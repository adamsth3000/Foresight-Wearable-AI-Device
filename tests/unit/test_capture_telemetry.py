import hashlib
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from urllib.request import Request, urlopen

import pytest

from foresight_device.capture import (
    ConfiguredMediaSource,
    EventControlService,
    EventService,
    MediaSegment,
    MediaSourceDescriptor,
    RollingBuffer,
    SessionTelemetryStore,
    TelemetryReceiver,
)
from foresight_device.capture.phone_media import PhoneMediaIngestService
from foresight_device.capture.telemetry import TelemetryProtocolError


def _bind_payload() -> dict[str, object]:
    return {
        "source_id": "galaxy_s24_fe",
        "source_session_id": "phone-source-1",
        "source_metadata": {"imu_source": "android_sensor_manager"},
        "timing_anchor": {
            "utc": "2026-08-28T12:00:00+00:00",
            "elapsed_realtime_nanos": 1_000_000_000,
        },
    }


def _record(offset_seconds: int, record_type: str = "accelerometer") -> dict[str, object]:
    return {
        "record_type": record_type,
        "timestamp_elapsed_realtime_nanos": 1_000_000_000 + offset_seconds * 1_000_000_000,
        "x": 1.0,
        "y": 2.0,
        "z": 3.0,
        "accuracy": 3,
    }


def test_store_binds_source_session_and_maps_elapsed_realtime_to_utc(tmp_path: Path) -> None:
    store = SessionTelemetryStore(tmp_path / "sessions", "capture-1")

    response = store.bind(_bind_payload())
    accepted = store.append_records(
        {
            "source_id": "galaxy_s24_fe",
            "source_session_id": "phone-source-1",
            "records": [_record(3), _record(5, "gyroscope")],
        }
    )

    assert response["capture_session_id"] == "capture-1"
    assert accepted == 2
    records = [
        json.loads(line) for line in store.sensors_path.read_text(encoding="utf-8").splitlines()
    ]
    assert [record["observed_at_utc"] for record in records] == [
        "2026-08-28T12:00:03+00:00",
        "2026-08-28T12:00:05+00:00",
    ]
    assert {record["capture_session_id"] for record in records} == {"capture-1"}
    assert {record["source_session_id"] for record in records} == {"phone-source-1"}
    timing = json.loads((tmp_path / "sessions" / "capture-1" / "timing.json").read_text())
    assert timing["source_bindings"][0]["source_id"] == "galaxy_s24_fe"


def test_unbound_or_malformed_telemetry_is_rejected_without_persistence(tmp_path: Path) -> None:
    store = SessionTelemetryStore(tmp_path / "sessions", "capture-1")

    with pytest.raises(TelemetryProtocolError, match="not bound"):
        store.append_records(
            {"source_id": "galaxy_s24_fe", "source_session_id": "unknown", "records": [_record(1)]}
        )
    with pytest.raises(TelemetryProtocolError, match="integer"):
        store.bind(
            {
                **_bind_payload(),
                "timing_anchor": {
                    "utc": "2026-08-28T12:00:00+00:00",
                    "elapsed_realtime_nanos": "bad",
                },
            }
        )
    assert not store.sensors_path.exists()


def test_receiver_binds_and_accepts_json_batches_over_lan_contract(tmp_path: Path) -> None:
    store = SessionTelemetryStore(tmp_path / "sessions", "capture-1")
    receiver = TelemetryReceiver(store, "127.0.0.1", 0)
    receiver.start()
    try:
        base = f"http://127.0.0.1:{receiver.port}"
        bind_response = _post_json(base + "/v1/bind", _bind_payload())
        records_response = _post_json(
            base + "/v1/records",
            {
                "source_id": "galaxy_s24_fe",
                "source_session_id": "phone-source-1",
                "records": [_record(1)],
            },
        )
    finally:
        receiver.stop()

    assert bind_response["capture_session_id"] == "capture-1"
    assert records_response == {"accepted": 1}


def test_receiver_exposes_authoritative_bounded_event_controls(tmp_path: Path) -> None:
    store = SessionTelemetryStore(tmp_path / "sessions", "capture-1")
    source = ConfiguredMediaSource(
        MediaSourceDescriptor(
            source_id="source",
            capture_session_id="capture-1",
            transport="rtsp",
            uri="rtsp://x",
            video_source="camera",
            audio_source="microphone",
            session_started_utc=datetime.now(UTC),
            session_started_monotonic_ns=1,
        )
    )
    buffer = RollingBuffer(60)

    def concatenate(paths: tuple[Path, ...], output: Path) -> None:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(b"".join(path.read_bytes() for path in paths))

    service = EventService(source, buffer, tmp_path / "events", concatenate)
    receiver = TelemetryReceiver(store, "127.0.0.1", 0, EventControlService(service))
    receiver.start()
    try:
        base = f"http://127.0.0.1:{receiver.port}"
        started = _post_json(base + "/events/start", {})
        recording = _get_json(base + "/events/status")
        ended = _post_json(base + "/events/end", {})
        finalizing = _get_json(base + "/events/status")
        # Complete the existing finalizing event with a real closed segment, then verify that
        # status reads EventService at request time instead of retaining a handler-side cache.
        active_event_id = str(started["event_id"])
        active_start = service._bounded.trigger_utc  # noqa: SLF001 - endpoint lifecycle fixture
        segment_path = tmp_path / "closed.mp4"
        segment_path.write_bytes(b"real-media")
        segment = MediaSegment(
            0,
            segment_path,
            active_start,
            active_start + timedelta(seconds=1),
        )
        buffer.add(segment, now=segment.ended_at_utc)
        service.observe_segment(segment)
        idle = _get_json(base + "/events/status")
    finally:
        receiver.stop()
    assert started["state"] == "recording_bounded_event"
    assert recording == started
    assert ended == {"state": "finalizing", "event_id": started["event_id"], "pending_events": 1}
    assert finalizing == ended
    assert active_event_id == ended["event_id"]
    assert idle == {"state": "idle", "event_id": None, "pending_events": 0}


def test_receiver_streams_phone_media_to_the_existing_private_listener(tmp_path: Path) -> None:
    event_id = "c4426cce-1ac2-47d6-9e9f-0c8d5c9e0543"
    events_root = tmp_path / "events"
    event_dir = events_root / event_id
    event_dir.mkdir(parents=True)
    (event_dir / "event.mp4").write_bytes(b"network")
    (event_dir / "manifest.json").write_text(
        json.dumps({"event_id": event_id, "media": {"filename": "event.mp4", "sha256": "network"}})
    )
    store = SessionTelemetryStore(tmp_path / "sessions", "capture-1")
    media = b"phone-media"
    receiver = TelemetryReceiver(
        store,
        "127.0.0.1",
        0,
        phone_media_service=PhoneMediaIngestService(
            events_root, probe_media=lambda _path: _phone_probe()
        ),
    )
    receiver.start()
    try:
        request = Request(
            f"http://127.0.0.1:{receiver.port}/events/{event_id}/phone-media",
            data=media,
            method="POST",
            headers={
                "Content-Type": "application/octet-stream",
                "X-Foresight-Source-Session-Id": "phone-session",
                "X-Foresight-Recording-Id": "recording-1",
                "X-Foresight-Media-Length": str(len(media)),
                "X-Foresight-Media-Sha256": hashlib.sha256(media).hexdigest(),
                "X-Foresight-Observed-Start-Utc": "2026-08-30T12:00:00+00:00",
                "X-Foresight-Observed-End-Utc": "2026-08-30T12:00:02+00:00",
                "X-Foresight-Start-Offset-Ms": "0",
                "X-Foresight-End-Offset-Ms": "2000",
                "X-Foresight-Output-Duration-Ms": "2000",
                "X-Foresight-Audio-Present": "true",
            },
        )
        with urlopen(request, timeout=3) as response:  # noqa: S310 - loopback receiver test
            payload = json.loads(response.read())
    finally:
        receiver.stop()
    assert payload["state"] == "synced"
    assert payload["validated"] is True
    assert payload["authoritative_media_sha256"] == hashlib.sha256(media).hexdigest()
    assert (event_dir / "phone_media" / "authoritative.mp4").read_bytes() == media


def test_event_copies_only_sensor_records_in_actual_media_window(tmp_path: Path) -> None:
    store = SessionTelemetryStore(tmp_path / "sessions", "capture-1")
    store.bind(_bind_payload())
    store.append_records(
        {
            "source_id": "galaxy_s24_fe",
            "source_session_id": "phone-source-1",
            "records": [_record(1), _record(3), _record(9)],
        }
    )
    started = datetime(2026, 8, 28, 12, 0, tzinfo=UTC)
    descriptor = MediaSourceDescriptor(
        source_id="rtsp-ingress",
        source_session_id="ingress-1",
        capture_session_id="capture-1",
        transport="rtsp",
        uri="rtsp://example.test/stream",
        video_source="phone_rear_camera",
        audio_source="phone_microphone",
        session_started_utc=started,
        session_started_monotonic_ns=1,
    )
    source = ConfiguredMediaSource(descriptor)
    segment_path = tmp_path / "segment.mp4"
    segment_path.write_bytes(b"media")
    segment = MediaSegment(0, segment_path, started, started + timedelta(seconds=5))
    buffer = RollingBuffer(60)
    buffer.add(segment, now=segment.ended_at_utc)

    def concatenate(paths: tuple[Path, ...], output: Path) -> None:
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(b"".join(path.read_bytes() for path in paths))

    service = EventService(
        source,
        buffer,
        tmp_path / "events",
        concatenate,
        pre_seconds=0,
        post_seconds=0,
        telemetry_store=store,
    )
    service.trigger(trigger_utc=started, trigger_monotonic_ns=1)
    event = service.observe_segment(segment)[0]

    assert event.sensors_path is not None
    event_records = [json.loads(line) for line in event.sensors_path.read_text().splitlines()]
    assert [record["timestamp_elapsed_realtime_nanos"] for record in event_records] == [
        2_000_000_000,
        4_000_000_000,
    ]
    manifest = json.loads(event.manifest_path.read_text())
    assert manifest["sensors"]["record_count"] == 2


def _post_json(url: str, payload: dict[str, object]) -> dict[str, object]:
    request = Request(
        url,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(request, timeout=3) as response:  # noqa: S310 - loopback test server
        return json.loads(response.read())


def _get_json(url: str) -> dict[str, object]:
    with urlopen(url, timeout=3) as response:  # noqa: S310 - loopback test server
        return json.loads(response.read())


def _phone_probe() -> dict[str, object]:
    return {
        "format": {"duration": "2.0", "format_name": "mp4"},
        "streams": [{"codec_type": "video"}, {"codec_type": "audio"}],
    }
