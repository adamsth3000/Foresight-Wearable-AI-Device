"""Atomic persistence for independent human annotation artifacts."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from .models import AnnotationAction, HumanAnnotation

ANNOTATION_SCHEMA_VERSION = 1


class AnnotationStoreError(RuntimeError):
    """Raised when an annotation artifact cannot be safely loaded or changed."""


class AnnotationStore:
    """Persist append-only corrections without mutating event_perception.json."""

    def __init__(self, path: Path, *, event_id: str, observation_ids: set[str]) -> None:
        self._path = path
        self._event_id = event_id
        self._observation_ids = observation_ids

    def load(self) -> tuple[HumanAnnotation, ...]:
        if not self._path.is_file():
            return ()
        try:
            payload = json.loads(self._path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise AnnotationStoreError(f"annotations could not be read: {self._path}") from exc
        if not isinstance(payload, dict) or payload.get("event_id") != self._event_id:
            raise AnnotationStoreError("annotations do not belong to this event")
        raw_annotations = payload.get("annotations")
        if not isinstance(raw_annotations, list):
            raise AnnotationStoreError("annotations must contain an annotations list")
        return tuple(_annotation_from_dict(item) for item in raw_annotations)

    def create(
        self,
        *,
        observation_id: str | None,
        media_timestamp_seconds: float,
        action: AnnotationAction,
        original_label: str | None = None,
        corrected_label: str | None = None,
        notes: str | None = None,
    ) -> HumanAnnotation:
        if observation_id is not None and observation_id not in self._observation_ids:
            raise AnnotationStoreError(
                f"annotation references unknown observation: {observation_id}"
            )
        if media_timestamp_seconds < 0:
            raise AnnotationStoreError("annotation media timestamp must be non-negative")
        annotation = HumanAnnotation(
            annotation_id=str(uuid4()),
            observation_id=observation_id,
            event_id=self._event_id,
            media_timestamp_seconds=media_timestamp_seconds,
            action=action,
            original_label=original_label,
            corrected_label=corrected_label,
            notes=notes,
            created_at_utc=datetime.now(UTC),
        )
        self.save((*self.load(), annotation))
        return annotation

    def save(self, annotations: tuple[HumanAnnotation, ...]) -> None:
        payload = {
            "schema_version": ANNOTATION_SCHEMA_VERSION,
            "event_id": self._event_id,
            "annotations": [annotation.as_dict() for annotation in annotations],
        }
        temporary_path = self._path.with_suffix(".json.tmp")
        temporary_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        temporary_path.replace(self._path)


def _annotation_from_dict(value: object) -> HumanAnnotation:
    if not isinstance(value, dict):
        raise AnnotationStoreError("annotation entries must be objects")
    try:
        return HumanAnnotation(
            annotation_id=_required_string(value, "annotation_id"),
            observation_id=_optional_string(value, "observation_id"),
            event_id=_required_string(value, "event_id"),
            media_timestamp_seconds=float(value["media_timestamp_seconds"]),
            action=AnnotationAction(_required_string(value, "action")),
            original_label=_optional_string(value, "original_label"),
            corrected_label=_optional_string(value, "corrected_label"),
            notes=_optional_string(value, "notes"),
            created_at_utc=datetime.fromisoformat(_required_string(value, "created_at_utc")),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise AnnotationStoreError("annotation entry has an invalid schema") from exc


def _required_string(value: dict[object, object], key: str) -> str:
    item = value.get(key)
    if not isinstance(item, str) or not item:
        raise ValueError(key)
    return item


def _optional_string(value: dict[object, object], key: str) -> str | None:
    item = value.get(key)
    if item is None or isinstance(item, str):
        return item
    raise ValueError(key)
