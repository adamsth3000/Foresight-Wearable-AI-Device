"""UI-independent selection, coordinate mapping, and annotation controller."""

from __future__ import annotations

from dataclasses import dataclass, replace

from foresight_device.annotation.gesture_annotations import (
    GestureAnnotation,
    GestureAnnotationLabel,
    GestureAnnotationStore,
    GestureTrack,
    HumanHandedness,
)
from foresight_device.annotation.models import AnnotationAction, HumanAnnotation
from foresight_device.annotation.store import AnnotationStore
from foresight_device.annotation.track_models import HumanTrackAnnotation
from foresight_device.annotation.track_store import TrackAnnotationStore, latest_track_labels
from foresight_device.body_perception.models import HandObservation, HandTrack
from foresight_device.gestures.models import GestureEventCandidate
from foresight_device.gestures.sample_proposals import (
    GestureSampleProposal,
    GestureSampleProposalConfig,
    GestureSampleSuggestion,
    propose_gesture_samples,
)
from foresight_device.perception.models import VisualObservation

from .gesture_timeline import GestureTimeline
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


@dataclass(frozen=True, slots=True)
class HandTrackChoice:
    hand_track_id: str
    media_pipe_handedness: str
    start_timestamp_seconds: float
    end_timestamp_seconds: float
    mean_confidence: float


@dataclass(frozen=True, slots=True)
class GestureSampleDraft:
    start_timestamp_seconds: float | None = None
    end_timestamp_seconds: float | None = None
    hand_track_ids: tuple[str, ...] = ()


@dataclass(frozen=True, slots=True)
class GestureSemanticDraft:
    """Unsaved human semantic choices for one selected confirmed gesture track."""

    gesture_track_id: str
    gesture_label: GestureAnnotationLabel | None = None
    human_handedness: HumanHandedness | None = None
    hand_track_ids: tuple[str, ...] = ()


class EditorController:
    """Coordinates overlay selection and durable actions without depending on widgets."""

    def __init__(
        self,
        observations: tuple[VisualObservation, ...],
        store: AnnotationStore,
        track_ids: dict[str, str] | None = None,
        track_store: TrackAnnotationStore | None = None,
        gesture_timeline: GestureTimeline | None = None,
        gesture_annotation_store: GestureAnnotationStore | None = None,
        hand_observations: tuple[HandObservation, ...] = (),
        hand_tracks: tuple[HandTrack, ...] = (),
        gesture_candidates: tuple[GestureEventCandidate, ...] = (),
        gesture_sample_config: GestureSampleProposalConfig | None = None,
        *,
        association_window_seconds: float = 0.5,
    ) -> None:
        self._observations = observations
        self._store = store
        self._track_ids = track_ids or {}
        self._track_store = track_store
        self._gesture_timeline = gesture_timeline
        self._gesture_annotation_store = gesture_annotation_store
        self._hand_observations = hand_observations
        self._hand_tracks = tuple(sorted(hand_tracks, key=lambda item: item.hand_track_id))
        self._gesture_candidates = gesture_candidates
        self._gesture_sample_config = gesture_sample_config
        self._selected_gesture_annotation_id: str | None = None
        self._selected_gesture_sample_id: str | None = None
        self._gesture_sample_boundaries: dict[str, tuple[float, float]] = {}
        self._manual_gesture_sample_draft: GestureSampleDraft | None = None
        self._semantic_gesture_drafts: dict[str, GestureSemanticDraft] = {}
        self._semantic_confirmation_message: str | None = None
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

    @property
    def gesture_annotations(self) -> tuple[GestureAnnotation, ...]:
        return (
            () if self._gesture_annotation_store is None else self._gesture_annotation_store.load()
        )

    @property
    def gesture_tracks(self) -> tuple[GestureTrack, ...]:
        if self._gesture_annotation_store is None:
            return ()
        loader = getattr(self._gesture_annotation_store, "load_tracks", None)
        return () if loader is None else loader()

    @property
    def selected_gesture_track(self) -> GestureTrack | None:
        selected = self.selected_gesture_sample
        if selected is None or selected.status != "confirmed":
            return None
        return next(
            (item for item in self.gesture_tracks if item.gesture_track_id == selected.proposal_id),
            None,
        )

    @property
    def semantic_gesture_draft(self) -> GestureSemanticDraft | None:
        """Return saved values or unsaved human choices for the selected track only."""

        track = self.selected_gesture_track
        if track is None:
            return None
        if track.gesture_track_id not in self._semantic_gesture_drafts:
            annotation = next(
                (
                    item
                    for item in self.gesture_annotations
                    if (item.gesture_track_id or item.annotation_id) == track.gesture_track_id
                ),
                None,
            )
            overlapping = self.hand_track_choices(
                track.start_timestamp_seconds, track.end_timestamp_seconds
            )
            self._semantic_gesture_drafts[track.gesture_track_id] = GestureSemanticDraft(
                track.gesture_track_id,
                annotation.gesture_label if annotation is not None else None,
                annotation.human_handedness if annotation is not None else None,
                annotation.hand_track_ids
                if annotation is not None
                else ((overlapping[0].hand_track_id,) if len(overlapping) == 1 else ()),
            )
        return self._semantic_gesture_drafts[track.gesture_track_id]

    @property
    def semantic_confirmation_message(self) -> str | None:
        return self._semantic_confirmation_message

    def clear_semantic_confirmation_message(self) -> None:
        self._semantic_confirmation_message = None

    def set_semantic_gesture_label(self, label: GestureAnnotationLabel | None) -> None:
        draft = self.semantic_gesture_draft
        if draft is None:
            raise ValueError("select a confirmed gesture track before selecting a gesture")
        self._semantic_gesture_drafts[draft.gesture_track_id] = replace(draft, gesture_label=label)
        self.clear_semantic_confirmation_message()

    def set_semantic_handedness(self, handedness: HumanHandedness | None) -> None:
        draft = self.semantic_gesture_draft
        if draft is None:
            raise ValueError("select a confirmed gesture track before selecting handedness")
        self._semantic_gesture_drafts[draft.gesture_track_id] = replace(
            draft, human_handedness=handedness
        )
        self.clear_semantic_confirmation_message()

    def set_semantic_hand_tracks(self, hand_track_ids: tuple[str, ...]) -> None:
        draft = self.semantic_gesture_draft
        if draft is None:
            raise ValueError("select a confirmed gesture track before selecting hand tracks")
        if len(hand_track_ids) != len(set(hand_track_ids)):
            raise ValueError("supporting hand tracks must be unique")
        self._semantic_gesture_drafts[draft.gesture_track_id] = replace(
            draft, hand_track_ids=hand_track_ids
        )
        self.clear_semantic_confirmation_message()

    def can_confirm_semantic_sample(self) -> tuple[bool, str | None]:
        """Return the single authoritative sample-confirmation decision."""

        track = self.selected_gesture_track
        draft = self.semantic_gesture_draft
        if track is None or draft is None:
            return (False, "Select a confirmed Gesture Track")
        if draft.gesture_track_id != track.gesture_track_id:
            return (False, "Semantic draft belongs to another Gesture Track")
        if draft.gesture_label is None:
            return (False, "Select a gesture")
        if draft.human_handedness is None:
            return (False, "Select RIGHT, LEFT, or BOTH")
        available = {
            item.hand_track_id
            for item in self.hand_track_choices(
                track.start_timestamp_seconds, track.end_timestamp_seconds
            )
        }
        if draft.hand_track_ids and not set(draft.hand_track_ids).issubset(available):
            return (False, "Selected supporting Hand Track is not available for this Gesture Track")
        return (True, None)

    def semantic_confirmation_reason(self) -> str | None:
        return self.can_confirm_semantic_sample()[1]

    @property
    def selected_gesture_annotation(self) -> GestureAnnotation | None:
        return next(
            (
                item
                for item in self.gesture_annotations
                if item.annotation_id == self._selected_gesture_annotation_id
            ),
            None,
        )

    @property
    def gesture_labels(self) -> tuple[GestureAnnotationLabel, ...]:
        return tuple(GestureAnnotationLabel)

    @property
    def gesture_samples(self) -> tuple[GestureSampleProposal, ...]:
        if self._gesture_annotation_store is None:
            return ()
        kwargs = (
            {} if self._gesture_sample_config is None else {"config": self._gesture_sample_config}
        )
        proposals = propose_gesture_samples(
            self._hand_observations,
            self._hand_tracks,
            candidates=self._gesture_candidates,
            confirmed_annotations=self.gesture_annotations,
            **kwargs,
        )
        adjusted = tuple(
            replace(
                item,
                start_timestamp_seconds=self._gesture_sample_boundaries[item.proposal_id][0],
                end_timestamp_seconds=self._gesture_sample_boundaries[item.proposal_id][1],
            )
            if item.proposal_id in self._gesture_sample_boundaries
            else item
            for item in proposals
        )
        # A confirmed track replaces the matching proposal on the scrubber; it is not a
        # second visual interval for the same physical action.
        confirmed_bounds = {
            (item.start_timestamp_seconds, item.end_timestamp_seconds)
            for item in self.gesture_tracks
        }
        adjusted = tuple(
            item
            for item in adjusted
            if (item.start_timestamp_seconds, item.end_timestamp_seconds) not in confirmed_bounds
        )
        annotations_by_track_id = {
            item.gesture_track_id or item.annotation_id: item for item in self.gesture_annotations
        }
        confirmed_tracks = tuple(
            GestureSampleProposal(
                proposal_id=track.gesture_track_id,
                event_id=self._gesture_annotation_store.event_id,
                start_timestamp_seconds=track.start_timestamp_seconds,
                end_timestamp_seconds=track.end_timestamp_seconds,
                hand_track_ids=(annotation.hand_track_ids if annotation is not None else ()),
                observation_ids=tuple(
                    observation.hand_observation_id
                    for observation in (
                        self._gesture_annotation_store.observations_for(annotation)
                        if annotation is not None
                        else ()
                    )
                ),
                detected_media_pipe_handedness=tuple(
                    sorted(
                        {
                            choice.media_pipe_handedness
                            for choice in self.hand_track_choices(
                                track.start_timestamp_seconds, track.end_timestamp_seconds
                            )
                            if annotation is not None
                            and choice.hand_track_id in annotation.hand_track_ids
                        }
                    )
                ),
                suggested_label=GestureSampleSuggestion.UNKNOWN_GESTURE,
                machine_confidence=None,
                source="human",
                status="confirmed",
                confirmed_annotation_id=(
                    annotation.annotation_id if annotation is not None else None
                ),
            )
            for track in self.gesture_tracks
            for annotation in (annotations_by_track_id.get(track.gesture_track_id),)
        )
        return tuple(
            sorted(
                (*adjusted, *confirmed_tracks),
                key=lambda item: (
                    item.start_timestamp_seconds,
                    item.end_timestamp_seconds,
                    item.proposal_id,
                ),
            )
        )

    def confirm_gesture_track(
        self, start_timestamp_seconds: float, end_timestamp_seconds: float
    ) -> GestureTrack:
        if self._gesture_annotation_store is None:
            raise ValueError("body perception is required for gesture tracks")
        track = self._gesture_annotation_store.create_track(
            start_timestamp_seconds=start_timestamp_seconds,
            end_timestamp_seconds=end_timestamp_seconds,
        )
        self._selected_gesture_sample_id = track.gesture_track_id
        self._selected_gesture_annotation_id = None
        self.clear_semantic_confirmation_message()
        self._semantic_gesture_drafts.pop(track.gesture_track_id, None)
        self._manual_gesture_sample_draft = None
        return track

    @property
    def manual_gesture_sample_draft(self) -> GestureSampleDraft | None:
        return self._manual_gesture_sample_draft

    @property
    def selected_gesture_sample(self) -> GestureSampleProposal | None:
        return next(
            (
                item
                for item in self.gesture_samples
                if item.proposal_id == self._selected_gesture_sample_id
            ),
            None,
        )

    @property
    def gesture_sample_progress(self) -> tuple[int, int]:
        samples = self.gesture_samples
        return (sum(item.status == "confirmed" for item in samples), len(samples))

    def hand_track_choices(
        self, start: float | None = None, end: float | None = None
    ) -> tuple[HandTrackChoice, ...]:
        """Return hand tracks, narrowed to a selected interval when supplied."""

        return tuple(
            HandTrackChoice(
                item.hand_track_id,
                item.handedness.value,
                item.start_timestamp_seconds,
                item.end_timestamp_seconds,
                item.mean_confidence,
            )
            for item in self._hand_tracks
            if start is None
            or end is None
            or (item.start_timestamp_seconds <= end and item.end_timestamp_seconds >= start)
        )

    def save_gesture_annotation(
        self,
        *,
        gesture_label: GestureAnnotationLabel,
        start_timestamp_seconds: float,
        end_timestamp_seconds: float,
        hand_track_ids: tuple[str, ...],
        human_handedness: HumanHandedness,
        gesture_track_id: str | None = None,
    ) -> GestureAnnotation:
        if self._gesture_annotation_store is None:
            raise ValueError("body perception is required for gesture annotations")
        annotation = self._gesture_annotation_store.create(
            gesture_label=gesture_label,
            start_timestamp_seconds=start_timestamp_seconds,
            end_timestamp_seconds=end_timestamp_seconds,
            hand_track_ids=hand_track_ids,
            human_handedness=human_handedness,
            gesture_track_id=gesture_track_id,
        )
        self._selected_gesture_annotation_id = annotation.annotation_id
        self._semantic_gesture_drafts[annotation.gesture_track_id or annotation.annotation_id] = (
            GestureSemanticDraft(
                annotation.gesture_track_id or annotation.annotation_id,
                annotation.gesture_label,
                annotation.human_handedness,
                annotation.hand_track_ids,
            )
        )
        return annotation

    def confirm_gesture_sample(
        self,
        *,
        gesture_label: GestureAnnotationLabel,
        start_timestamp_seconds: float | None = None,
        end_timestamp_seconds: float | None = None,
        hand_track_ids: tuple[str, ...],
        human_handedness: HumanHandedness,
    ) -> GestureAnnotation:
        """Persist a human decision; automatic proposal evidence remains transient."""

        selected = self.selected_gesture_sample
        if self._gesture_annotation_store is None:
            raise ValueError("body perception is required for gesture annotations")
        track = self.selected_gesture_track
        if track is None and selected is not None and selected.status == "proposed":
            # Compatibility for non-UI callers; the editor exposes the explicit track step.
            track = self.confirm_gesture_track(
                start_timestamp_seconds or selected.start_timestamp_seconds,
                end_timestamp_seconds or selected.end_timestamp_seconds,
            )
            selected = self.selected_gesture_sample
        if track is None:
            raise ValueError("select a confirmed gesture track before confirming a sample")
        if selected is not None and selected.confirmed_annotation_id is not None:
            annotation = self._gesture_annotation_store.update(
                selected.confirmed_annotation_id,
                gesture_label=gesture_label,
                start_timestamp_seconds=track.start_timestamp_seconds,
                end_timestamp_seconds=track.end_timestamp_seconds,
                hand_track_ids=hand_track_ids,
                human_handedness=human_handedness,
            )
        else:
            annotation = self.save_gesture_annotation(
                gesture_label=gesture_label,
                start_timestamp_seconds=track.start_timestamp_seconds,
                end_timestamp_seconds=track.end_timestamp_seconds,
                hand_track_ids=hand_track_ids,
                human_handedness=human_handedness,
                gesture_track_id=track.gesture_track_id,
            )
        self._selected_gesture_annotation_id = annotation.annotation_id
        self._semantic_gesture_drafts[track.gesture_track_id] = GestureSemanticDraft(
            track.gesture_track_id,
            annotation.gesture_label,
            annotation.human_handedness,
            annotation.hand_track_ids,
        )
        self._semantic_confirmation_message = (
            f"Sample confirmed: {annotation.gesture_label.value} / "
            f"{annotation.human_handedness.value if annotation.human_handedness else 'UNSET'}"
        )
        if selected is not None:
            self._gesture_sample_boundaries.pop(selected.proposal_id, None)
        return annotation

    def confirm_manual_gesture_sample(
        self,
        *,
        gesture_label: GestureAnnotationLabel,
        hand_track_ids: tuple[str, ...],
        human_handedness: HumanHandedness,
    ) -> GestureAnnotation:
        """Compatibility helper: confirm the draft track, then attach its sample."""

        self.confirm_manual_gesture_track()
        return self.confirm_gesture_sample(
            gesture_label=gesture_label,
            hand_track_ids=hand_track_ids,
            human_handedness=human_handedness,
        )

    def select_gesture_sample(self, proposal_id: str | None) -> GestureSampleProposal | None:
        if proposal_id is None:
            self._selected_gesture_sample_id = None
            self.clear_semantic_confirmation_message()
            return None
        selected = next(
            (item for item in self.gesture_samples if item.proposal_id == proposal_id), None
        )
        if selected is None:
            raise ValueError("gesture sample proposal does not exist")
        self._selected_gesture_sample_id = proposal_id
        self.clear_semantic_confirmation_message()
        if selected.confirmed_annotation_id is not None:
            self._selected_gesture_annotation_id = selected.confirmed_annotation_id
        else:
            self._selected_gesture_annotation_id = None
        return selected

    def set_selected_gesture_sample_boundary(
        self, boundary: str, timestamp_seconds: float
    ) -> GestureSampleProposal:
        """Apply a reviewer boundary override without changing hand-track evidence."""

        selected = self.selected_gesture_sample
        if selected is None:
            raise ValueError("select a gesture sample before changing its boundary")
        if boundary == "start":
            start, end = timestamp_seconds, selected.end_timestamp_seconds
        elif boundary == "end":
            start, end = selected.start_timestamp_seconds, timestamp_seconds
        else:
            raise ValueError("gesture sample boundary must be start or end")
        if not 0 <= start < end:
            raise ValueError("gesture sample start must precede end")
        self._gesture_sample_boundaries[selected.proposal_id] = (start, end)
        updated = self.select_gesture_sample(selected.proposal_id)
        assert updated is not None
        return updated

    def set_manual_gesture_sample_boundary(
        self, boundary: str, timestamp_seconds: float
    ) -> GestureSampleDraft:
        """Start or adjust a human-owned draft when no proposal is being reviewed."""

        draft = self._manual_gesture_sample_draft or GestureSampleDraft()
        if boundary == "start":
            draft = replace(draft, start_timestamp_seconds=timestamp_seconds)
        elif boundary == "end":
            draft = replace(draft, end_timestamp_seconds=timestamp_seconds)
        else:
            raise ValueError("gesture sample boundary must be start or end")
        if draft.start_timestamp_seconds is not None and draft.end_timestamp_seconds is not None:
            if draft.start_timestamp_seconds >= draft.end_timestamp_seconds:
                raise ValueError("gesture sample start must precede end")
            tracks = self.hand_track_choices(
                draft.start_timestamp_seconds, draft.end_timestamp_seconds
            )
            draft = replace(
                draft,
                hand_track_ids=(tracks[0].hand_track_id,) if len(tracks) == 1 else (),
            )
        self._manual_gesture_sample_draft = draft
        self._selected_gesture_sample_id = None
        self._selected_gesture_annotation_id = None
        self.clear_semantic_confirmation_message()
        return draft

    def clear_manual_gesture_sample_draft(self) -> None:
        self._manual_gesture_sample_draft = None

    def confirm_manual_gesture_track(self) -> GestureTrack:
        draft = self._manual_gesture_sample_draft
        if (
            draft is None
            or draft.start_timestamp_seconds is None
            or draft.end_timestamp_seconds is None
        ):
            raise ValueError("set sample start and end before confirming")
        return self.confirm_gesture_track(
            draft.start_timestamp_seconds, draft.end_timestamp_seconds
        )

    def navigate_gesture_sample(self, direction: int) -> GestureSampleProposal | None:
        samples = self.gesture_samples
        if not samples:
            return None
        current = next(
            (
                index
                for index, item in enumerate(samples)
                if item.proposal_id == self._selected_gesture_sample_id
            ),
            -1 if direction >= 0 else 0,
        )
        # Prefer unreviewed work while retaining a deterministic wrap-around fallback.
        for offset in range(1, len(samples) + 1):
            candidate = samples[(current + direction * offset) % len(samples)]
            if candidate.status != "confirmed":
                return self.select_gesture_sample(candidate.proposal_id)
        return self.select_gesture_sample(samples[(current + direction) % len(samples)].proposal_id)

    def delete_selected_gesture_annotation(self) -> bool:
        if self._gesture_annotation_store is None or self._selected_gesture_annotation_id is None:
            return False
        deleted = self._gesture_annotation_store.delete(self._selected_gesture_annotation_id)
        if deleted:
            self._selected_gesture_annotation_id = None
            self.clear_semantic_confirmation_message()
        return deleted

    def delete_selected_gesture_track(self) -> bool:
        track = self.selected_gesture_track
        if self._gesture_annotation_store is None or track is None:
            return False
        deleted = self._gesture_annotation_store.delete_track(track.gesture_track_id)
        if deleted:
            self._selected_gesture_sample_id = None
            self._selected_gesture_annotation_id = None
            self.clear_semantic_confirmation_message()
        return deleted

    def select_gesture_annotation(self, annotation_id: str | None) -> GestureAnnotation | None:
        if annotation_id is None:
            self._selected_gesture_annotation_id = None
            return None
        selected = next(
            (item for item in self.gesture_annotations if item.annotation_id == annotation_id), None
        )
        if selected is None:
            raise ValueError("gesture annotation does not exist")
        self._selected_gesture_annotation_id = annotation_id
        return selected

    def navigate_gesture_annotation(self, direction: int) -> GestureAnnotation | None:
        annotations = tuple(
            sorted(
                self.gesture_annotations,
                key=lambda item: (
                    item.start_timestamp_seconds,
                    item.end_timestamp_seconds,
                    item.annotation_id,
                ),
            )
        )
        if not annotations:
            return None
        current = next(
            (
                index
                for index, item in enumerate(annotations)
                if item.annotation_id == self._selected_gesture_annotation_id
            ),
            0 if direction >= 0 else len(annotations) - 1,
        )
        target = annotations[(current + direction) % len(annotations)]
        self._selected_gesture_annotation_id = target.annotation_id
        return target

    def observations_for_selected_gesture_annotation(self) -> tuple[HandObservation, ...]:
        selected = self.selected_gesture_annotation
        if selected is None or self._gesture_annotation_store is None:
            return ()
        return self._gesture_annotation_store.observations_for(selected)

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

        primitives: list[GestureRingPrimitive | RelationshipArrowPrimitive] = []
        if self._gesture_timeline is not None:
            primitives.extend(self._gesture_timeline.at(timestamp_seconds))
        debug = self._interaction.gesture_debug
        if (
            debug is None
            or abs(debug.media_timestamp_seconds - timestamp_seconds)
            > self._association_window_seconds
        ):
            return tuple(primitives)
        centers = {
            item.observation_id: NormalizedPoint(
                (item.bounding_box.x_min + item.bounding_box.x_max) / 2,
                (item.bounding_box.y_min + item.bounding_box.y_max) / 2,
            )
            for item in self._observations
        }
        primitives.extend(debug.overlay_primitives(centers))
        return tuple(primitives)

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
