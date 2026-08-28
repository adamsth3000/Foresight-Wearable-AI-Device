"""Normalized source, segment, and event records for local media capture."""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from uuid import uuid4


@dataclass(frozen=True, slots=True)
class MediaSourceDescriptor:
    """Source-neutral identity and timing anchors for one live media session."""

    source_id: str
    capture_session_id: str
    transport: str
    uri: str
    video_source: str
    audio_source: str
    session_started_utc: datetime
    session_started_monotonic_ns: int
    source_session_id: str | None = None
    metadata: Mapping[str, str] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class MediaSegment:
    """One closed stream-copy segment with approximate wall-clock boundaries."""

    sequence: int
    path: Path
    started_at_utc: datetime
    ended_at_utc: datetime


@dataclass(frozen=True, slots=True)
class CaptureEvent:
    """Durable result of promoting a bounded window from the rolling buffer."""

    event_id: str
    trigger_type: str
    trigger_utc: datetime
    trigger_monotonic_ns: int
    requested_pre_seconds: float
    requested_post_seconds: float
    actual_start_utc: datetime
    actual_end_utc: datetime
    media_path: Path
    media_sha256: str
    manifest_path: Path
    source: MediaSourceDescriptor
    created_at_utc: datetime
    sensors_path: Path | None = None
    sensor_record_count: int = 0

    @classmethod
    def new_id(cls) -> str:
        """Create an event identifier without tying event creation to a device."""

        return str(uuid4())
