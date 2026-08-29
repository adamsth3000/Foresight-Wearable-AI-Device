"""Transient gesture-debug state, intentionally independent from annotations."""

from __future__ import annotations

from dataclasses import dataclass, replace

from foresight_device.identity import SELF_ACTOR, ActorIdentity


@dataclass(frozen=True, slots=True)
class NormalizedPoint:
    """A source-frame point that future renderers can map without UI coupling."""

    x: float
    y: float

    def __post_init__(self) -> None:
        if not 0.0 <= self.x <= 1.0 or not 0.0 <= self.y <= 1.0:
            raise ValueError("normalized points must be within 0.0 and 1.0")


@dataclass(frozen=True, slots=True)
class PointingVector:
    """A normalized-image pointing ray used only for visualization/debugging."""

    x: float
    y: float


@dataclass(frozen=True, slots=True)
class SelfVisualEvidence:
    """Future hand/arm/body/reflection evidence associated with the stable wearer."""

    evidence_id: str
    evidence_kind: str
    observation_id: str | None = None

    def __post_init__(self) -> None:
        if not self.evidence_id or not self.evidence_kind:
            raise ValueError("self evidence identity fields cannot be empty")


@dataclass(frozen=True, slots=True)
class GestureRingPrimitive:
    """A non-obscuring action/gesture overlay distinct from entity boxes."""

    center: NormalizedPoint
    radius_normalized: float
    label: str | None


@dataclass(frozen=True, slots=True)
class RelationshipArrowPrimitive:
    """A future-renderable action-to-entity vector with transient association state."""

    start: NormalizedPoint
    end: NormalizedPoint
    target_observation_id: str
    resolved: bool


@dataclass(frozen=True, slots=True)
class GestureCandidate:
    """One candidate target and the evidence exposed by future association code."""

    observation_id: str
    association_confidence: float | None = None
    vector_intersects_object: bool | None = None
    angular_error_degrees: float | None = None
    spatial_distance_normalized: float | None = None
    target_selection_score: float | None = None
    rejection_reason: str | None = None

    def __post_init__(self) -> None:
        if not self.observation_id:
            raise ValueError("gesture candidate observation_id cannot be empty")
        if (
            self.association_confidence is not None
            and not 0.0 <= self.association_confidence <= 1.0
        ):
            raise ValueError("gesture candidate confidence must be within 0.0 and 1.0")


@dataclass(frozen=True, slots=True)
class GestureAssociationDebug:
    """A future gesture engine's transient, inspectable target-association result.

    No field is persisted by the editor. Coordinates are normalized to the source
    video, allowing a later renderer to draw hands, fingertips, and pointing lines
    using the same viewport mapping as object boxes.
    """

    gesture_id: str
    media_timestamp_seconds: float
    hand_detected: bool
    actor: ActorIdentity = SELF_ACTOR
    self_evidence: tuple[SelfVisualEvidence, ...] = ()
    hand_landmarks: tuple[NormalizedPoint, ...] = ()
    fingertip: NormalizedPoint | None = None
    recognized_gesture: str | None = None
    gesture_confidence: float | None = None
    pointing_origin: NormalizedPoint | None = None
    pointing_vector: PointingVector | None = None
    candidates: tuple[GestureCandidate, ...] = ()
    resolved_target_observation_id: str | None = None
    failure_reason: str | None = None

    def __post_init__(self) -> None:
        if not self.gesture_id:
            raise ValueError("gesture_id cannot be empty")
        if self.media_timestamp_seconds < 0:
            raise ValueError("gesture media timestamp must be non-negative")
        if self.gesture_confidence is not None and not 0.0 <= self.gesture_confidence <= 1.0:
            raise ValueError("gesture confidence must be within 0.0 and 1.0")
        if self.actor != SELF_ACTOR:
            raise ValueError("Phase 1E gesture debug actor must be the stable SELF wearer")
        candidate_ids = [candidate.observation_id for candidate in self.candidates]
        if len(candidate_ids) != len(set(candidate_ids)):
            raise ValueError("gesture candidates must not repeat observation ids")

    @property
    def candidate_observation_ids(self) -> frozenset[str]:
        """Candidate identities, independent of any eventual association algorithm."""

        return frozenset(candidate.observation_id for candidate in self.candidates)

    def overlay_primitives(
        self, target_centers: dict[str, NormalizedPoint]
    ) -> tuple[GestureRingPrimitive | RelationshipArrowPrimitive, ...]:
        """Build future ring/arrow primitives without performing gesture inference."""

        center = self.fingertip or self.pointing_origin
        if center is None:
            return ()
        primitives: list[GestureRingPrimitive | RelationshipArrowPrimitive] = [
            GestureRingPrimitive(center, 0.03, self.recognized_gesture)
        ]
        target_ids = (*self.candidate_observation_ids, self.resolved_target_observation_id)
        for target_id in sorted({item for item in target_ids if item is not None}):
            target_center = target_centers.get(target_id)
            if target_center is not None:
                primitives.append(
                    RelationshipArrowPrimitive(
                        center,
                        target_center,
                        target_id,
                        target_id == self.resolved_target_observation_id,
                    )
                )
        return tuple(primitives)


@dataclass(frozen=True, slots=True)
class InteractionState:
    """Non-persistent editor and gesture state keyed by normalized observation identity."""

    selected_observation_id: str | None = None
    selected_track_id: str | None = None
    gesture_debug: GestureAssociationDebug | None = None

    @property
    def gesture_target_observation_id(self) -> str | None:
        """Resolved gesture target, if a future association engine supplied one."""

        if self.gesture_debug is None:
            return None
        return self.gesture_debug.resolved_target_observation_id

    @property
    def gesture_candidate_observation_ids(self) -> frozenset[str]:
        """Unresolved candidate targets that should be drawn yellow."""

        if self.gesture_debug is None:
            return frozenset()
        return self.gesture_debug.candidate_observation_ids

    def select(self, observation_id: str | None, track_id: str | None = None) -> InteractionState:
        return InteractionState(observation_id, track_id, self.gesture_debug)

    def set_gesture_debug(self, gesture_debug: GestureAssociationDebug | None) -> InteractionState:
        return InteractionState(
            self.selected_observation_id,
            self.selected_track_id,
            gesture_debug,
        )

    def set_gesture_target(self, observation_id: str | None) -> InteractionState:
        """Compatibility helper for a future resolver that only has a target identity."""

        debug = self.gesture_debug or GestureAssociationDebug(
            gesture_id="external-gesture-state",
            media_timestamp_seconds=0.0,
            hand_detected=False,
        )
        return InteractionState(
            self.selected_observation_id,
            self.selected_track_id,
            replace(debug, resolved_target_observation_id=observation_id),
        )
