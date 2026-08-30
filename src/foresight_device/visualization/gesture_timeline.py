"""Pure, time-bounded gesture-ring planning from validated Phase 1G evidence."""

from __future__ import annotations

from foresight_device.body_perception.artifact import BodyArtifact
from foresight_device.body_perception.models import HandObservation
from foresight_device.gestures.artifact import GestureArtifact
from foresight_device.gestures.models import GestureEventCandidate

from .interaction import GestureRingPrimitive, NormalizedPoint


class GestureTimeline:
    """Maps typed gesture evidence to neutral ring primitives at event-media time.

    A ring uses the candidate observation nearest the current playback timestamp.
    Its index fingertip is preferred; otherwise the center and radius are derived
    deterministically from the recorded hand-landmark region.
    """

    def __init__(
        self,
        observations: tuple[HandObservation, ...],
        candidates: tuple[GestureEventCandidate, ...],
    ) -> None:
        self._observations = {item.hand_observation_id: item for item in observations}
        self._candidates = tuple(sorted(candidates, key=lambda item: item.gesture_event_id))

    @classmethod
    def from_artifacts(cls, body: BodyArtifact, gestures: GestureArtifact) -> GestureTimeline:
        if body.event_id != gestures.event_id:
            raise ValueError("body and gesture artifacts belong to different events")
        return cls(body.observations, gestures.gesture_events)

    def at(self, timestamp_seconds: float) -> tuple[GestureRingPrimitive, ...]:
        """Return rings only for candidates active at ``timestamp_seconds`` inclusively."""
        return tuple(
            self._primitive(candidate, timestamp_seconds)
            for candidate in self._candidates
            if candidate.start_timestamp_seconds
            <= timestamp_seconds
            <= candidate.end_timestamp_seconds
        )

    def all(self) -> tuple[GestureRingPrimitive, ...]:
        """Return one representative primitive per candidate, centered at its peak evidence."""
        return tuple(
            self._primitive(candidate, candidate.peak_timestamp_seconds)
            for candidate in self._candidates
        )

    def _primitive(
        self, candidate: GestureEventCandidate, timestamp_seconds: float
    ) -> GestureRingPrimitive:
        observation = min(
            (self._observations[identifier] for identifier in candidate.observation_ids),
            key=lambda item: (
                abs(item.media_timestamp_seconds - timestamp_seconds),
                item.media_timestamp_seconds,
                item.hand_observation_id,
            ),
        )
        center, radius = _ring_geometry(observation)
        return GestureRingPrimitive(
            center=center,
            radius_normalized=radius,
            label=candidate.gesture_type,
            gesture_event_id=candidate.gesture_event_id,
            gesture_type=candidate.gesture_type,
            start_timestamp_seconds=candidate.start_timestamp_seconds,
            end_timestamp_seconds=candidate.end_timestamp_seconds,
            peak_timestamp_seconds=candidate.peak_timestamp_seconds,
            hand_track_id=candidate.hand_track_id,
            source_hand_observation_ids=candidate.observation_ids,
        )


def _ring_geometry(observation: HandObservation) -> tuple[NormalizedPoint, float]:
    fingertip = observation.fingertip
    if fingertip is not None:
        return NormalizedPoint(fingertip.x, fingertip.y), 0.03
    points = observation.landmarks
    left, right = min(item.x for item in points), max(item.x for item in points)
    top, bottom = min(item.y for item in points), max(item.y for item in points)
    center = NormalizedPoint((left + right) / 2, (top + bottom) / 2)
    # A bounded hand-region radius stays legible without obscuring the video.
    return center, min(0.12, max(0.03, max(right - left, bottom - top) / 2 + 0.02))
