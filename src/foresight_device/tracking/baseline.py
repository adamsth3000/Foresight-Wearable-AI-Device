"""Deterministic sparse-observation baseline tracking without heavyweight dependencies."""

from __future__ import annotations

from dataclasses import dataclass, field

from foresight_device.perception.models import NormalizedBoundingBox, VisualObservation

from .models import EntityTrack, TrackingResult


@dataclass(frozen=True, slots=True)
class TrackingConfig:
    max_center_distance: float = 0.65
    max_time_gap_seconds: float = 6.5
    iou_weight: float = 0.15

    def __post_init__(self) -> None:
        if self.max_center_distance <= 0 or self.max_time_gap_seconds <= 0 or self.iou_weight < 0:
            raise ValueError("tracking thresholds must be positive")

    def as_dict(self) -> dict[str, object]:
        return {
            "max_center_distance": self.max_center_distance,
            "max_time_gap_seconds": self.max_time_gap_seconds,
            "iou_weight": self.iou_weight,
            "label_compatibility": "exact",
            "matching": "deterministic_greedy_one_to_one",
        }


@dataclass(slots=True)
class _TrackState:
    identifier: int
    observations: list[VisualObservation]
    qualities: list[float] = field(default_factory=list)

    @property
    def last(self) -> VisualObservation:
        return self.observations[-1]


class BaselineTracker:
    """Match exact-label sparse detections by gated displacement and optional IoU quality."""

    backend_identity = "deterministic_sparse_geometry_v1"

    def __init__(self, config: TrackingConfig | None = None) -> None:
        self._config = config or TrackingConfig()

    def track(self, observations: tuple[VisualObservation, ...]) -> TrackingResult:
        if not observations:
            return TrackingResult("", (), self.backend_identity, self._config.as_dict())
        event_ids = {item.event_id for item in observations}
        if len(event_ids) != 1:
            raise ValueError("tracking observations must belong to one event")
        if len({item.observation_id for item in observations}) != len(observations):
            raise ValueError("tracking observations must have unique observation ids")
        ordered = sorted(
            observations,
            key=lambda item: (item.media_timestamp_seconds, item.frame_index, item.observation_id),
        )
        states: list[_TrackState] = []
        next_identifier = 1
        for timestamp, frame_observations in _group_by_timestamp(ordered):
            eligible = [
                state
                for state in states
                if timestamp - state.last.media_timestamp_seconds
                <= self._config.max_time_gap_seconds
            ]
            matches = _match(eligible, frame_observations, self._config)
            matched_observation_ids = set()
            for state, observation, quality in matches:
                state.observations.append(observation)
                state.qualities.append(quality)
                matched_observation_ids.add(observation.observation_id)
            for observation in frame_observations:
                if observation.observation_id not in matched_observation_ids:
                    states.append(_TrackState(next_identifier, [observation]))
                    next_identifier += 1
        tracks = tuple(_to_track(state, self.backend_identity) for state in states)
        return TrackingResult(
            ordered[0].event_id, tracks, self.backend_identity, self._config.as_dict()
        )


def _group_by_timestamp(
    observations: list[VisualObservation],
) -> list[tuple[float, list[VisualObservation]]]:
    groups: list[tuple[float, list[VisualObservation]]] = []
    for observation in observations:
        if not groups or groups[-1][0] != observation.media_timestamp_seconds:
            groups.append((observation.media_timestamp_seconds, [observation]))
        else:
            groups[-1][1].append(observation)
    return groups


def _match(
    states: list[_TrackState], observations: list[VisualObservation], config: TrackingConfig
) -> list[tuple[_TrackState, VisualObservation, float]]:
    candidates: list[tuple[float, int, str, _TrackState, VisualObservation, float]] = []
    for state in states:
        for observation in observations:
            if observation.label != state.last.label:
                continue
            distance = _center_distance(state.last.bounding_box, observation.bounding_box)
            if distance > config.max_center_distance:
                continue
            quality = max(
                0.0,
                1.0
                - distance
                - config.iou_weight
                * (1.0 - _iou(state.last.bounding_box, observation.bounding_box)),
            )
            candidates.append(
                (
                    1.0 - quality,
                    state.identifier,
                    observation.observation_id,
                    state,
                    observation,
                    quality,
                )
            )
    matches: list[tuple[_TrackState, VisualObservation, float]] = []
    used_tracks: set[int] = set()
    used_observations: set[str] = set()
    for _, identifier, observation_id, state, observation, quality in sorted(candidates):
        if identifier not in used_tracks and observation_id not in used_observations:
            matches.append((state, observation, quality))
            used_tracks.add(identifier)
            used_observations.add(observation_id)
    return matches


def _center_distance(left: NormalizedBoundingBox, right: NormalizedBoundingBox) -> float:
    return (
        ((left.x_min + left.x_max - right.x_min - right.x_max) / 2) ** 2
        + ((left.y_min + left.y_max - right.y_min - right.y_max) / 2) ** 2
    ) ** 0.5


def _iou(left: NormalizedBoundingBox, right: NormalizedBoundingBox) -> float:
    overlap_width = max(0.0, min(left.x_max, right.x_max) - max(left.x_min, right.x_min))
    overlap_height = max(0.0, min(left.y_max, right.y_max) - max(left.y_min, right.y_min))
    overlap = overlap_width * overlap_height
    left_area = (left.x_max - left.x_min) * (left.y_max - left.y_min)
    right_area = (right.x_max - right.x_min) * (right.y_max - right.y_min)
    union = left_area + right_area - overlap
    return 0.0 if union <= 0 else overlap / union


def _to_track(state: _TrackState, backend: str) -> EntityTrack:
    observations = state.observations
    return EntityTrack(
        track_id=f"T{state.identifier:03d}",
        event_id=observations[0].event_id,
        label=observations[0].label,
        observation_ids=tuple(item.observation_id for item in observations),
        start_timestamp_seconds=observations[0].media_timestamp_seconds,
        end_timestamp_seconds=observations[-1].media_timestamp_seconds,
        first_observation_id=observations[0].observation_id,
        last_observation_id=observations[-1].observation_id,
        observation_count=len(observations),
        tracking_backend=backend,
        mean_observation_confidence=sum(item.confidence for item in observations)
        / len(observations),
        mean_match_quality=(
            sum(state.qualities) / len(state.qualities) if state.qualities else None
        ),
        termination_reason="end_of_perception",
    )
