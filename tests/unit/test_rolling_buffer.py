from datetime import UTC, datetime, timedelta
from pathlib import Path

from foresight_device.capture import MediaSegment, RollingBuffer


def _segment(path: Path, sequence: int, start_seconds: int) -> MediaSegment:
    started_at = datetime(2026, 8, 27, tzinfo=UTC) + timedelta(seconds=start_seconds)
    path.write_bytes(b"segment")
    return MediaSegment(sequence, path, started_at, started_at + timedelta(seconds=2))


def test_retention_expires_old_unpromoted_segments(tmp_path: Path) -> None:
    buffer = RollingBuffer(retention_seconds=5)
    old = _segment(tmp_path / "old.mp4", 0, 0)
    current = _segment(tmp_path / "current.mp4", 1, 10)

    buffer.add(old, now=current.ended_at_utc)
    buffer.add(current, now=current.ended_at_utc)

    assert not old.path.exists()
    assert buffer.segments == (current,)


def test_promoted_pre_event_segments_are_not_expired(tmp_path: Path) -> None:
    buffer = RollingBuffer(retention_seconds=5)
    protected = _segment(tmp_path / "protected.mp4", 0, 0)
    buffer.add(protected, now=protected.ended_at_utc)
    selected = buffer.select_pre_event(protected.ended_at_utc, pre_seconds=5)

    buffer.expire(protected.ended_at_utc + timedelta(seconds=10))

    assert selected == (protected,)
    assert protected.path.exists()
    assert protected.path in buffer.promoted_paths
