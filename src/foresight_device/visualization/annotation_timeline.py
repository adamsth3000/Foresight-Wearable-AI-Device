"""Reusable geometry for persistent semantic timeline intervals."""

from __future__ import annotations

from dataclasses import dataclass

from foresight_device.annotation.gesture_annotations import GestureAnnotation
from foresight_device.gestures.sample_proposals import GestureSampleProposal


@dataclass(frozen=True, slots=True)
class TimelineInterval:
    annotation_id: str
    start_x: float
    end_x: float
    color: str
    label: str
    status: str = "confirmed"


def gesture_annotation_intervals(
    annotations: tuple[GestureAnnotation, ...],
    *,
    duration_seconds: float,
    width: float,
) -> tuple[TimelineInterval, ...]:
    """Map complete human intervals, including overlaps, into orange timeline spans."""

    if duration_seconds <= 0 or width <= 0:
        raise ValueError("timeline duration and width must be positive")
    return tuple(
        TimelineInterval(
            annotation_id=item.annotation_id,
            start_x=width * item.start_timestamp_seconds / duration_seconds,
            end_x=width * item.end_timestamp_seconds / duration_seconds,
            color="orange",
            label=item.gesture_label.value,
            status="confirmed",
        )
        for item in sorted(
            annotations,
            key=lambda item: (
                item.start_timestamp_seconds,
                item.end_timestamp_seconds,
                item.annotation_id,
            ),
        )
    )


def gesture_sample_intervals(
    proposals: tuple[GestureSampleProposal, ...],
    *,
    duration_seconds: float,
    width: float,
) -> tuple[TimelineInterval, ...]:
    """Map review proposals into intervals without changing their persistence state."""

    if duration_seconds <= 0 or width <= 0:
        raise ValueError("timeline duration and width must be positive")
    return tuple(
        TimelineInterval(
            annotation_id=item.proposal_id,
            start_x=width * item.start_timestamp_seconds / duration_seconds,
            end_x=width * item.end_timestamp_seconds / duration_seconds,
            color="orange",
            label=item.suggested_label.value,
            status=item.status,
        )
        for item in proposals
    )


def timestamp_at_x(*, x: float, width: float, duration_seconds: float) -> float:
    """Convert a playback scrubber coordinate to a clamped media timestamp."""

    if duration_seconds <= 0 or width <= 0:
        raise ValueError("timeline duration and width must be positive")
    return min(duration_seconds, max(0.0, duration_seconds * x / width))


def interval_at_x(
    intervals: tuple[TimelineInterval, ...],
    *,
    x: float,
    width: float,
    duration_seconds: float,
) -> TimelineInterval | None:
    """Deterministically hit-test overlapping playback interval spans."""

    if duration_seconds <= 0 or width <= 0:
        raise ValueError("timeline duration and width must be positive")
    timestamp = timestamp_at_x(x=x, width=width, duration_seconds=duration_seconds)
    matches = [
        item
        for item in intervals
        if item.start_x <= width * timestamp / duration_seconds <= item.end_x
    ]
    return (
        min(matches, key=lambda item: (item.end_x - item.start_x, item.annotation_id))
        if matches
        else None
    )
