"""LAN-only, source-neutral sensor telemetry binding and persistence."""

from __future__ import annotations

import json
import threading
from collections.abc import Mapping
from datetime import UTC, datetime, timedelta
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any, BinaryIO, cast

from foresight_device.core.logging import get_logger

from .phone_media import PhoneMediaIngestError, PhoneMediaIngestService

LOGGER = get_logger(__name__)


class TelemetryProtocolError(ValueError):
    """Raised when a telemetry request cannot be safely associated or stored."""


class SessionTelemetryStore:
    """Persist bound source telemetry under one canonical capture session."""

    def __init__(self, sessions_root: Path, capture_session_id: str) -> None:
        self.capture_session_id = capture_session_id
        self.session_dir = sessions_root / capture_session_id
        self._timing_path = self.session_dir / "timing.json"
        self._sensors_path = self.session_dir / "sensors.jsonl"
        self._lock = threading.RLock()
        self._bindings: dict[tuple[str, str], dict[str, Any]] = {}
        self.session_dir.mkdir(parents=True, exist_ok=True)
        self._write_timing()

    @property
    def sensors_path(self) -> Path:
        """Return the durable JSONL path for this canonical session."""

        return self._sensors_path

    def bind(self, payload: Mapping[str, Any]) -> dict[str, Any]:
        """Associate one physical source session with this logical capture session."""

        source_id = _required_string(payload, "source_id")
        source_session_id = _required_string(payload, "source_session_id")
        anchor = _required_mapping(payload, "timing_anchor")
        elapsed_ns = _required_int(anchor, "elapsed_realtime_nanos")
        utc = _parse_utc(_required_string(anchor, "utc"))
        binding = {
            "source_id": source_id,
            "source_session_id": source_session_id,
            "timing_anchor": {
                "elapsed_realtime_nanos": elapsed_ns,
                "utc": utc.isoformat(),
            },
            "source_metadata": dict(_optional_mapping(payload, "source_metadata")),
            "bound_at_utc": datetime.now(UTC).isoformat(),
        }
        with self._lock:
            self._bindings[(source_id, source_session_id)] = binding
            self._write_timing()
        LOGGER.info(
            "telemetry source bound capture_session_id=%s source_id=%s source_session_id=%s",
            self.capture_session_id,
            source_id,
            source_session_id,
        )
        return {
            "capture_session_id": self.capture_session_id,
            "source_id": source_id,
            "source_session_id": source_session_id,
        }

    def append_records(self, payload: Mapping[str, Any]) -> int:
        """Append records after verifying their previously negotiated source binding."""

        source_id = _required_string(payload, "source_id")
        source_session_id = _required_string(payload, "source_session_id")
        records = payload.get("records")
        if not isinstance(records, list):
            raise TelemetryProtocolError("records must be a JSON array")
        key = (source_id, source_session_id)
        with self._lock:
            binding = self._bindings.get(key)
            if binding is None:
                raise TelemetryProtocolError("source session is not bound to this capture session")
            normalized = [self._normalize_record(record, binding) for record in records]
            if normalized:
                with self._sensors_path.open("a", encoding="utf-8") as handle:
                    for record in normalized:
                        handle.write(json.dumps(record, sort_keys=True) + "\n")
        return len(normalized)

    def select_records(self, start_utc: datetime, end_utc: datetime) -> tuple[dict[str, Any], ...]:
        """Return persisted observations in an event's actual preserved media window."""

        if not self._sensors_path.exists():
            return ()
        selected: list[dict[str, Any]] = []
        with self._sensors_path.open(encoding="utf-8") as handle:
            for line in handle:
                record = json.loads(line)
                observed_at = _parse_utc(str(record["observed_at_utc"]))
                if start_utc <= observed_at <= end_utc:
                    selected.append(record)
        return tuple(selected)

    def _normalize_record(self, record: object, binding: Mapping[str, Any]) -> dict[str, Any]:
        if not isinstance(record, Mapping):
            raise TelemetryProtocolError("each telemetry record must be a JSON object")
        timestamp_ns = _required_int(record, "timestamp_elapsed_realtime_nanos")
        anchor = _required_mapping(binding, "timing_anchor")
        anchor_elapsed_ns = _required_int(anchor, "elapsed_realtime_nanos")
        anchor_utc = _parse_utc(_required_string(anchor, "utc"))
        observed_at = anchor_utc + timedelta(
            microseconds=(timestamp_ns - anchor_elapsed_ns) / 1_000,
        )
        normalized = dict(record)
        normalized.update(
            {
                "capture_session_id": self.capture_session_id,
                "source_id": binding["source_id"],
                "source_session_id": binding["source_session_id"],
                "observed_at_utc": observed_at.isoformat(),
                "received_at_utc": datetime.now(UTC).isoformat(),
            }
        )
        return normalized

    def _write_timing(self) -> None:
        payload = {
            "schema_version": 1,
            "capture_session_id": self.capture_session_id,
            "timebase": {
                "primary": "android_systemclock_elapsed_realtime_nanos",
                "utc_mapping": "source timing anchors map elapsed realtime observations to UTC",
                "transport_note": "telemetry receipt time is provenance only, not observation time",
            },
            "source_bindings": list(self._bindings.values()),
        }
        self._timing_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


class TelemetryReceiver:
    """Small LAN HTTP receiver that binds source sessions and writes telemetry."""

    def __init__(
        self,
        store: SessionTelemetryStore,
        host: str = "0.0.0.0",
        port: int = 8766,
        control_service: Any | None = None,
        phone_media_service: PhoneMediaIngestService | None = None,
    ) -> None:
        self.store = store
        self._server = ThreadingHTTPServer(
            (host, port), _handler_for(store, control_service, phone_media_service)
        )
        self._thread: threading.Thread | None = None

    @property
    def port(self) -> int:
        return int(self._server.server_address[1])

    def start(self) -> None:
        if self._thread is not None:
            return
        self._thread = threading.Thread(target=self._server.serve_forever, daemon=True)
        self._thread.start()
        LOGGER.info("telemetry receiver listening port=%d", self.port)

    def stop(self) -> None:
        if self._thread is None:
            self._server.server_close()
            return
        self._server.shutdown()
        self._server.server_close()
        self._thread.join(timeout=3)
        self._thread = None


def copy_event_sensor_records(
    store: SessionTelemetryStore,
    event_dir: Path,
    start_utc: datetime,
    end_utc: datetime,
) -> tuple[Path, int]:
    """Copy bound sensor observations into a promoted event without moving session data."""

    records = store.select_records(start_utc, end_utc)
    destination = event_dir / "sensors.jsonl"
    with destination.open("w", encoding="utf-8") as handle:
        for record in records:
            handle.write(json.dumps(record, sort_keys=True) + "\n")
    return destination, len(records)


def _handler_for(
    store: SessionTelemetryStore,
    control: Any | None = None,
    phone_media: PhoneMediaIngestService | None = None,
) -> type[BaseHTTPRequestHandler]:
    class TelemetryHandler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 - HTTP handler API
            if control is not None and self.path == "/events/status":
                status = control.status()
                LOGGER.debug(
                    "GET /events/status state=%s event_id=%s",
                    status["state"],
                    status["event_id"],
                )
                _write_response(self, HTTPStatus.OK, status)
            else:
                _write_response(self, HTTPStatus.NOT_FOUND, {"error": "unknown endpoint"})

        def do_POST(self) -> None:  # noqa: N802 - HTTP handler API
            try:
                if (
                    phone_media is not None
                    and self.path.startswith("/events/")
                    and self.path.endswith("/phone-media")
                ):
                    event_id = (
                        self.path.removeprefix("/events/").removesuffix("/phone-media").strip("/")
                    )
                    content_type = (
                        self.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
                    )
                    if content_type != "application/octet-stream":
                        raise PhoneMediaIngestError(
                            "phone-media uploads require application/octet-stream"
                        )
                    result = phone_media.ingest(
                        event_id,
                        dict(self.headers.items()),
                        cast(BinaryIO, self.rfile),
                    )
                    _write_response(
                        self,
                        HTTPStatus.OK,
                        {
                            "state": result.state,
                            "event_id": result.event_id,
                            "sha256": result.sha256,
                            "byte_size": result.byte_size,
                            "idempotent": result.idempotent,
                        },
                    )
                    return
                payload = _read_payload(self)
                if self.path == "/v1/bind":
                    _write_response(self, HTTPStatus.OK, store.bind(payload))
                elif self.path == "/v1/records":
                    _write_response(
                        self,
                        HTTPStatus.ACCEPTED,
                        {"accepted": store.append_records(payload)},
                    )
                elif control is not None and self.path == "/events/start":
                    _write_response(self, HTTPStatus.OK, control.start())
                elif control is not None and self.path == "/events/end":
                    _write_response(self, HTTPStatus.OK, control.end())
                elif control is not None and self.path == "/events/quick":
                    _write_response(self, HTTPStatus.ACCEPTED, control.quick())
                else:
                    _write_response(self, HTTPStatus.NOT_FOUND, {"error": "unknown endpoint"})
            except PhoneMediaIngestError as exc:
                _write_response(self, HTTPStatus(exc.status_code), {"error": str(exc)})
            except (TelemetryProtocolError, RuntimeError, json.JSONDecodeError) as exc:
                _write_response(self, HTTPStatus.BAD_REQUEST, {"error": str(exc)})

        def log_message(self, _format: str, *_args: object) -> None:
            return

    return TelemetryHandler


def _read_payload(handler: BaseHTTPRequestHandler) -> Mapping[str, Any]:
    length = int(handler.headers.get("Content-Length", "0"))
    if length <= 0 or length > 1_000_000:
        raise TelemetryProtocolError("request body must be between 1 and 1000000 bytes")
    payload = json.loads(handler.rfile.read(length))
    if not isinstance(payload, Mapping):
        raise TelemetryProtocolError("request body must be a JSON object")
    return payload


def _write_response(
    handler: BaseHTTPRequestHandler,
    status: HTTPStatus,
    payload: Mapping[str, Any],
) -> None:
    body = json.dumps(payload).encode("utf-8")
    handler.send_response(status)
    handler.send_header("Content-Type", "application/json")
    handler.send_header("Content-Length", str(len(body)))
    handler.end_headers()
    handler.wfile.write(body)


def _required_string(payload: Mapping[str, Any], name: str) -> str:
    value = payload.get(name)
    if not isinstance(value, str) or not value:
        raise TelemetryProtocolError(f"{name} must be a non-empty string")
    return value


def _required_int(payload: Mapping[str, Any], name: str) -> int:
    value = payload.get(name)
    if not isinstance(value, int) or isinstance(value, bool):
        raise TelemetryProtocolError(f"{name} must be an integer")
    return value


def _required_mapping(payload: Mapping[str, Any], name: str) -> Mapping[str, Any]:
    value = payload.get(name)
    if not isinstance(value, Mapping):
        raise TelemetryProtocolError(f"{name} must be an object")
    return value


def _optional_mapping(payload: Mapping[str, Any], name: str) -> Mapping[str, Any]:
    value = payload.get(name, {})
    if not isinstance(value, Mapping):
        raise TelemetryProtocolError(f"{name} must be an object")
    return value


def _parse_utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise TelemetryProtocolError("UTC timestamps must include an offset")
    return parsed.astimezone(UTC)
