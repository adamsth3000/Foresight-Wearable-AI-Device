"""Manual event promotion over source-neutral rolling media segments."""

from __future__ import annotations

import hashlib
import json
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path

from foresight_device.core.logging import get_logger

from .media_source import MediaSource
from .models import CaptureEvent, MediaSegment
from .rolling_buffer import RollingBuffer
from .telemetry import SessionTelemetryStore, copy_event_sensor_records

SegmentConcatenator = Callable[[Sequence[Path], Path], None]
LOGGER = get_logger(__name__)


@dataclass(slots=True)
class _PendingEvent:
    event_id: str
    trigger_type: str
    trigger_utc: datetime
    trigger_monotonic_ns: int
    requested_pre_seconds: float
    requested_post_seconds: float
    segments: list[MediaSegment]

    @property
    def required_end_utc(self) -> datetime:
        return self.trigger_utc + timedelta(seconds=self.requested_post_seconds)


class EventService:
    """Promote bounded media windows when a manual or future trigger occurs."""

    def __init__(
        self,
        source: MediaSource,
        rolling_buffer: RollingBuffer,
        events_dir: Path,
        concatenate_segments: SegmentConcatenator,
        *,
        pre_seconds: float = 30.0,
        post_seconds: float = 15.0,
        telemetry_store: SessionTelemetryStore | None = None,
    ) -> None:
        if pre_seconds < 0 or post_seconds < 0:
            raise ValueError("event durations cannot be negative")
        self._source = source
        self._rolling_buffer = rolling_buffer
        self._events_dir = events_dir
        self._concatenate_segments = concatenate_segments
        self._pre_seconds = pre_seconds
        self._post_seconds = post_seconds
        self._telemetry_store = telemetry_store
        self._pending: list[_PendingEvent] = []
        self._latest_capture_progress_utc: datetime | None = None

    @property
    def pending_count(self) -> int:
        """Return the number of events awaiting their requested post-roll."""

        return len(self._pending)

    def trigger(
        self,
        trigger_type: str = "manual",
        *,
        trigger_utc: datetime | None = None,
        trigger_monotonic_ns: int | None = None,
    ) -> str:
        """Snapshot pre-roll and wait for enough future segments to promote it."""

        occurred_at = trigger_utc or datetime.now(UTC)
        monotonic_ns = (
            trigger_monotonic_ns if trigger_monotonic_ns is not None else time.monotonic_ns()
        )
        selected = list(self._rolling_buffer.select_pre_event(occurred_at, self._pre_seconds))
        event_id = CaptureEvent.new_id()
        self._pending.append(
            _PendingEvent(
                event_id=event_id,
                trigger_type=trigger_type,
                trigger_utc=occurred_at,
                trigger_monotonic_ns=monotonic_ns,
                requested_pre_seconds=self._pre_seconds,
                requested_post_seconds=self._post_seconds,
                segments=selected,
            )
        )
        LOGGER.info(
            "capture event triggered event_id=%s trigger_utc=%s trigger_monotonic_ns=%d "
            "requested_post_end_utc=%s selected_segments=%d",
            event_id,
            occurred_at.isoformat(),
            monotonic_ns,
            (occurred_at + timedelta(seconds=self._post_seconds)).isoformat(),
            len(selected),
        )
        return event_id

    def observe_segment(self, segment: MediaSegment) -> tuple[CaptureEvent, ...]:
        """Add post-roll media and finalize any event whose window is complete."""

        if (
            self._latest_capture_progress_utc is None
            or segment.ended_at_utc > self._latest_capture_progress_utc
        ):
            self._latest_capture_progress_utc = segment.ended_at_utc
        completed: list[CaptureEvent] = []
        for pending in tuple(self._pending):
            overlaps_post_roll = (
                segment.ended_at_utc >= pending.trigger_utc
                and segment.started_at_utc <= pending.required_end_utc
            )
            if overlaps_post_roll:
                if segment.path not in {existing.path for existing in pending.segments}:
                    pending.segments.append(segment)
                    self._rolling_buffer.promote((segment,))
            # Segment boundaries are filesystem-derived approximations and can have
            # small gaps. Capture progress past the requested end is sufficient as
            # long as the event already has real media overlapping its window.
            progressed_through_requested_end = (
                segment.ended_at_utc >= pending.required_end_utc
            )
            if progressed_through_requested_end and not pending.segments:
                LOGGER.warning(
                    "capture event discarded because a source outage left no real media "
                    "in its requested window event_id=%s requested_start_utc=%s "
                    "requested_end_utc=%s",
                    pending.event_id,
                    (
                        pending.trigger_utc
                        - timedelta(seconds=pending.requested_pre_seconds)
                    ).isoformat(),
                    pending.required_end_utc.isoformat(),
                )
                self._pending.remove(pending)
                continue
            ready_to_finalize = progressed_through_requested_end and bool(pending.segments)
            LOGGER.debug(
                "capture event evaluation event_id=%s trigger_utc=%s "
                "trigger_monotonic_ns=%d requested_post_end_utc=%s "
                "segment_start_utc=%s segment_end_utc=%s "
                "latest_capture_progress_utc=%s overlaps_event_window=%s "
                "selected_segments=%d readiness=%s",
                pending.event_id,
                pending.trigger_utc.isoformat(),
                pending.trigger_monotonic_ns,
                pending.required_end_utc.isoformat(),
                segment.started_at_utc.isoformat(),
                segment.ended_at_utc.isoformat(),
                self._latest_capture_progress_utc.isoformat(),
                overlaps_post_roll,
                len(pending.segments),
                ready_to_finalize,
            )
            if ready_to_finalize:
                completed.append(self._finalize(pending))
                self._pending.remove(pending)
        return tuple(completed)

    def abort_pending(self) -> int:
        """Release incomplete events during shutdown rather than claiming success."""

        count = len(self._pending)
        for pending in self._pending:
            self._rolling_buffer.release(tuple(pending.segments))
        self._pending.clear()
        return count

    def _finalize(self, pending: _PendingEvent) -> CaptureEvent:
        if not pending.segments:
            raise RuntimeError("A completed event has no media segments.")
        segments = tuple(
            sorted(pending.segments, key=lambda item: (item.started_at_utc, item.sequence))
        )
        event_dir = self._events_dir / pending.event_id
        media_path = event_dir / "event.mp4"
        manifest_path = event_dir / "manifest.json"
        self._concatenate_segments(tuple(segment.path for segment in segments), media_path)
        media_hash = _sha256_file(media_path)
        created_at = datetime.now(UTC)
        sensors_path: Path | None = None
        sensor_record_count = 0
        if self._telemetry_store is not None:
            sensors_path, sensor_record_count = copy_event_sensor_records(
                self._telemetry_store,
                event_dir,
                segments[0].started_at_utc,
                segments[-1].ended_at_utc,
            )
        event = CaptureEvent(
            event_id=pending.event_id,
            trigger_type=pending.trigger_type,
            trigger_utc=pending.trigger_utc,
            trigger_monotonic_ns=pending.trigger_monotonic_ns,
            requested_pre_seconds=pending.requested_pre_seconds,
            requested_post_seconds=pending.requested_post_seconds,
            actual_start_utc=segments[0].started_at_utc,
            actual_end_utc=segments[-1].ended_at_utc,
            media_path=media_path,
            media_sha256=media_hash,
            manifest_path=manifest_path,
            source=self._source.descriptor,
            created_at_utc=created_at,
            sensors_path=sensors_path,
            sensor_record_count=sensor_record_count,
        )
        manifest_path.write_text(
            json.dumps(_manifest_payload(event), indent=2) + "\n",
            encoding="utf-8",
        )
        LOGGER.info(
            "capture event promoted event_id=%s media_path=%s manifest_path=%s",
            event.event_id,
            event.media_path,
            event.manifest_path,
        )
        return event


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _manifest_payload(event: CaptureEvent) -> dict[str, object]:
    source = event.source
    return {
        "schema_version": 2,
        "event_id": event.event_id,
        "capture_session_id": source.capture_session_id,
        "source_id": source.source_id,
        "source_session_id": source.source_session_id,
        "trigger_type": event.trigger_type,
        "trigger_utc": event.trigger_utc.isoformat(),
        "trigger_monotonic_ns": event.trigger_monotonic_ns,
        "requested_pre_seconds": event.requested_pre_seconds,
        "requested_post_seconds": event.requested_post_seconds,
        "actual_preserved_start_utc": event.actual_start_utc.isoformat(),
        "actual_preserved_end_utc": event.actual_end_utc.isoformat(),
        "media": {"filename": event.media_path.name, "sha256": event.media_sha256},
        "sensors": (
            {
                "filename": event.sensors_path.name,
                "record_count": event.sensor_record_count,
                "selection": "observed_at_utc within actual preserved media window",
            }
            if event.sensors_path is not None
            else None
        ),
        "source": {
            "transport": source.transport,
            "uri": source.uri,
            "video_source": source.video_source,
            "audio_source": source.audio_source,
            "session_started_utc": source.session_started_utc.isoformat(),
            "session_started_monotonic_ns": source.session_started_monotonic_ns,
            "metadata": dict(source.metadata),
        },
        "created_at_utc": event.created_at_utc.isoformat(),
        "timing_note": "Segment boundaries are approximate filesystem-derived capture times.",
    }
