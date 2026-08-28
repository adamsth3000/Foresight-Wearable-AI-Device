import os
from collections.abc import Sequence
from datetime import UTC, datetime, timedelta
from io import StringIO
from pathlib import Path

from foresight_device.capture import (
    ConfiguredMediaSource,
    EventService,
    FfmpegRtspIngress,
    MediaSegment,
    MediaSourceDescriptor,
    RollingBuffer,
)
from foresight_device.cli import run_capture_cli


def _source(capture_session_id: str = "session-1") -> ConfiguredMediaSource:
    return ConfiguredMediaSource(
        MediaSourceDescriptor(
            source_id="generic-source",
            capture_session_id=capture_session_id,
            transport="rtsp",
            uri="rtsp://example.test:8555/custom-path",
            video_source="camera",
            audio_source="microphone",
            session_started_utc=datetime(2026, 8, 27, tzinfo=UTC),
            session_started_monotonic_ns=1,
        )
    )


def test_rtsp_ingress_uses_tcp_stream_copy_and_configured_uri(tmp_path: Path) -> None:
    ingress = FfmpegRtspIngress(_source(), tmp_path, lambda _: None, executable="ffmpeg-test")

    command = ingress.command

    assert command[0] == "ffmpeg-test"
    assert command[command.index("-rtsp_transport") + 1] == "tcp"
    assert command[command.index("-i") + 1] == "rtsp://example.test:8555/custom-path"
    assert command[command.index("-c") + 1] == "copy"
    assert "segment" in command


def test_ingress_callback_and_stop_handle_an_incomplete_early_event(tmp_path: Path) -> None:
    """A forced final scan cannot crash on an event with no qualifying segment."""

    source = _source()
    rolling_buffer = RollingBuffer(retention_seconds=60)
    completed = []
    event_service: EventService

    def concatenate(paths: Sequence[Path], output_path: Path) -> None:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"".join(path.read_bytes() for path in paths))

    def on_segment(segment: MediaSegment) -> None:
        # Match the CLI ordering: the completed segment is available before
        # event finalization considers it.
        rolling_buffer.add(segment, now=segment.ended_at_utc)
        completed.extend(event_service.observe_segment(segment))

    buffer_dir = tmp_path / "buffer" / source.descriptor.capture_session_id
    buffer_dir.mkdir(parents=True)
    ingress = FfmpegRtspIngress(source, buffer_dir, on_segment, executable="ffmpeg-test")
    event_service = EventService(
        source,
        rolling_buffer,
        tmp_path / "events",
        concatenate,
        pre_seconds=30,
        post_seconds=15,
    )
    trigger = datetime(2026, 8, 27, tzinfo=UTC)
    event_service.trigger(trigger_utc=trigger, trigger_monotonic_ns=99)
    late_segment = buffer_dir / "segment-000000000.mp4"
    late_segment.write_bytes(b"late")
    late_end = trigger + timedelta(seconds=22)
    os.utime(late_segment, (late_end.timestamp(), late_end.timestamp()))

    ingress.stop()

    assert completed == []
    assert event_service.pending_count == 0
    assert event_service.abort_pending() == 0
    assert ingress.cleanup_temporary_media() is None
    assert not buffer_dir.exists()


def test_session_scoped_ingress_ignores_orphaned_segments_from_another_session(
    tmp_path: Path,
) -> None:
    buffer_root = tmp_path / "buffer"
    old_session_dir = buffer_root / "session-a"
    old_session_dir.mkdir(parents=True)
    stale_segment = old_session_dir / "segment-000000000.mp4"
    stale_segment.write_bytes(b"old-session")

    new_session_dir = buffer_root / "session-b"
    ingress = FfmpegRtspIngress(
        _source("session-b"),
        new_session_dir,
        lambda _: None,
        executable="ffmpeg-test",
    )

    assert ingress.scan_completed_segments(force=True) == ()
    assert stale_segment.exists()
    assert not new_session_dir.exists()


def test_promoted_event_survives_current_session_temporary_media_cleanup(tmp_path: Path) -> None:
    source = _source("session-a")
    session_dir = tmp_path / "buffer" / source.descriptor.capture_session_id
    session_dir.mkdir(parents=True)
    rolling_buffer = RollingBuffer(retention_seconds=60)

    def concatenate(paths: Sequence[Path], output_path: Path) -> None:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(b"".join(path.read_bytes() for path in paths))

    service = EventService(
        source,
        rolling_buffer,
        tmp_path / "events",
        concatenate,
        pre_seconds=0,
        post_seconds=0,
    )
    started_at = datetime(2026, 8, 27, tzinfo=UTC)
    segment_path = session_dir / "segment-000000000.mp4"
    segment_path.write_bytes(b"event-media")
    segment = MediaSegment(0, segment_path, started_at, started_at + timedelta(seconds=2))
    service.trigger(trigger_utc=started_at, trigger_monotonic_ns=99)
    rolling_buffer.add(segment, now=segment.ended_at_utc)
    event = service.observe_segment(segment)[0]
    ingress = FfmpegRtspIngress(source, session_dir, lambda _: None, executable="ffmpeg-test")

    assert ingress.cleanup_temporary_media() is None
    assert not session_dir.exists()
    assert event.media_path.exists()
    assert event.manifest_path.exists()


def test_cleanup_failure_is_reported_without_touching_persistent_events(
    monkeypatch,
    tmp_path: Path,
) -> None:
    session_dir = tmp_path / "buffer" / "session-a"
    session_dir.mkdir(parents=True)
    event_path = tmp_path / "events" / "event-a" / "event.mp4"
    event_path.parent.mkdir(parents=True)
    event_path.write_bytes(b"persistent-event")
    ingress = FfmpegRtspIngress(_source("session-a"), session_dir, lambda _: None)

    def fail_cleanup(_: Path) -> None:
        raise OSError("sharing violation")

    monkeypatch.setattr("foresight_device.capture.ffmpeg_ingress.shutil.rmtree", fail_cleanup)
    message = ingress.cleanup_temporary_media()

    assert message is not None
    assert "cleanup failed" in message
    assert event_path.exists()


def test_capture_cli_uses_capture_session_id_for_temporary_buffer(
    monkeypatch,
    tmp_path: Path,
) -> None:
    created: list[object] = []

    class FakeIngress:
        def __init__(self, source, buffer_dir, on_segment, **_kwargs) -> None:
            self.source = source
            self.buffer_dir = buffer_dir
            self.on_segment = on_segment
            created.append(self)

        @property
        def is_running(self) -> bool:
            return False

        @property
        def failure_message(self) -> None:
            return None

        @property
        def ffmpeg_running(self) -> bool:
            return False

        @property
        def reconnecting(self) -> bool:
            return False

        @property
        def reconnect_attempt(self) -> int:
            return 0

        def start(self) -> None:
            return None

        def stop(self) -> None:
            return None

        def cleanup_temporary_media(self) -> None:
            return None

        def concatenate_to_mp4(self, _paths: Sequence[Path], _output_path: Path) -> None:
            return None

    monkeypatch.setattr("foresight_device.cli.FfmpegRtspIngress", FakeIngress)
    output = StringIO()

    assert (
        run_capture_cli(
            StringIO("stop\n"),
            output,
            source_uri="rtsp://example.test/live",
            ffmpeg_executable="ffmpeg-test",
            data_root=tmp_path,
        )
        == 0
    )

    ingress = created[0]
    assert ingress.buffer_dir.parent == tmp_path / "buffer"
    assert ingress.buffer_dir.name == ingress.source.descriptor.capture_session_id
