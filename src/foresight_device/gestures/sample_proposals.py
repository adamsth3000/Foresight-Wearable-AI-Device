"""Deterministic, review-only gesture sample proposals from hand presence."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum
from uuid import NAMESPACE_URL, uuid5

from foresight_device.annotation.gesture_annotations import GestureAnnotation
from foresight_device.body_perception.models import HandObservation, HandTrack

from .models import GestureEventCandidate


class GestureSampleSuggestion(StrEnum):
    """Machine suggestions are deliberately separate from human training labels."""

    UNKNOWN_GESTURE = "UNKNOWN_GESTURE"
    OPEN_HAND = "OPEN_HAND"
    CLOSED_HAND = "CLOSED_HAND"
    POINT = "POINT"
    PINCH_FINGERS = "PINCH_FINGERS"


@dataclass(frozen=True, slots=True)
class GestureSampleProposalConfig:
    """Centralized segmentation policy for sampled body-perception evidence."""

    maximum_absence_gap_seconds: float = 0.60
    minimum_duration_seconds: float = 0.30
    minimum_observation_count: int = 2
    minimum_observation_confidence: float = 0.0
    boundary_padding_seconds: float = 0.0

    def __post_init__(self) -> None:
        if (
            self.maximum_absence_gap_seconds < 0
            or self.minimum_duration_seconds < 0
            or self.minimum_observation_count < 1
            or not 0 <= self.minimum_observation_confidence <= 1
            or self.boundary_padding_seconds < 0
        ):
            raise ValueError("gesture sample proposal configuration is invalid")


DEFAULT_GESTURE_SAMPLE_PROPOSAL_CONFIG = GestureSampleProposalConfig()


@dataclass(frozen=True, slots=True)
class GestureSampleProposal:
    proposal_id: str
    event_id: str
    start_timestamp_seconds: float
    end_timestamp_seconds: float
    hand_track_ids: tuple[str, ...]
    observation_ids: tuple[str, ...]
    detected_media_pipe_handedness: tuple[str, ...]
    suggested_label: GestureSampleSuggestion
    machine_confidence: float | None
    source: str = "body_perception"
    status: str = "proposed"
    confirmed_annotation_id: str | None = None

    @property
    def observation_count(self) -> int:
        return len(self.observation_ids)


def propose_gesture_samples(
    observations: tuple[HandObservation, ...],
    tracks: tuple[HandTrack, ...],
    *,
    candidates: tuple[GestureEventCandidate, ...] = (),
    confirmed_annotations: tuple[GestureAnnotation, ...] = (),
    config: GestureSampleProposalConfig = DEFAULT_GESTURE_SAMPLE_PROPOSAL_CONFIG,
) -> tuple[GestureSampleProposal, ...]:
    """Group hand-presence observations into non-persistent review candidates."""

    valid = tuple(
        sorted(
            (
                item
                for item in observations
                if item.confidence >= config.minimum_observation_confidence
            ),
            key=lambda item: (item.media_timestamp_seconds, item.hand_observation_id),
        )
    )
    observation_tracks = {
        observation_id: track.hand_track_id
        for track in tracks
        for observation_id in track.observation_ids
    }
    groups: list[list[HandObservation]] = []
    for observation in valid:
        if (
            not groups
            or observation.media_timestamp_seconds - groups[-1][-1].media_timestamp_seconds
            > config.maximum_absence_gap_seconds
        ):
            groups.append([observation])
        else:
            groups[-1].append(observation)

    proposals: list[GestureSampleProposal] = []
    for group in groups:
        start = max(0.0, group[0].media_timestamp_seconds - config.boundary_padding_seconds)
        end = group[-1].media_timestamp_seconds + config.boundary_padding_seconds
        if (
            len(group) < config.minimum_observation_count
            or end - start < config.minimum_duration_seconds
        ):
            continue
        tracks_for_group = tuple(
            sorted(
                {
                    observation_tracks[item.hand_observation_id]
                    for item in group
                    if item.hand_observation_id in observation_tracks
                }
            )
        )
        if not tracks_for_group:
            continue
        annotation = _confirmed_annotation(start, end, tracks_for_group, confirmed_annotations)
        suggestion, confidence = _suggestion(start, end, candidates)
        observations_for_group = tuple(item.hand_observation_id for item in group)
        proposal_id = str(
            uuid5(
                NAMESPACE_URL,
                f"foresight-gesture-sample:{group[0].event_id}:{':'.join(observations_for_group)}",
            )
        )
        proposals.append(
            GestureSampleProposal(
                proposal_id=proposal_id,
                event_id=group[0].event_id,
                start_timestamp_seconds=start,
                end_timestamp_seconds=end,
                hand_track_ids=tracks_for_group,
                observation_ids=observations_for_group,
                detected_media_pipe_handedness=tuple(
                    sorted({item.handedness.value for item in group})
                ),
                suggested_label=suggestion,
                machine_confidence=confidence,
                status="confirmed" if annotation is not None else "proposed",
                confirmed_annotation_id=annotation.annotation_id
                if annotation is not None
                else None,
            )
        )
    return tuple(proposals)


def _confirmed_annotation(
    start: float,
    end: float,
    tracks: tuple[str, ...],
    annotations: tuple[GestureAnnotation, ...],
) -> GestureAnnotation | None:
    # A reviewer may tighten a detected interval, so require substantial overlap rather
    # than its original boundaries. Track compatibility prevents cross-hand association.
    compatible = [
        item
        for item in annotations
        if set(tracks).issubset(item.hand_track_ids)
        and _overlap_seconds(start, end, item.start_timestamp_seconds, item.end_timestamp_seconds)
        >= (end - start) * 0.5
    ]
    return max(
        compatible,
        key=lambda item: (
            _overlap_seconds(start, end, item.start_timestamp_seconds, item.end_timestamp_seconds),
            item.annotation_id,
        ),
        default=None,
    )


def _suggestion(
    start: float, end: float, candidates: tuple[GestureEventCandidate, ...]
) -> tuple[GestureSampleSuggestion, float | None]:
    overlapping = [
        item
        for item in candidates
        if item.end_timestamp_seconds >= start
        and item.start_timestamp_seconds <= end
        and item.gesture_type in _CANDIDATE_LABELS
    ]
    labels = {_CANDIDATE_LABELS[item.gesture_type] for item in overlapping}
    if len(labels) != 1:
        return GestureSampleSuggestion.UNKNOWN_GESTURE, None
    selected = max(
        overlapping,
        key=lambda item: (item.gesture_confidence, item.motion_confidence, item.gesture_event_id),
    )
    return _CANDIDATE_LABELS[selected.gesture_type], selected.gesture_confidence


_CANDIDATE_LABELS = {
    "OPEN_HAND": GestureSampleSuggestion.OPEN_HAND,
    "CLOSED_HAND": GestureSampleSuggestion.CLOSED_HAND,
    "POINT": GestureSampleSuggestion.POINT,
    "PINCH": GestureSampleSuggestion.PINCH_FINGERS,
}


def _overlap_seconds(
    first_start: float, first_end: float, second_start: float, second_end: float
) -> float:
    return max(0.0, min(first_end, second_end) - max(first_start, second_start))


__all__ = [
    "DEFAULT_GESTURE_SAMPLE_PROPOSAL_CONFIG",
    "GestureSampleProposal",
    "GestureSampleProposalConfig",
    "GestureSampleSuggestion",
    "propose_gesture_samples",
]
