"""Validated loading and media-provenance checks for body-perception artifacts."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass
from pathlib import Path
from typing import TypeVar

from foresight_device.perception.event_media import (
    ArtifactMediaProvenance,
    ArtifactMediaStaleError,
    ResolvedEventMedia,
    require_artifact_media_match,
)

from .models import (
    Handedness,
    HandObservation,
    HandTrack,
    NormalizedLandmark,
    SelfAssociation,
    SelfAssociationStatus,
)


class ArtifactValidationError(RuntimeError):
    """A body artifact is structurally invalid or belongs to another event."""


class ArtifactProvenanceError(RuntimeError):
    """An artifact does not describe the local media supplied for verification."""


@dataclass(frozen=True, slots=True)
class BodyArtifact:
    event_id: str
    media_sha256: str
    provider: str
    sampling_interval_seconds: float
    observations: tuple[HandObservation, ...]
    tracks: tuple[HandTrack, ...]
    media_relative_path: str | None = None
    media_source: str | None = None


def load_body_artifact(path: Path, *, event_id: str) -> BodyArtifact:
    """Load one event's artifact without accepting malformed evidence silently."""
    payload = _read_object(path, "body artifact")
    if payload.get("schema_version") != 1:
        raise ArtifactValidationError("body artifact schema version is invalid")
    if _string(payload.get("event_id"), "event_id") != event_id:
        raise ArtifactValidationError("body artifact does not belong to this event")

    configuration = _dict(payload.get("configuration"), "configuration")
    observations = tuple(
        _observation(_dict(item, "hand observation"), event_id)
        for item in _list(payload.get("hand_observations"), "hand_observations")
    )
    observation_ids = {item.hand_observation_id for item in observations}
    if len(observation_ids) != len(observations):
        raise ArtifactValidationError("duplicate hand observation ID")

    tracks = tuple(
        _track(_dict(item, "hand track"), event_id)
        for item in _list(payload.get("hand_tracks"), "hand_tracks")
    )
    if len({item.hand_track_id for item in tracks}) != len(tracks):
        raise ArtifactValidationError("duplicate hand track ID")
    _validate_track_references(tracks, observation_ids)

    source_media = payload.get("source_media")
    provenance = _media_provenance(source_media, payload)
    return BodyArtifact(
        event_id=event_id,
        media_sha256=provenance.sha256,
        provider=_string(payload.get("provider"), "provider"),
        sampling_interval_seconds=_number(
            configuration.get("sampling_interval_seconds"), "sampling_interval_seconds"
        ),
        observations=observations,
        tracks=tracks,
        media_relative_path=provenance.relative_path,
        media_source=provenance.source,
    )


def verify_body_media(artifact: BodyArtifact, media: ResolvedEventMedia | Path) -> None:
    """Reject body evidence if it was generated from different event media."""
    provenance = ArtifactMediaProvenance(
        artifact.media_sha256, artifact.media_relative_path, artifact.media_source
    )
    if isinstance(media, ResolvedEventMedia):
        try:
            require_artifact_media_match(media, provenance)
        except ArtifactMediaStaleError as exc:
            raise ArtifactProvenanceError(str(exc)) from exc
    elif sha256(media) != artifact.media_sha256:
        raise ArtifactProvenanceError("body artifact media SHA-256 is stale")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _observation(value: dict[str, object], event_id: str) -> HandObservation:
    if _string(value.get("event_id"), "observation event_id") != event_id:
        raise ArtifactValidationError("hand observation belongs to another event")
    return HandObservation(
        hand_observation_id=_string(value.get("hand_observation_id"), "hand_observation_id"),
        event_id=event_id,
        frame_index=_integer(value.get("frame_index"), "frame_index"),
        media_timestamp_seconds=_number(
            value.get("media_timestamp_seconds"), "media_timestamp_seconds"
        ),
        provider=_string(value.get("provider"), "provider"),
        confidence=_number(value.get("confidence"), "confidence"),
        handedness=_enum(Handedness, value.get("handedness"), "handedness"),
        handedness_confidence=_optional_number(
            value.get("handedness_confidence"), "handedness_confidence"
        ),
        landmarks=tuple(
            _landmark(_dict(item, "landmark"))
            for item in _list(value.get("landmarks"), "landmarks")
        ),
        self_association=_association(value.get("self_association")),
    )


def _landmark(value: dict[str, object]) -> NormalizedLandmark:
    return NormalizedLandmark(
        name=_string(value.get("name"), "landmark name"),
        x=_number(value.get("x"), "landmark x"),
        y=_number(value.get("y"), "landmark y"),
        z=_optional_number(value.get("z"), "landmark z"),
    )


def _track(value: dict[str, object], event_id: str) -> HandTrack:
    return HandTrack(
        hand_track_id=_string(value.get("hand_track_id"), "hand_track_id"),
        event_id=event_id,
        observation_ids=tuple(
            _string(item, "observation_id")
            for item in _list(value.get("observation_ids"), "observation_ids")
        ),
        start_timestamp_seconds=_number(
            value.get("start_timestamp_seconds"), "start_timestamp_seconds"
        ),
        end_timestamp_seconds=_number(value.get("end_timestamp_seconds"), "end_timestamp_seconds"),
        handedness=_enum(Handedness, value.get("handedness"), "track handedness"),
        self_association=_association(value.get("self_association")),
        mean_confidence=_number(value.get("mean_confidence"), "mean_confidence"),
    )


def _association(value: object) -> SelfAssociation:
    # Early Phase 1G artifacts did not serialize observation-level association.
    if value is None:
        return SelfAssociation()
    association = _dict(value, "self_association")
    return SelfAssociation(
        status=_enum(SelfAssociationStatus, association.get("status"), "self association status"),
        confidence=_optional_number(association.get("confidence"), "self association confidence"),
        reasons=tuple(
            _string(item, "self association reason")
            for item in _list(association.get("reasons"), "self association reasons")
        ),
    )


def _media_provenance(value: object, payload: dict[str, object]) -> ArtifactMediaProvenance:
    legacy_sha256 = _string(payload.get("source_media_sha256"), "source_media_sha256")
    if value is None:
        return ArtifactMediaProvenance(legacy_sha256)
    source = _dict(value, "source_media")
    sha256_value = _string(source.get("sha256"), "source media SHA-256")
    if sha256_value != legacy_sha256:
        raise ArtifactValidationError("source media SHA-256 disagrees with legacy provenance")
    return ArtifactMediaProvenance(
        sha256=sha256_value,
        relative_path=_string(source.get("filename"), "source media filename"),
        source=_string(source.get("source"), "source media source"),
    )


def _validate_track_references(tracks: tuple[HandTrack, ...], observation_ids: set[str]) -> None:
    referenced = [identifier for track in tracks for identifier in track.observation_ids]
    if len(referenced) != len(set(referenced)):
        raise ArtifactValidationError("a hand observation belongs to multiple tracks")
    if any(identifier not in observation_ids for identifier in referenced):
        raise ArtifactValidationError("hand track references an unknown observation")


def _read_object(path: Path, name: str) -> dict[str, object]:
    try:
        return _dict(json.loads(path.read_text(encoding="utf-8")), name)
    except (OSError, json.JSONDecodeError) as exc:
        raise ArtifactValidationError(f"{name} could not be read") from exc


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


def _integer(value: object, name: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int):
        raise ArtifactValidationError(f"{name} must be an integer")
    return value


def _optional_number(value: object, name: str) -> float | None:
    return None if value is None else _number(value, name)


EnumValue = TypeVar("EnumValue", Handedness, SelfAssociationStatus)


def _enum(enum_type: type[EnumValue], value: object, name: str) -> EnumValue:
    try:
        return enum_type(_string(value, name))
    except ValueError as exc:
        raise ArtifactValidationError(f"{name} is invalid") from exc
