"""Validated loading and body-artifact provenance checks for gesture evidence."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from foresight_device.body_perception.artifact import (
    ArtifactValidationError,
    BodyArtifact,
    load_body_artifact,
    sha256,
)
from foresight_device.body_perception.models import SelfAssociationStatus

from .models import GestureEventCandidate


class GestureArtifactProvenanceError(RuntimeError):
    """A gesture artifact references different body-perception evidence."""


@dataclass(frozen=True, slots=True)
class GestureArtifact:
    event_id: str
    source_body_filename: str
    source_body_sha256: str
    backend: str
    gesture_events: tuple[GestureEventCandidate, ...]


def load_gesture_artifact(
    path: Path, *, event_id: str, body_artifact_path: Path | None = None
) -> GestureArtifact:
    """Load event-local gesture candidates and optionally verify body provenance."""
    payload = _read_object(path)
    if payload.get("schema_version") != 1:
        raise ArtifactValidationError("gesture artifact schema version is invalid")
    if _string(payload.get("event_id"), "event_id") != event_id:
        raise ArtifactValidationError("gesture artifact does not belong to this event")
    source = _dict(payload.get("source_body_perception"), "source_body_perception")
    source_filename = _string(source.get("filename"), "source body filename")
    source_sha256 = _string(source.get("sha256"), "source body SHA-256")
    configuration = _dict(payload.get("configuration"), "configuration")
    artifact = GestureArtifact(
        event_id=event_id,
        source_body_filename=source_filename,
        source_body_sha256=source_sha256,
        backend=_string(configuration.get("backend"), "gesture backend"),
        gesture_events=tuple(
            _gesture(_dict(item, "gesture event"), event_id)
            for item in _list(payload.get("gesture_events"), "gesture_events")
        ),
    )
    if len({item.gesture_event_id for item in artifact.gesture_events}) != len(
        artifact.gesture_events
    ):
        raise ArtifactValidationError("duplicate gesture event ID")
    if body_artifact_path is not None:
        body = load_body_artifact(body_artifact_path, event_id=event_id)
        verify_gesture_body_provenance(artifact, body_artifact_path)
        _validate_gesture_evidence(artifact, body)
    return artifact


def verify_gesture_body_provenance(artifact: GestureArtifact, body_artifact_path: Path) -> None:
    if body_artifact_path.name != artifact.source_body_filename:
        raise GestureArtifactProvenanceError("gesture artifact body filename is stale")
    if sha256(body_artifact_path) != artifact.source_body_sha256:
        raise GestureArtifactProvenanceError("gesture artifact body SHA-256 is stale")


def _validate_gesture_evidence(artifact: GestureArtifact, body: BodyArtifact) -> None:
    track_ids = {track.hand_track_id for track in body.tracks}
    observation_ids = {observation.hand_observation_id for observation in body.observations}
    for candidate in artifact.gesture_events:
        if candidate.hand_track_id not in track_ids:
            raise ArtifactValidationError("gesture candidate references an unknown hand track")
        if any(identifier not in observation_ids for identifier in candidate.observation_ids):
            raise ArtifactValidationError("gesture candidate references an unknown observation")


def _gesture(value: dict[str, object], event_id: str) -> GestureEventCandidate:
    if _string(value.get("event_id"), "gesture event_id") != event_id:
        raise ArtifactValidationError("gesture candidate belongs to another event")
    fingertip = _list(value.get("fingertip"), "fingertip")
    if len(fingertip) != 2:
        raise ArtifactValidationError("fingertip must contain two coordinates")
    try:
        return GestureEventCandidate(
            gesture_event_id=_string(value.get("gesture_event_id"), "gesture_event_id"),
            event_id=event_id,
            hand_track_id=_string(value.get("hand_track_id"), "hand_track_id"),
            observation_ids=tuple(
                _string(item, "gesture observation_id")
                for item in _list(value.get("observation_ids"), "observation_ids")
            ),
            start_timestamp_seconds=_number(
                value.get("start_timestamp_seconds"), "start_timestamp_seconds"
            ),
            end_timestamp_seconds=_number(
                value.get("end_timestamp_seconds"), "end_timestamp_seconds"
            ),
            peak_timestamp_seconds=_number(
                value.get("peak_timestamp_seconds"), "peak_timestamp_seconds"
            ),
            gesture_type=_string(value.get("gesture_type"), "gesture_type"),
            gesture_confidence=_number(value.get("gesture_confidence"), "gesture_confidence"),
            motion_confidence=_number(value.get("motion_confidence"), "motion_confidence"),
            self_association_status=_status(value.get("self_association_status")),
            fingertip_x=_optional_number(fingertip[0], "fingertip x"),
            fingertip_y=_optional_number(fingertip[1], "fingertip y"),
        )
    except ValueError as exc:
        raise ArtifactValidationError("gesture candidate fields are invalid") from exc


def _read_object(path: Path) -> dict[str, object]:
    try:
        return _dict(json.loads(path.read_text(encoding="utf-8")), "gesture artifact")
    except (OSError, json.JSONDecodeError) as exc:
        raise ArtifactValidationError("gesture artifact could not be read") from exc


def _dict(value: object, name: str) -> dict[str, object]:
    if not isinstance(value, dict) or not all(isinstance(key, str) for key in value):
        raise ArtifactValidationError(f"{name} must be an object")
    return value


def _list(value: object, name: str) -> list[object]:
    if not isinstance(value, list):
        raise ArtifactValidationError(f"{name} must be a list")
    return value


def _string(value: object, name: str) -> str:
    if not isinstance(value, str) or not value:
        raise ArtifactValidationError(f"{name} must be a non-empty string")
    return value


def _number(value: object, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ArtifactValidationError(f"{name} must be numeric")
    return float(value)


def _optional_number(value: object, name: str) -> float | None:
    return None if value is None else _number(value, name)


def _status(value: object) -> SelfAssociationStatus:
    try:
        return SelfAssociationStatus(_string(value, "self_association_status"))
    except ValueError as exc:
        raise ArtifactValidationError("self_association_status is invalid") from exc
