from datetime import UTC, datetime

from foresight_device.capture import ConfiguredMediaSource, MediaSourceDescriptor


def test_configured_media_source_keeps_source_neutral_session_metadata() -> None:
    descriptor = MediaSourceDescriptor(
        source_id="test-source",
        capture_session_id="session-1",
        transport="rtsp",
        uri="rtsp://example.test/live",
        video_source="rear_camera",
        audio_source="microphone",
        session_started_utc=datetime(2026, 8, 27, tzinfo=UTC),
        session_started_monotonic_ns=123,
        metadata={"manufacturer": "test"},
    )

    source = ConfiguredMediaSource(descriptor)

    assert source.descriptor.source_id == "test-source"
    assert source.descriptor.video_source == "rear_camera"
    assert source.descriptor.metadata == {"manufacturer": "test"}
