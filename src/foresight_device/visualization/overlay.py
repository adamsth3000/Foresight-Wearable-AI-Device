"""Pure overlay timeline models, independent from video decoding and UI."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from foresight_device.annotation.models import AnnotationAction, HumanAnnotation
from foresight_device.perception.models import NormalizedBoundingBox, VisualObservation

from .interaction import InteractionState


class OverlayState(StrEnum):
    DETECTED = "detected"
    MANUALLY_SELECTED = "manually_selected"
    GESTURE_CANDIDATE = "gesture_candidate"
    GESTURE_TARGETED = "gesture_targeted"
    VALIDATED = "validated"
    REJECTED = "rejected"


@dataclass(frozen=True, slots=True)
class PixelBoundingBox:
    x: int
    y: int
    width: int
    height: int


@dataclass(frozen=True, slots=True)
class OverlayItem:
    observation: VisualObservation
    pixel_box: PixelBoundingBox
    state: OverlayState
    display_label: str


def to_pixel_box(box: NormalizedBoundingBox, *, width: int, height: int) -> PixelBoundingBox:
    """Convert canonical normalized coordinates to a bounded pixel-space rectangle."""

    if width <= 0 or height <= 0:
        raise ValueError("video dimensions must be positive")
    left = round(box.x_min * width)
    top = round(box.y_min * height)
    right = round(box.x_max * width)
    bottom = round(box.y_max * height)
    return PixelBoundingBox(left, top, max(0, right - left), max(0, bottom - top))


class OverlayTimeline:
    """Associate sampled observations with nearby media time without persistence or decoding."""

    def __init__(
        self,
        observations: tuple[VisualObservation, ...],
        annotations: tuple[HumanAnnotation, ...] = (),
        *,
        association_window_seconds: float = 0.5,
        interaction_state: InteractionState | None = None,
        track_ids: dict[str, str] | None = None,
        track_labels: dict[str, str] | None = None,
    ) -> None:
        if association_window_seconds < 0:
            raise ValueError("association window must be non-negative")
        self._observations = observations
        self._annotations = _latest_annotations(annotations)
        self._association_window_seconds = association_window_seconds
        self._interaction_state = interaction_state or InteractionState()
        self._track_ids = track_ids or {}
        self._track_labels = track_labels or {}

    def at(self, timestamp_seconds: float, *, width: int, height: int) -> tuple[OverlayItem, ...]:
        """Return a deterministic, time-local overlay set for one decoded frame."""

        visible = [
            observation
            for observation in self._observations
            if abs(observation.media_timestamp_seconds - timestamp_seconds)
            <= self._association_window_seconds
        ]
        return tuple(
            OverlayItem(
                observation=observation,
                pixel_box=to_pixel_box(observation.bounding_box, width=width, height=height),
                state=_state_for(
                    observation,
                    self._annotations.get(observation.observation_id),
                    self._interaction_state,
                    timestamp_seconds,
                    self._association_window_seconds,
                    self._track_ids.get(observation.observation_id),
                ),
                display_label=_label_for(
                    observation,
                    self._annotations.get(observation.observation_id),
                    self._track_ids.get(observation.observation_id),
                    self._track_labels.get(self._track_ids.get(observation.observation_id, "")),
                ),
            )
            for observation in sorted(
                visible,
                key=lambda item: (
                    item.media_timestamp_seconds,
                    item.frame_index,
                    item.observation_id,
                ),
            )
        )

    def all(self, *, width: int, height: int) -> tuple[OverlayItem, ...]:
        """Return every observation in deterministic order for an offline renderer."""

        return self.at_all(self._observations, width=width, height=height)

    def at_all(
        self,
        observations: tuple[VisualObservation, ...],
        *,
        width: int,
        height: int,
    ) -> tuple[OverlayItem, ...]:
        """Apply visual state and pixel conversion without timestamp filtering."""

        return tuple(
            OverlayItem(
                observation=observation,
                pixel_box=to_pixel_box(observation.bounding_box, width=width, height=height),
                state=_state_for(
                    observation,
                    self._annotations.get(observation.observation_id),
                    self._interaction_state,
                    observation.media_timestamp_seconds,
                    self._association_window_seconds,
                    self._track_ids.get(observation.observation_id),
                ),
                display_label=_label_for(
                    observation,
                    self._annotations.get(observation.observation_id),
                    self._track_ids.get(observation.observation_id),
                    self._track_labels.get(self._track_ids.get(observation.observation_id, "")),
                ),
            )
            for observation in sorted(
                observations,
                key=lambda item: (
                    item.media_timestamp_seconds,
                    item.frame_index,
                    item.observation_id,
                ),
            )
        )


def _latest_annotations(annotations: tuple[HumanAnnotation, ...]) -> dict[str, HumanAnnotation]:
    latest: dict[str, HumanAnnotation] = {}
    for annotation in annotations:
        if annotation.observation_id is None:
            continue
        previous = latest.get(annotation.observation_id)
        if previous is None or annotation.created_at_utc >= previous.created_at_utc:
            latest[annotation.observation_id] = annotation
    return latest


def _state_for(
    observation: VisualObservation,
    annotation: HumanAnnotation | None,
    interaction: InteractionState,
    timestamp_seconds: float,
    association_window_seconds: float,
    track_id: str | None,
) -> OverlayState:
    if (
        observation.observation_id == interaction.selected_observation_id
        or (track_id is not None and track_id == interaction.selected_track_id)
    ):
        return OverlayState.MANUALLY_SELECTED
    gesture_is_current = (
        interaction.gesture_debug is not None
        and abs(interaction.gesture_debug.media_timestamp_seconds - timestamp_seconds)
        <= association_window_seconds
    )
    if (
        gesture_is_current
        and observation.observation_id == interaction.gesture_target_observation_id
    ):
        return OverlayState.GESTURE_TARGETED
    if (
        gesture_is_current
        and observation.observation_id in interaction.gesture_candidate_observation_ids
    ):
        return OverlayState.GESTURE_CANDIDATE
    if annotation is None:
        return OverlayState.DETECTED
    if annotation.action == AnnotationAction.REJECT:
        return OverlayState.REJECTED
    if annotation.action == AnnotationAction.VALIDATE:
        return OverlayState.VALIDATED
    if annotation.action == AnnotationAction.SELECTED_OBJECT:
        return OverlayState.MANUALLY_SELECTED
    return OverlayState.DETECTED


def _label_for(
    observation: VisualObservation,
    annotation: HumanAnnotation | None,
    track_id: str | None,
    track_label: str | None,
) -> str:
    label = track_label or observation.label
    if annotation is not None and annotation.action == AnnotationAction.RELABEL:
        label = annotation.corrected_label or label
    return f"{label} · {track_id}" if track_id else label
