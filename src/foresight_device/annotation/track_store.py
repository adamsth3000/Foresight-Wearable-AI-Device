"""Atomic append-only persistence for event-local human track labels."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path
from uuid import uuid4

from .track_models import HumanTrackAnnotation, TrackAnnotationAction

TRACK_ANNOTATION_SCHEMA_VERSION = 1


class TrackAnnotationStoreError(RuntimeError):
    """Raised when a track-label artifact cannot be safely loaded or changed."""


class TrackAnnotationStore:
    """Persist human track labels separately from machine-derived event artifacts."""

    def __init__(self, path: Path, *, event_id: str, track_ids: set[str]) -> None:
        self._path = path
        self._event_id = event_id
        self._track_ids = track_ids

    def load(self) -> tuple[HumanTrackAnnotation, ...]:
        if not self._path.is_file():
            return ()
        try:
            payload = json.loads(self._path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise TrackAnnotationStoreError(
                f"track annotations could not be read: {self._path}"
            ) from exc
        if not isinstance(payload, dict) or payload.get("event_id") != self._event_id:
            raise TrackAnnotationStoreError("track annotations do not belong to this event")
        raw_annotations = payload.get("annotations")
        if not isinstance(raw_annotations, list):
            raise TrackAnnotationStoreError("track annotations must contain an annotations list")
        annotations = tuple(_annotation_from_dict(item) for item in raw_annotations)
        if any(
            annotation.event_id != self._event_id or annotation.track_id not in self._track_ids
            for annotation in annotations
        ):
            raise TrackAnnotationStoreError("track annotations reference another event or track")
        return annotations

    def create_relabel(
        self, *, track_id: str, original_track_label: str, corrected_label: str
    ) -> HumanTrackAnnotation:
        if track_id not in self._track_ids:
            raise TrackAnnotationStoreError(f"annotation references unknown track: {track_id}")
        if not original_track_label or not corrected_label:
            raise TrackAnnotationStoreError("track labels cannot be empty")
        annotation = HumanTrackAnnotation(
            annotation_id=str(uuid4()),
            event_id=self._event_id,
            track_id=track_id,
            action=TrackAnnotationAction.RELABEL_TRACK,
            original_track_label=original_track_label,
            corrected_label=corrected_label,
            created_at_utc=datetime.now(UTC),
        )
        self.save((*self.load(), annotation))
        return annotation

    def save(self, annotations: tuple[HumanTrackAnnotation, ...]) -> None:
        payload = {
            "schema_version": TRACK_ANNOTATION_SCHEMA_VERSION,
            "event_id": self._event_id,
            "annotations": [annotation.as_dict() for annotation in annotations],
        }
        temporary_path = self._path.with_suffix(".json.tmp")
        temporary_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
        temporary_path.replace(self._path)


def latest_track_labels(
    annotations: tuple[HumanTrackAnnotation, ...],
) -> dict[str, str]:
    """Return deterministic latest effective human labels keyed by track identity."""

    latest: dict[str, HumanTrackAnnotation] = {}
    for annotation in annotations:
        previous = latest.get(annotation.track_id)
        if previous is None or annotation.created_at_utc >= previous.created_at_utc:
            latest[annotation.track_id] = annotation
    return {track_id: annotation.corrected_label for track_id, annotation in latest.items()}


def _annotation_from_dict(value: object) -> HumanTrackAnnotation:
    if not isinstance(value, dict):
        raise TrackAnnotationStoreError("track annotation entries must be objects")
    try:
        return HumanTrackAnnotation(
            annotation_id=_required_string(value, "annotation_id"),
            event_id=_required_string(value, "event_id"),
            track_id=_required_string(value, "track_id"),
            action=TrackAnnotationAction(_required_string(value, "action")),
            original_track_label=_required_string(value, "original_track_label"),
            corrected_label=_required_string(value, "corrected_label"),
            created_at_utc=datetime.fromisoformat(_required_string(value, "created_at_utc")),
            provenance=_required_string(value, "provenance"),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise TrackAnnotationStoreError("track annotation entry has an invalid schema") from exc


def _required_string(value: dict[object, object], key: str) -> str:
    item = value.get(key)
    if not isinstance(item, str) or not item:
        raise ValueError(key)
    return item
