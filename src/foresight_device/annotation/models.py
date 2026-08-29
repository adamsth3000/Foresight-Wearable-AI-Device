"""Versioned, machine-readable human corrections for perception evidence."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum


class AnnotationAction(StrEnum):
    """Supported and reserved human correction actions."""

    VALIDATE = "validate"
    REJECT = "reject"
    RELABEL = "relabel"
    ADJUST_BOUNDING_BOX = "adjust_bounding_box"
    SEGMENTATION_MASK_CORRECTION = "segmentation_mask_correction"
    MARK_MISSING_OBJECT = "mark_missing_object"
    GESTURE_ANNOTATION = "gesture_annotation"
    HAND_LANDMARK_CORRECTION = "hand_landmark_correction"
    POINT_TO_OBJECT_ASSOCIATION = "point_to_object_association"
    SELECTED_OBJECT = "selected_object"


@dataclass(frozen=True, slots=True)
class HumanAnnotation:
    """One immutable human correction that references, but never replaces, an observation."""

    annotation_id: str
    observation_id: str | None
    event_id: str
    media_timestamp_seconds: float
    action: AnnotationAction
    original_label: str | None
    corrected_label: str | None
    notes: str | None
    created_at_utc: datetime

    @property
    def validated(self) -> bool:
        return self.action == AnnotationAction.VALIDATE

    @property
    def rejected(self) -> bool:
        return self.action == AnnotationAction.REJECT

    def as_dict(self) -> dict[str, object]:
        return {
            "annotation_id": self.annotation_id,
            "observation_id": self.observation_id,
            "event_id": self.event_id,
            "media_timestamp_seconds": self.media_timestamp_seconds,
            "action": self.action.value,
            "original_label": self.original_label,
            "corrected_label": self.corrected_label,
            "validated": self.validated,
            "rejected": self.rejected,
            "notes": self.notes,
            "created_at_utc": self.created_at_utc.isoformat(),
        }
