"""UI-independent selection, coordinate mapping, and annotation controller."""

from __future__ import annotations

from dataclasses import dataclass

from foresight_device.annotation.models import AnnotationAction, HumanAnnotation
from foresight_device.annotation.store import AnnotationStore
from foresight_device.annotation.track_models import HumanTrackAnnotation
from foresight_device.annotation.track_store import TrackAnnotationStore, latest_track_labels
from foresight_device.perception.models import VisualObservation

from .interaction import (
    GestureAssociationDebug,
    GestureRingPrimitive,
    InteractionState,
    NormalizedPoint,
    RelationshipArrowPrimitive,
)
from .label_choices import known_label_choices
from .overlay import OverlayItem, OverlayTimeline


@dataclass(frozen=True, slots=True)
class VideoViewport:
    """Aspect-ratio preserving placement of source video inside a display rectangle."""

    source_width: int
    source_height: int
    display_width: int
    display_height: int

    def source_coordinates(self, x: float, y: float) -> tuple[float, float] | None:
        if min(self.source_width, self.source_height, self.display_width, self.display_height) <= 0:
            raise ValueError("viewport dimensions must be positive")
        scale = min(
            self.display_width / self.source_width, self.display_height / self.source_height
        )
        rendered_width = self.source_width * scale
        rendered_height = self.source_height * scale
        offset_x = (self.display_width - rendered_width) / 2
        offset_y = (self.display_height - rendered_height) / 2
        if (
            not offset_x <= x < offset_x + rendered_width
            or not offset_y <= y < offset_y + rendered_height
        ):
            return None
        return ((x - offset_x) / scale, (y - offset_y) / scale)


class EditorController:
    """Coordinates overlay selection and durable actions without depending on widgets."""

    def __init__(
        self,
        observations: tuple[VisualObservation, ...],
        store: AnnotationStore,
        track_ids: dict[str, str] | None = None,
        track_store: TrackAnnotationStore | None = None,
        *,
        association_window_seconds: float = 0.5,
    ) -> None:
        self._observations = observations
        self._store = store
        self._track_ids = track_ids or {}
        self._track_store = track_store
        self._association_window_seconds = association_window_seconds
        self._interaction = InteractionState()
        self._timeline = self._new_timeline()

    @property
    def interaction(self) -> InteractionState:
        return self._interaction

    @property
    def known_labels(self) -> tuple[str, ...]:
        """Known entity labels for correction convenience; arbitrary labels remain valid."""

        return known_label_choices(self._observations)

    @property
    def selected_observation(self) -> VisualObservation | None:
        """The current transient selection for visible editor controls."""

        return self._selected_observation()

    @property
    def selected_track_id(self) -> str | None:
        """The transient selected derived track, distinct from a source observation."""

        return self._interaction.selected_track_id

    @property
    def selected_display_label(self) -> str | None:
        """Effective label for the selected observation under annotation precedence."""

        selected = self._selected_observation()
        if selected is None:
            return None
        return next(
            (
                item.display_label.rsplit(" · ", 1)[0]
                for item in self._timeline.all(width=1, height=1)
                if item.observation.observation_id == selected.observation_id
            ),
            selected.label,
        )

    def track_for_observation(self, observation_id: str) -> str | None:
        """Return optional derived track identity without changing source observation identity."""

        return self._track_ids.get(observation_id)

    def observations_for_track(self, track_id: str) -> tuple[VisualObservation, ...]:
        """Return source observations that belong to an optional derived track."""

        return tuple(
            item
            for item in self._observations
            if self._track_ids.get(item.observation_id) == track_id
        )

    def overlays_at(
        self, timestamp_seconds: float, *, width: int, height: int
    ) -> tuple[OverlayItem, ...]:
        return self._timeline.at(timestamp_seconds, width=width, height=height)

    def click(
        self,
        display_x: float,
        display_y: float,
        *,
        timestamp_seconds: float,
        viewport: VideoViewport,
    ) -> VisualObservation | None:
        """Select the smallest visible source-space box containing a display click."""

        source_point = viewport.source_coordinates(display_x, display_y)
        if source_point is None:
            self._interaction = self._interaction.select(None)
            self._timeline = self._new_timeline()
            return None
        source_x, source_y = source_point
        matches = [
            item
            for item in self.overlays_at(
                timestamp_seconds, width=viewport.source_width, height=viewport.source_height
            )
            if item.pixel_box.x <= source_x <= item.pixel_box.x + item.pixel_box.width
            and item.pixel_box.y <= source_y <= item.pixel_box.y + item.pixel_box.height
        ]
        if not matches:
            self._interaction = self._interaction.select(None)
            self._timeline = self._new_timeline()
            return None
        selected = min(
            matches,
            key=lambda item: (
                item.pixel_box.width * item.pixel_box.height,
                item.observation.observation_id,
            ),
        ).observation
        self._interaction = self._interaction.select(
            selected.observation_id, self._track_ids.get(selected.observation_id)
        )
        self._timeline = self._new_timeline()
        return selected

    def set_gesture_target(self, observation_id: str | None) -> None:
        """Accept a future gesture-engine target identity without persistence side effects."""

        self._interaction = self._interaction.set_gesture_target(observation_id)
        self._timeline = self._new_timeline()

    def set_gesture_debug(self, gesture_debug: GestureAssociationDebug | None) -> None:
        """Accept a future gesture-debug snapshot without writing an annotation."""

        self._interaction = self._interaction.set_gesture_debug(gesture_debug)
        self._timeline = self._new_timeline()

    def gesture_primitives_at(
        self, timestamp_seconds: float
    ) -> tuple[GestureRingPrimitive | RelationshipArrowPrimitive, ...]:
        """Expose transient action and relationship primitives for a future renderer."""

        debug = self._interaction.gesture_debug
        if (
            debug is None
            or abs(debug.media_timestamp_seconds - timestamp_seconds)
            > self._association_window_seconds
        ):
            return ()
        centers = {
            item.observation_id: NormalizedPoint(
                (item.bounding_box.x_min + item.bounding_box.x_max) / 2,
                (item.bounding_box.y_min + item.bounding_box.y_max) / 2,
            )
            for item in self._observations
        }
        return debug.overlay_primitives(centers)

    def annotate_selected(
        self, action: AnnotationAction, *, corrected_label: str | None = None
    ) -> HumanAnnotation:
        selected = self._selected_observation()
        if selected is None:
            raise ValueError("select an observation before annotating")
        if action == AnnotationAction.RELABEL and not corrected_label:
            raise ValueError("relabel requires a corrected label")
        annotation = self._store.create(
            observation_id=selected.observation_id,
            media_timestamp_seconds=selected.media_timestamp_seconds,
            action=action,
            original_label=selected.label,
            corrected_label=corrected_label,
        )
        self._timeline = self._new_timeline()
        return annotation

    def relabel_selected_track(self, corrected_label: str) -> HumanTrackAnnotation:
        """Persist an event-local human label for the selected derived track only."""

        selected = self._selected_observation()
        track_id = self._interaction.selected_track_id
        if selected is None or track_id is None or self._track_store is None:
            raise ValueError("select a tracked object before relabeling its track")
        if not corrected_label:
            raise ValueError("track relabel requires a corrected label")
        annotation = self._track_store.create_relabel(
            track_id=track_id,
            original_track_label=selected.label,
            corrected_label=corrected_label,
        )
        self._timeline = self._new_timeline()
        return annotation

    def _selected_observation(self) -> VisualObservation | None:
        return self._observation_by_id(self._interaction.selected_observation_id)

    def _observation_by_id(self, observation_id: str | None) -> VisualObservation | None:
        return next(
            (item for item in self._observations if item.observation_id == observation_id), None
        )

    def _new_timeline(self) -> OverlayTimeline:
        return OverlayTimeline(
            self._observations,
            self._store.load(),
            association_window_seconds=self._association_window_seconds,
            interaction_state=self._interaction,
            track_ids=self._track_ids,
            track_labels=(
                latest_track_labels(self._track_store.load())
                if self._track_store is not None
                else {}
            ),
        )
