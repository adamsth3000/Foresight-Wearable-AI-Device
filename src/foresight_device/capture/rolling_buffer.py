"""Deterministic retention policy for closed local media segments."""

from __future__ import annotations

from datetime import datetime, timedelta
from pathlib import Path

from .models import MediaSegment


class RollingBuffer:
    """Retain recent segments while protecting any segment selected for an event."""

    def __init__(self, retention_seconds: float) -> None:
        if retention_seconds <= 0:
            raise ValueError("retention_seconds must be positive")
        self._retention = timedelta(seconds=retention_seconds)
        self._segments: list[MediaSegment] = []
        self._promoted_paths: set[Path] = set()

    @property
    def segments(self) -> tuple[MediaSegment, ...]:
        """Return known segments in deterministic time and sequence order."""

        return tuple(self._segments)

    @property
    def promoted_paths(self) -> frozenset[Path]:
        """Return buffer files protected from normal expiration."""

        return frozenset(self._promoted_paths)

    def add(self, segment: MediaSegment, now: datetime) -> tuple[Path, ...]:
        """Add one closed segment and remove expired unpromoted files."""

        if segment.path not in {known.path for known in self._segments}:
            self._segments.append(segment)
            self._segments.sort(key=lambda item: (item.started_at_utc, item.sequence))
        return self.expire(now)

    def expire(self, now: datetime) -> tuple[Path, ...]:
        """Delete old unpromoted files and return the paths removed."""

        cutoff = now - self._retention
        expired = [
            segment
            for segment in self._segments
            if segment.ended_at_utc < cutoff and segment.path not in self._promoted_paths
        ]
        for segment in expired:
            segment.path.unlink(missing_ok=True)
        expired_paths = {segment.path for segment in expired}
        self._segments = [
            segment for segment in self._segments if segment.path not in expired_paths
        ]
        return tuple(segment.path for segment in expired)

    def select_pre_event(
        self,
        trigger_utc: datetime,
        pre_seconds: float,
    ) -> tuple[MediaSegment, ...]:
        """Select and protect segments overlapping the requested pre-event window."""

        if pre_seconds < 0:
            raise ValueError("pre_seconds cannot be negative")
        start = trigger_utc - timedelta(seconds=pre_seconds)
        selected = tuple(
            segment
            for segment in self._segments
            if segment.ended_at_utc >= start and segment.started_at_utc <= trigger_utc
        )
        self.promote(selected)
        return selected

    def promote(self, segments: tuple[MediaSegment, ...]) -> None:
        """Protect selected segment files from normal rolling expiration."""

        self._promoted_paths.update(segment.path for segment in segments)

    def release(self, segments: tuple[MediaSegment, ...]) -> None:
        """Release aborted-event segments back to normal retention policy."""

        self._promoted_paths.difference_update(segment.path for segment in segments)
