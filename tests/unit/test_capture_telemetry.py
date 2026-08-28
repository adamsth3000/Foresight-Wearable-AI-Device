import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from urllib.request import Request, urlopen

import pytest

from foresight_device.capture import (
    ConfiguredMediaSource,
    EventService,
    MediaSegment,
    MediaSourceDescriptor,
    RollingBuffer,
    SessionTelemetryStore,
    TelemetryReceiver,
)
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
        json.loads(line)
        for line in store.sensors_path.read_text(encoding="utf-8").splitlines()
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
