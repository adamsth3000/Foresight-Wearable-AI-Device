import hashlib
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path

from foresight_device.capture import (
    ConfiguredMediaSource,
    EventService,
    MediaSegment,
    MediaSourceDescriptor,
    RollingBuffer,
)


def _source() -> ConfiguredMediaSource:
    return ConfiguredMediaSource(
        MediaSourceDescriptor(
            source_id="source-1",
            capture_session_id="session-1",
            transport="rtsp",
            uri="rtsp://example.test/live",
            video_source="rear_camera",
            audio_source="microphone",
            session_started_utc=datetime(2026, 8, 27, tzinfo=UTC),
            session_started_monotonic_ns=10,
        )
    )


def _segment(directory: Path, sequence: int, start_seconds: float) -> MediaSegment:
    started_at = datetime(2026, 8, 27, tzinfo=UTC) + timedelta(seconds=start_seconds)
    path = directory / f"segment-{sequence}.mp4"
    path.write_bytes(f"segment-{sequence}".encode())
    return MediaSegment(sequence, path, started_at, started_at + timedelta(seconds=2))


def _concatenate(paths: tuple[Path, ...], output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    output_path.write_bytes(b"".join(path.read_bytes() for path in paths))


def test_event_promotes_pre_and_post_roll_and_writes_hashed_manifest(tmp_path: Path) -> None:
    rolling_buffer = RollingBuffer(retention_seconds=60)
    pre = _segment(tmp_path, 0, 8)
    trigger_window = _segment(tmp_path, 1, 10)
    rolling_buffer.add(pre, now=pre.ended_at_utc)
    rolling_buffer.add(trigger_window, now=trigger_window.ended_at_utc)
    service = EventService(
        _source(),
        rolling_buffer,
        tmp_path / "events",
        _concatenate,
        pre_seconds=5,
        post_seconds=5,
    )
    trigger = datetime(2026, 8, 27, tzinfo=UTC) + timedelta(seconds=12)

    event_id = service.trigger(trigger_utc=trigger, trigger_monotonic_ns=99)
    completed = service.observe_segment(_segment(tmp_path, 2, 15))

    assert len(completed) == 1
    event = completed[0]
    assert event.event_id == event_id
    assert event.media_path.exists()
    assert event.media_sha256 == hashlib.sha256(event.media_path.read_bytes()).hexdigest()
    manifest = json.loads(event.manifest_path.read_text(encoding="utf-8"))
    assert manifest["trigger_type"] == "manual"
    assert manifest["capture_session_id"] == "session-1"
    assert manifest["media"]["sha256"] == event.media_sha256
    assert pre.path in rolling_buffer.promoted_paths


def test_abort_releases_incomplete_pending_event_segments(tmp_path: Path) -> None:
    rolling_buffer = RollingBuffer(retention_seconds=60)
    segment = _segment(tmp_path, 0, 0)
    rolling_buffer.add(segment, now=segment.ended_at_utc)
    service = EventService(_source(), rolling_buffer, tmp_path / "events", _concatenate)

    service.trigger(trigger_utc=segment.ended_at_utc)
    aborted = service.abort_pending()

    assert aborted == 1
    assert segment.path not in rolling_buffer.promoted_paths


def test_early_event_waits_for_post_roll_and_preserves_available_media(tmp_path: Path) -> None:
    """A young session may not have the requested pre-roll yet."""

    rolling_buffer = RollingBuffer(retention_seconds=60)
    service = EventService(
        _source(),
        rolling_buffer,
        tmp_path / "events",
        _concatenate,
        pre_seconds=30,
        post_seconds=5,
    )
    trigger = datetime(2026, 8, 27, tzinfo=UTC) + timedelta(seconds=1)

    event_id = service.trigger(trigger_utc=trigger, trigger_monotonic_ns=99)
    first = _segment(tmp_path, 0, 0)
    rolling_buffer.add(first, now=first.ended_at_utc)
    assert service.observe_segment(first) == ()

    middle = _segment(tmp_path, 1, 2)
    rolling_buffer.add(middle, now=middle.ended_at_utc)
    assert service.observe_segment(middle) == ()

    final = _segment(tmp_path, 2, 4)
    rolling_buffer.add(final, now=final.ended_at_utc)
    completed = service.observe_segment(final)

    assert len(completed) == 1
    event = completed[0]
    assert event.event_id == event_id
    assert event.actual_start_utc == first.started_at_utc
    assert event.actual_end_utc == final.ended_at_utc
    assert event.actual_start_utc > trigger - timedelta(seconds=30)
    assert service.pending_count == 0


def test_non_overlapping_late_segment_does_not_finalize_empty_event(tmp_path: Path) -> None:
    """A delayed first closed segment must not complete an event with no media."""

    rolling_buffer = RollingBuffer(retention_seconds=60)
    service = EventService(
        _source(),
        rolling_buffer,
        tmp_path / "events",
        _concatenate,
        pre_seconds=30,
        post_seconds=15,
    )
    trigger = datetime(2026, 8, 27, tzinfo=UTC)
    service.trigger(trigger_utc=trigger, trigger_monotonic_ns=99)
    late = _segment(tmp_path, 0, 20)
    rolling_buffer.add(late, now=late.ended_at_utc)

    assert service.observe_segment(late) == ()
    assert service.pending_count == 1
    assert service.abort_pending() == 1


def test_capture_progress_across_a_segment_gap_finalizes_early_event(tmp_path: Path) -> None:
    """Post-roll completion must not require a segment to straddle its endpoint."""

    rolling_buffer = RollingBuffer(retention_seconds=60)
    service = EventService(
        _source(),
        rolling_buffer,
        tmp_path / "events",
        _concatenate,
        pre_seconds=30,
        post_seconds=15,
    )
    trigger = datetime(2026, 8, 27, tzinfo=UTC)
    service.trigger(trigger_utc=trigger, trigger_monotonic_ns=99)

    # These normal two-second files leave a gap across the 15-second endpoint.
    # The final file proves capture has progressed beyond it but is not selected.
    selected_starts = (0.0, 2.1, 4.2, 6.3, 8.4, 10.5, 12.6)
    for sequence, start_seconds in enumerate(selected_starts):
        segment = _segment(tmp_path, sequence, start_seconds)
        rolling_buffer.add(segment, now=segment.ended_at_utc)
        assert service.observe_segment(segment) == ()

    progress_segment = _segment(tmp_path, len(selected_starts), 15.4)
    rolling_buffer.add(progress_segment, now=progress_segment.ended_at_utc)
    completed = service.observe_segment(progress_segment)

    assert len(completed) == 1
    assert completed[0].actual_start_utc == trigger
    assert completed[0].actual_end_utc == trigger + timedelta(seconds=14.6)
    assert service.pending_count == 0


def test_mature_buffer_preserves_requested_thirty_second_pre_and_fifteen_second_post(
    tmp_path: Path,
) -> None:
    rolling_buffer = RollingBuffer(retention_seconds=60)
    pre = _segment(tmp_path, 0, 0)
    trigger_segment = _segment(tmp_path, 1, 28)
    rolling_buffer.add(pre, now=pre.ended_at_utc)
    rolling_buffer.add(trigger_segment, now=trigger_segment.ended_at_utc)
    service = EventService(
        _source(),
        rolling_buffer,
        tmp_path / "events",
        _concatenate,
        pre_seconds=30,
        post_seconds=15,
    )
    trigger = datetime(2026, 8, 27, tzinfo=UTC) + timedelta(seconds=30)

    service.trigger(trigger_utc=trigger, trigger_monotonic_ns=99)
    final = _segment(tmp_path, 2, 44)
    rolling_buffer.add(final, now=final.ended_at_utc)
    completed = service.observe_segment(final)

    assert len(completed) == 1
    assert completed[0].actual_start_utc == pre.started_at_utc
    assert completed[0].actual_end_utc == final.ended_at_utc
