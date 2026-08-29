"""Event-local human labels layered over machine-derived entity tracks."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
from enum import StrEnum


class TrackAnnotationAction(StrEnum):
    """Supported human actions on a derived event-local track."""

    RELABEL_TRACK = "relabel_track"


@dataclass(frozen=True, slots=True)
class HumanTrackAnnotation:
    """One immutable human label without changing a perception or tracking artifact."""

    annotation_id: str
    event_id: str
    track_id: str
    action: TrackAnnotationAction
    original_track_label: str
    corrected_label: str
    created_at_utc: datetime
    provenance: str = "human"

    def as_dict(self) -> dict[str, str]:
        return {
            "annotation_id": self.annotation_id,
            "event_id": self.event_id,
            "track_id": self.track_id,
            "action": self.action.value,
            "original_track_label": self.original_track_label,
            "corrected_label": self.corrected_label,
            "created_at_utc": self.created_at_utc.isoformat(),
            "provenance": self.provenance,
        }
