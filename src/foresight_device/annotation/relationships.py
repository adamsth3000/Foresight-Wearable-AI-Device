"""Machine-readable, append-only human annotations of actor/action/target relationships."""

from __future__ import annotations

import json
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from foresight_device.identity import SELF_ACTOR, ActorIdentity, ActorRole


class RelationshipAnnotationError(RuntimeError):
    """Raised when relationship evidence cannot be safely persisted or decoded."""


@dataclass(frozen=True, slots=True)
class RelationshipTarget:
    """One or more observed entities, or a future semantic entity/region target."""

    observation_ids: tuple[str, ...] = ()
    semantic_entity_id: str | None = None
    region_label: str | None = None

    def __post_init__(self) -> None:
        if (
            not self.observation_ids
            and self.semantic_entity_id is None
            and self.region_label is None
        ):
            raise ValueError("relationship target requires an observation, entity, or region")
        if any(not item for item in self.observation_ids):
            raise ValueError("relationship target observation ids cannot be empty")


@dataclass(frozen=True, slots=True)
class RelationshipAnnotation:
    """A human-designated relationship that never mutates perception evidence."""

    relationship_annotation_id: str
    event_id: str
    actor: ActorIdentity
    action: str
    relationship: str
    source_evidence_observation_ids: tuple[str, ...]
    target: RelationshipTarget
    media_timestamp_seconds: float
    end_media_timestamp_seconds: float | None
    notes: str | None
    human_validated: bool
    created_at_utc: datetime

    def __post_init__(self) -> None:
        if not self.relationship_annotation_id or not self.event_id:
            raise ValueError("relationship annotation identity fields cannot be empty")
        if not self.action.strip() or not self.relationship.strip():
            raise ValueError("relationship action and relationship cannot be empty")
        if self.media_timestamp_seconds < 0:
            raise ValueError("relationship timestamp must be non-negative")
        if (
            self.end_media_timestamp_seconds is not None
            and self.end_media_timestamp_seconds < self.media_timestamp_seconds
        ):
            raise ValueError("relationship end timestamp cannot precede its start")

    def as_dict(self) -> dict[str, object]:
        return {
            "relationship_annotation_id": self.relationship_annotation_id,
            "event_id": self.event_id,
            "actor_id": self.actor.actor_id,
            "actor_role": self.actor.actor_role.value,
            "action": self.action,
            "relationship": self.relationship,
            "source_evidence_observation_ids": list(self.source_evidence_observation_ids),
            "target": {
                "observation_ids": list(self.target.observation_ids),
                "semantic_entity_id": self.target.semantic_entity_id,
                "region_label": self.target.region_label,
            },
            "media_timestamp_seconds": self.media_timestamp_seconds,
            "end_media_timestamp_seconds": self.end_media_timestamp_seconds,
            "notes": self.notes,
            "human_validated": self.human_validated,
            "created_at_utc": self.created_at_utc.isoformat(),
        }


class RelationshipAnnotationStore:
    """Append-only event-local relationship persistence with observation integrity checks."""

    def __init__(self, path: Path, *, event_id: str, observation_ids: set[str]) -> None:
        self._path = path
        self._event_id = event_id
        self._observation_ids = observation_ids

    def load(self) -> tuple[RelationshipAnnotation, ...]:
        if not self._path.is_file():
            return ()
        try:
            payload = json.loads(self._path.read_text(encoding="utf-8"))
            values = payload["relationships"]
        except (OSError, json.JSONDecodeError, KeyError, TypeError) as exc:
            raise RelationshipAnnotationError("relationship annotations could not be read") from exc
        if payload.get("event_id") != self._event_id or not isinstance(values, list):
            raise RelationshipAnnotationError(
                "relationship annotations do not belong to this event"
            )
        return tuple(_from_dict(item) for item in values)

    def create(
        self,
        *,
        action: str,
        relationship: str,
        source_evidence_observation_ids: tuple[str, ...],
        target: RelationshipTarget,
        media_timestamp_seconds: float,
        actor: ActorIdentity = SELF_ACTOR,
        notes: str | None = None,
        human_validated: bool = True,
    ) -> RelationshipAnnotation:
        referenced = (*source_evidence_observation_ids, *target.observation_ids)
        unknown = [item for item in referenced if item not in self._observation_ids]
        if unknown:
            raise RelationshipAnnotationError(
                f"relationship references unknown observation: {unknown[0]}"
            )
        annotation = RelationshipAnnotation(
            relationship_annotation_id=str(uuid4()),
            event_id=self._event_id,
            actor=actor,
            action=action,
            relationship=relationship,
            source_evidence_observation_ids=source_evidence_observation_ids,
            target=target,
            media_timestamp_seconds=media_timestamp_seconds,
            end_media_timestamp_seconds=None,
            notes=notes,
            human_validated=human_validated,
            created_at_utc=datetime.now(UTC),
        )
        self.save((*self.load(), annotation))
        return annotation

    def save(self, annotations: tuple[RelationshipAnnotation, ...]) -> None:
        payload = {
            "schema_version": 1,
            "event_id": self._event_id,
            "relationships": [item.as_dict() for item in annotations],
        }
        temporary_path = self._path.with_suffix(".json.tmp")
        temporary_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        temporary_path.replace(self._path)


def _from_dict(value: object) -> RelationshipAnnotation:
    try:
        if not isinstance(value, dict) or not isinstance(value["target"], dict):
            raise ValueError("relationship entry")
        target = value["target"]
        observation_ids = target.get("observation_ids")
        source_ids = value["source_evidence_observation_ids"]
        if not isinstance(observation_ids, list) or not isinstance(source_ids, list):
            raise ValueError("relationship observation ids")
        human_validated = value["human_validated"]
        if not isinstance(human_validated, bool):
            raise ValueError("human_validated")
        return RelationshipAnnotation(
            relationship_annotation_id=_string(value, "relationship_annotation_id"),
            event_id=_string(value, "event_id"),
            actor=ActorIdentity(
                _string(value, "actor_id"), ActorRole(_string(value, "actor_role"))
            ),
            action=_string(value, "action"),
            relationship=_string(value, "relationship"),
            source_evidence_observation_ids=tuple(_as_strings(source_ids)),
            target=RelationshipTarget(
                tuple(_as_strings(observation_ids)),
                _optional(target, "semantic_entity_id"),
                _optional(target, "region_label"),
            ),
            media_timestamp_seconds=float(value["media_timestamp_seconds"]),
            end_media_timestamp_seconds=_as_float_or_none(value.get("end_media_timestamp_seconds")),
            notes=_optional(value, "notes"),
            human_validated=human_validated,
            created_at_utc=datetime.fromisoformat(_string(value, "created_at_utc")),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise RelationshipAnnotationError("relationship annotation has an invalid schema") from exc


def _string(value: dict[object, object], key: str) -> str:
    item = value.get(key)
    if not isinstance(item, str) or not item:
        raise ValueError(key)
    return item


def _optional(value: dict[object, object], key: str) -> str | None:
    item = value.get(key)
    if item is None or isinstance(item, str):
        return item
    raise ValueError(key)


def _as_strings(value: list[object]) -> tuple[str, ...]:
    if not all(isinstance(item, str) and item for item in value):
        raise ValueError("string list")
    return tuple(item for item in value if isinstance(item, str))


def _as_float_or_none(value: object) -> float | None:
    if value is None:
        return None
    if isinstance(value, (str, int, float)):
        return float(value)
    raise ValueError("float")
