"""Manual event promotion over source-neutral rolling media segments."""

from __future__ import annotations

import hashlib
import json
import threading
import time
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from pathlib import Path

from foresight_device.core.logging import get_logger

from .media_source import MediaSource
from .models import CaptureEvent, EventMode, MediaSegment
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
    event_mode: EventMode = EventMode.QUICK
    bounded_end_utc: datetime | None = None

    @property
    def required_end_utc(self) -> datetime:
        if self.event_mode == EventMode.BOUNDED:
            if self.bounded_end_utc is None:
                raise EventStateError("bounded event has not ended")
            return self.bounded_end_utc
        return self.trigger_utc + timedelta(seconds=self.requested_post_seconds)


class EventStateError(RuntimeError):
    """A requested event transition is invalid for the authoritative capture state."""


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
        bounded_finalization_timeout_seconds: float = 15.0,
        telemetry_store: SessionTelemetryStore | None = None,
    ) -> None:
        if pre_seconds < 0 or post_seconds < 0 or bounded_finalization_timeout_seconds <= 0:
            raise ValueError("event durations cannot be negative")
        self._source = source
        self._rolling_buffer = rolling_buffer
        self._events_dir = events_dir
        self._concatenate_segments = concatenate_segments
        self._pre_seconds = pre_seconds
        self._post_seconds = post_seconds
        self._telemetry_store = telemetry_store
        self._bounded_finalization_timeout_seconds = bounded_finalization_timeout_seconds
        self._pending: list[_PendingEvent] = []
        self._bounded: _PendingEvent | None = None
        self._latest_capture_progress_utc: datetime | None = None
        self._bounded_timeout_timer: threading.Timer | None = None

    @property
    def pending_count(self) -> int:
        """Return the number of events awaiting their requested post-roll."""

        return len(self._pending) + int(self._bounded is not None)

    @property
    def bounded_event_id(self) -> str | None:
        return self._bounded.event_id if self._bounded is not None else None

    @property
    def bounded_event_state(self) -> str:
        if self._bounded is None:
            return "idle"
        return (
            "finalizing"
            if self._bounded.bounded_end_utc is not None
            else "recording_bounded_event"
        )

    def trigger(
        self,
        trigger_type: str = "manual",
        *,
        trigger_utc: datetime | None = None,
        trigger_monotonic_ns: int | None = None,
    ) -> str:
        """Snapshot pre-roll and wait for enough future segments to promote it."""

        if self._bounded is not None:
            raise EventStateError("quick event is unavailable while a bounded event is active")
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

    def start_bounded(
        self, *, start_utc: datetime | None = None, start_monotonic_ns: int | None = None
    ) -> str:
        if self._bounded is not None:
            raise EventStateError("a bounded event is already active")
        occurred_at = start_utc or datetime.now(UTC)
        pending = _PendingEvent(
            CaptureEvent.new_id(),
            "manual_bounded",
            occurred_at,
            start_monotonic_ns if start_monotonic_ns is not None else time.monotonic_ns(),
            0.0,
            0.0,
            list(self._rolling_buffer.select_pre_event(occurred_at, 0.0)),
            EventMode.BOUNDED,
        )
        self._bounded = pending
        LOGGER.info(
            "bounded event started event_id=%s start_utc=%s",
            pending.event_id,
            occurred_at.isoformat(),
        )
        return pending.event_id

    def end_bounded(self, *, end_utc: datetime | None = None) -> str:
        pending = self._bounded
        if pending is None:
            raise EventStateError("no bounded event is active")
        if pending.bounded_end_utc is not None:
            raise EventStateError("bounded event is already finalizing")
        pending.bounded_end_utc = end_utc or datetime.now(UTC)
        if pending.bounded_end_utc < pending.trigger_utc:
            raise EventStateError("bounded event cannot end before it starts")
        self._select_bounded_segments(pending)
        self._schedule_bounded_finalization_timeout(pending)
        LOGGER.info(
            "bounded event ending event_id=%s end_utc=%s",
            pending.event_id,
            pending.bounded_end_utc.isoformat(),
        )
        return pending.event_id

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
            progressed_through_requested_end = segment.ended_at_utc >= pending.required_end_utc
            if progressed_through_requested_end and not pending.segments:
                LOGGER.warning(
                    "capture event discarded because a source outage left no real media "
                    "in its requested window event_id=%s requested_start_utc=%s "
                    "requested_end_utc=%s",
                    pending.event_id,
                    (
                        pending.trigger_utc - timedelta(seconds=pending.requested_pre_seconds)
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
        bounded = self._bounded
        if bounded is not None:
            self._append_bounded_segment(bounded, segment)
            if (
                bounded.bounded_end_utc is not None
                and segment.ended_at_utc >= bounded.bounded_end_utc
            ):
                if not bounded.segments:
                    LOGGER.warning(
                        "bounded event discarded without media event_id=%s", bounded.event_id
                    )
                else:
                    completed.append(self._finalize(bounded))
                self._cancel_bounded_finalization_timeout()
                self._bounded = None
        return tuple(completed)

    def abort_pending(self) -> int:
        """Release incomplete events during shutdown rather than claiming success."""

        count = len(self._pending)
        for pending in self._pending:
            self._rolling_buffer.release(tuple(pending.segments))
        if self._bounded is not None:
            self._cancel_bounded_finalization_timeout()
            self._rolling_buffer.release(tuple(self._bounded.segments))
            count += 1
            self._bounded = None
        self._pending.clear()
        return count

    def finalize_bounded_after_source_loss(self, event_id: str) -> CaptureEvent | None:
        """Resolve a finalizing bounded event after its source cannot close another segment.

        Existing closed segments are genuine media and may be promoted. No media is invented when
        the source disappears before a usable segment exists.
        """

        pending = self._bounded
        if (
            pending is None
            or pending.event_id != event_id
            or pending.bounded_end_utc is None
        ):
            return None
        self._cancel_bounded_finalization_timeout()
        self._bounded = None
        if not pending.segments:
            LOGGER.error(
                "bounded event failed after source loss without media "
                "event_id=%s requested_end_utc=%s",
                pending.event_id,
                pending.required_end_utc.isoformat(),
            )
            return None
        event = self._finalize(pending, source_terminated_early=True)
        LOGGER.warning(
            "bounded event promoted after source loss event_id=%s requested_end_utc=%s "
            "actual_end_utc=%s",
            event.event_id,
            pending.required_end_utc.isoformat(),
            event.actual_end_utc.isoformat(),
        )
        return event

    def _schedule_bounded_finalization_timeout(self, pending: _PendingEvent) -> None:
        self._cancel_bounded_finalization_timeout()
        timer = threading.Timer(
            self._bounded_finalization_timeout_seconds,
            self._on_bounded_finalization_timeout,
            args=(pending.event_id,),
        )
        timer.daemon = True
        self._bounded_timeout_timer = timer
        timer.start()

    def _cancel_bounded_finalization_timeout(self) -> None:
        if self._bounded_timeout_timer is not None:
            self._bounded_timeout_timer.cancel()
            self._bounded_timeout_timer = None

    def _on_bounded_finalization_timeout(self, event_id: str) -> None:
        event = self.finalize_bounded_after_source_loss(event_id)
        if event is None:
            LOGGER.error(
                "bounded event finalization timed out without promotable media event_id=%s",
                event_id,
            )

    def _select_bounded_segments(self, pending: _PendingEvent) -> None:
        for segment in self._rolling_buffer.segments:
            self._append_bounded_segment(pending, segment)

    def _append_bounded_segment(self, pending: _PendingEvent, segment: MediaSegment) -> None:
        end = pending.bounded_end_utc
        if segment.ended_at_utc < pending.trigger_utc or (
            end is not None and segment.started_at_utc > end
        ):
            return
        if segment.path not in {existing.path for existing in pending.segments}:
            pending.segments.append(segment)
            self._rolling_buffer.promote((segment,))

    def _finalize(
        self, pending: _PendingEvent, *, source_terminated_early: bool = False
    ) -> CaptureEvent:
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
            event_mode=pending.event_mode,
            bounded_start_utc=(
                pending.trigger_utc if pending.event_mode == EventMode.BOUNDED else None
            ),
            bounded_end_utc=pending.bounded_end_utc,
            source_terminated_early=source_terminated_early,
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
        "schema_version": 3,
        "event_id": event.event_id,
        "capture_session_id": source.capture_session_id,
        "source_id": source.source_id,
        "source_session_id": source.source_session_id,
        "trigger_type": event.trigger_type,
        "event_mode": event.event_mode.value,
        "trigger_utc": event.trigger_utc.isoformat(),
        "trigger_monotonic_ns": event.trigger_monotonic_ns,
        "requested_pre_seconds": event.requested_pre_seconds,
        "requested_post_seconds": event.requested_post_seconds,
        "actual_preserved_start_utc": event.actual_start_utc.isoformat(),
        "actual_preserved_end_utc": event.actual_end_utc.isoformat(),
        "bounded_event": (
            {
                "start_utc": event.bounded_start_utc.isoformat(),
                "end_utc": event.bounded_end_utc.isoformat(),
                "duration_seconds": (
                    event.bounded_end_utc - event.bounded_start_utc
                ).total_seconds(),
                "source_terminated_early": event.source_terminated_early,
            }
            if event.event_mode == EventMode.BOUNDED
            and event.bounded_start_utc is not None
            and event.bounded_end_utc is not None
            else None
        ),
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
