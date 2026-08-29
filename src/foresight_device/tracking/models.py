"""Derived persistent-entity track records, separate from model observations."""

from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class EntityTrack:
    track_id: str
    event_id: str
    label: str
    observation_ids: tuple[str, ...]
    start_timestamp_seconds: float
    end_timestamp_seconds: float
    first_observation_id: str
    last_observation_id: str
    observation_count: int
    tracking_backend: str
    mean_observation_confidence: float
    mean_match_quality: float | None
    canonical_label: str | None = None
    entity_type: str | None = None
    parent_group_id: str | None = None
    self_association: bool | None = None
    termination_reason: str | None = None

    def __post_init__(self) -> None:
        if not self.track_id or not self.event_id or not self.label:
            raise ValueError("track identity fields cannot be empty")
        if not self.observation_ids or len(set(self.observation_ids)) != len(self.observation_ids):
            raise ValueError("track observation ids must be non-empty and unique")
        if self.observation_count != len(self.observation_ids):
            raise ValueError("track observation_count must match observation ids")
        if (
            self.first_observation_id != self.observation_ids[0]
            or self.last_observation_id != self.observation_ids[-1]
        ):
            raise ValueError("track first/last observation ids must match ordering")
        if self.start_timestamp_seconds > self.end_timestamp_seconds:
            raise ValueError("track timestamps must be ordered")

    def as_dict(self) -> dict[str, object]:
        return {
            "track_id": self.track_id,
            "event_id": self.event_id,
            "label": self.label,
            "observation_ids": list(self.observation_ids),
            "start_timestamp_seconds": self.start_timestamp_seconds,
            "end_timestamp_seconds": self.end_timestamp_seconds,
            "first_observation_id": self.first_observation_id,
            "last_observation_id": self.last_observation_id,
            "observation_count": self.observation_count,
            "tracking_backend": self.tracking_backend,
            "mean_observation_confidence": self.mean_observation_confidence,
            "mean_match_quality": self.mean_match_quality,
            "canonical_label": self.canonical_label,
            "entity_type": self.entity_type,
            "parent_group_id": self.parent_group_id,
            "self_association": self.self_association,
            "termination_reason": self.termination_reason,
        }


@dataclass(frozen=True, slots=True)
class TrackingResult:
    event_id: str
    tracks: tuple[EntityTrack, ...]
    backend_identity: str
    configuration: dict[str, object]
