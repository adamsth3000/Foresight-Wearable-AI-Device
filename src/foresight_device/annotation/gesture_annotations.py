"""Durable human ground-truth gesture intervals backed by body-perception evidence."""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, replace
from datetime import UTC, datetime
from enum import StrEnum
from pathlib import Path
from uuid import UUID, uuid4

from foresight_device.body_perception.artifact import BodyArtifact, sha256
from foresight_device.body_perception.models import HandObservation
from foresight_device.perception.event_media import ResolvedEventMedia


class GestureAnnotationError(ValueError):
    """A human gesture annotation is invalid or stale for its source evidence."""


class GestureAnnotationLabel(StrEnum):
    OPEN_HAND = "OPEN_HAND"
    CLOSED_HAND = "CLOSED_HAND"
    POINT = "POINT"
    EXPAND_FINGERS = "EXPAND_FINGERS"
    PINCH_FINGERS = "PINCH_FINGERS"
    TAKE_PICTURE = "TAKE_PICTURE"
    QUOTATION_MARKS = "QUOTATION_MARKS"
    FS_WAKE = "FS_WAKE"
    SNAPSHOT = "SNAPSHOT"
    THUMBS_UP = "THUMBS_UP"
    CUT = "CUT"


class HumanHandedness(StrEnum):
    RIGHT = "RIGHT"
    LEFT = "LEFT"
    BOTH = "BOTH"


@dataclass(frozen=True, slots=True)
class GestureAnnotation:
    annotation_id: str
    gesture_label: GestureAnnotationLabel
    start_timestamp_seconds: float
    end_timestamp_seconds: float
    hand_track_ids: tuple[str, ...]
    created_at_utc: str
    updated_at_utc: str
    source: str = "human"
    status: str = "confirmed"
    # ``None`` represents a legacy record; it is never inferred from MediaPipe evidence.
    human_handedness: HumanHandedness | None = None
    gesture_track_id: str | None = None


@dataclass(frozen=True, slots=True)
class GestureTrack:
    gesture_track_id: str
    start_timestamp_seconds: float
    end_timestamp_seconds: float
    status: str = "confirmed"


@dataclass(frozen=True, slots=True)
class GestureAnnotationArtifact:
    event_id: str
    source_body_filename: str
    source_body_sha256: str
    source_media_filename: str
    source_media_sha256: str
    annotations: tuple[GestureAnnotation, ...]
    gesture_tracks: tuple[GestureTrack, ...] = ()


class GestureAnnotationStore:
    """Atomically persist human gesture labels without changing body or model artifacts."""

    def __init__(
        self,
        path: Path,
        *,
        event_id: str,
        body_path: Path,
        body: BodyArtifact,
        media: ResolvedEventMedia,
        media_duration_seconds: float,
    ) -> None:
        if body.event_id != event_id or media.event_id != event_id:
            raise GestureAnnotationError("annotation sources do not belong to the selected event")
        if media_duration_seconds <= 0:
            raise GestureAnnotationError("media duration must be positive")
        self._path = path
        self._event_id = event_id
        self._body_path = body_path
        self._body = body
        self._media = media
        self._media_duration_seconds = media_duration_seconds
        self._track_ids = {track.hand_track_id for track in body.tracks}

    @property
    def vocabulary(self) -> tuple[GestureAnnotationLabel, ...]:
        return tuple(GestureAnnotationLabel)

    @property
    def event_id(self) -> str:
        return self._event_id

    def load(self) -> tuple[GestureAnnotation, ...]:
        if not self._path.is_file():
            return ()
        artifact = self._decode(self._path)
        self._verify_provenance(artifact)
        return artifact.annotations

    def load_tracks(self) -> tuple[GestureTrack, ...]:
        if not self._path.is_file():
            return ()
        artifact = self._decode(self._path)
        self._verify_provenance(artifact)
        return artifact.gesture_tracks

    def create_track(
        self, *, start_timestamp_seconds: float, end_timestamp_seconds: float
    ) -> GestureTrack:
        track = GestureTrack(str(uuid4()), start_timestamp_seconds, end_timestamp_seconds)
        self._validate_track(track)
        artifact = self._artifact_or_empty()
        self._write(artifact.annotations, (*artifact.gesture_tracks, track))
        return track

    def create(
        self,
        *,
        gesture_label: GestureAnnotationLabel,
        start_timestamp_seconds: float,
        end_timestamp_seconds: float,
        hand_track_ids: tuple[str, ...],
        human_handedness: HumanHandedness,
        gesture_track_id: str | None = None,
    ) -> GestureAnnotation:
        annotation = GestureAnnotation(
            annotation_id=str(uuid4()),
            gesture_label=gesture_label,
            start_timestamp_seconds=start_timestamp_seconds,
            end_timestamp_seconds=end_timestamp_seconds,
            hand_track_ids=hand_track_ids,
            created_at_utc=_utc_now(),
            updated_at_utc=_utc_now(),
            human_handedness=human_handedness,
            gesture_track_id=gesture_track_id,
        )
        self._validate_annotation(annotation)
        artifact = self._artifact_or_empty()
        tracks = artifact.gesture_tracks
        if gesture_track_id is None:
            track = GestureTrack(
                annotation.annotation_id, start_timestamp_seconds, end_timestamp_seconds
            )
            tracks = (*tracks, track)
            annotation = replace(annotation, gesture_track_id=track.gesture_track_id)
        elif gesture_track_id not in {item.gesture_track_id for item in tracks}:
            raise GestureAnnotationError("gesture annotation references an unknown gesture track")
        self._write((*artifact.annotations, annotation), tracks)
        return annotation

    def delete(self, annotation_id: str) -> bool:
        annotations = self.load()
        retained = tuple(item for item in annotations if item.annotation_id != annotation_id)
        if len(retained) == len(annotations):
            return False
        artifact = self._artifact_or_empty()
        self._write(retained, artifact.gesture_tracks)
        return True

    def delete_track(self, gesture_track_id: str) -> bool:
        """Delete a temporal track and its attached semantic sample, if any."""

        artifact = self._artifact_or_empty()
        tracks = tuple(
            item for item in artifact.gesture_tracks if item.gesture_track_id != gesture_track_id
        )
        if len(tracks) == len(artifact.gesture_tracks):
            return False
        annotations = tuple(
            item for item in artifact.annotations if item.gesture_track_id != gesture_track_id
        )
        self._write(annotations, tracks)
        return True

    def update(
        self,
        annotation_id: str,
        *,
        gesture_label: GestureAnnotationLabel,
        start_timestamp_seconds: float,
        end_timestamp_seconds: float,
        hand_track_ids: tuple[str, ...],
        human_handedness: HumanHandedness,
    ) -> GestureAnnotation:
        annotations = self.load()
        selected = next((item for item in annotations if item.annotation_id == annotation_id), None)
        if selected is None:
            raise GestureAnnotationError("gesture annotation does not exist")
        updated = replace(
            selected,
            gesture_label=gesture_label,
            start_timestamp_seconds=start_timestamp_seconds,
            end_timestamp_seconds=end_timestamp_seconds,
            hand_track_ids=hand_track_ids,
            updated_at_utc=_utc_now(),
            human_handedness=human_handedness,
        )
        self._validate_annotation(updated)
        self._write(
            tuple(updated if item.annotation_id == annotation_id else item for item in annotations),
            self.load_tracks(),
        )
        return updated

    def observations_for(self, annotation: GestureAnnotation) -> tuple[HandObservation, ...]:
        """Return only referenced-track observations inside the human-labeled interval."""

        track_observations = {
            observation_id
            for track in self._body.tracks
            if track.hand_track_id in annotation.hand_track_ids
            for observation_id in track.observation_ids
        }
        return tuple(
            observation
            for observation in self._body.observations
            if observation.hand_observation_id in track_observations
            and annotation.start_timestamp_seconds
            <= observation.media_timestamp_seconds
            <= annotation.end_timestamp_seconds
        )

    def _write(
        self, annotations: tuple[GestureAnnotation, ...], tracks: tuple[GestureTrack, ...]
    ) -> None:
        for annotation in annotations:
            self._validate_annotation(annotation)
        for track in tracks:
            self._validate_track(track)
        track_ids = {item.gesture_track_id for item in tracks}
        for annotation in annotations:
            expected_track_id = annotation.gesture_track_id or annotation.annotation_id
            if expected_track_id not in track_ids:
                raise GestureAnnotationError(
                    "gesture annotation references an unknown gesture track"
                )
        payload = {
            "schema_version": 1,
            "event_id": self._event_id,
            "source_body_perception": {
                "filename": self._body_path.name,
                "sha256": sha256(self._body_path),
            },
            "source_media": {
                "filename": self._media.relative_path,
                "sha256": self._media.sha256,
            },
            "annotations": [_serialize(item) for item in annotations],
            "gesture_tracks": [_serialize_track(item) for item in tracks],
        }
        temporary = self._path.with_suffix(self._path.suffix + ".tmp")
        self._path.parent.mkdir(parents=True, exist_ok=True)
        with temporary.open("w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, self._path)

    def _decode(self, path: Path) -> GestureAnnotationArtifact:
        try:
            payload = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise GestureAnnotationError("gesture annotation artifact could not be read") from exc
        if not isinstance(payload, dict) or payload.get("schema_version") != 1:
            raise GestureAnnotationError("gesture annotation artifact schema version is invalid")
        if payload.get("event_id") != self._event_id:
            raise GestureAnnotationError(
                "gesture annotation artifact does not belong to this event"
            )
        body = _mapping(payload.get("source_body_perception"), "source body provenance")
        media = _mapping(payload.get("source_media"), "source media provenance")
        annotations_payload = payload.get("annotations")
        if not isinstance(annotations_payload, list):
            raise GestureAnnotationError("gesture annotation list is invalid")
        annotations = tuple(_deserialize(item) for item in annotations_payload)
        tracks_payload = payload.get("gesture_tracks")
        tracks = (
            tuple(_deserialize_track(item) for item in tracks_payload)
            if isinstance(tracks_payload, list)
            else tuple(
                GestureTrack(
                    item.annotation_id, item.start_timestamp_seconds, item.end_timestamp_seconds
                )
                for item in annotations
            )
        )
        if len({item.annotation_id for item in annotations}) != len(annotations):
            raise GestureAnnotationError("duplicate gesture annotation ID")
        for annotation in annotations:
            self._validate_annotation(annotation)
        for track in tracks:
            self._validate_track(track)
        return GestureAnnotationArtifact(
            event_id=self._event_id,
            source_body_filename=_string(body.get("filename"), "source body filename"),
            source_body_sha256=_sha256(body.get("sha256"), "source body SHA-256"),
            source_media_filename=_string(media.get("filename"), "source media filename"),
            source_media_sha256=_sha256(media.get("sha256"), "source media SHA-256"),
            annotations=annotations,
            gesture_tracks=tracks,
        )

    def _artifact_or_empty(self) -> GestureAnnotationArtifact:
        if self._path.is_file():
            return self._decode(self._path)
        return GestureAnnotationArtifact(
            self._event_id,
            self._body_path.name,
            sha256(self._body_path),
            self._media.relative_path,
            self._media.sha256,
            (),
        )

    def _verify_provenance(self, artifact: GestureAnnotationArtifact) -> None:
        if artifact.source_body_filename != self._body_path.name:
            raise GestureAnnotationError("gesture annotations reference another body artifact")
        if artifact.source_body_sha256 != sha256(self._body_path):
            raise GestureAnnotationError("gesture annotations are stale for body perception")
        if (
            artifact.source_media_filename != self._media.relative_path
            or artifact.source_media_sha256 != self._media.sha256
        ):
            raise GestureAnnotationError("gesture annotations are stale for authoritative media")

    def _validate_annotation(self, annotation: GestureAnnotation) -> None:
        try:
            UUID(annotation.annotation_id)
        except ValueError as exc:
            raise GestureAnnotationError("gesture annotation ID is invalid") from exc
        if not annotation.start_timestamp_seconds < annotation.end_timestamp_seconds:
            raise GestureAnnotationError("gesture annotation start must precede end")
        if (
            annotation.start_timestamp_seconds < 0
            or annotation.end_timestamp_seconds > self._media_duration_seconds
        ):
            raise GestureAnnotationError("gesture annotation is outside media duration")
        if len(annotation.hand_track_ids) != len(set(annotation.hand_track_ids)):
            raise GestureAnnotationError("gesture annotation must reference unique hand tracks")
        if any(track_id not in self._track_ids for track_id in annotation.hand_track_ids):
            raise GestureAnnotationError("gesture annotation references an unknown hand track")
        if annotation.human_handedness is not None and not isinstance(
            annotation.human_handedness, HumanHandedness
        ):
            raise GestureAnnotationError("human handedness is invalid")
        if annotation.source != "human" or annotation.status != "confirmed":
            raise GestureAnnotationError("gesture annotations must be confirmed human labels")

    def _validate_track(self, track: GestureTrack) -> None:
        try:
            UUID(track.gesture_track_id)
        except ValueError as exc:
            raise GestureAnnotationError("gesture track ID is invalid") from exc
        if (
            not 0
            <= track.start_timestamp_seconds
            < track.end_timestamp_seconds
            <= self._media_duration_seconds
            or track.status != "confirmed"
        ):
            raise GestureAnnotationError("gesture track is invalid")


def _serialize(annotation: GestureAnnotation) -> dict[str, object]:
    return {
        "annotation_id": annotation.annotation_id,
        "gesture_label": annotation.gesture_label.value,
        "start_timestamp_seconds": annotation.start_timestamp_seconds,
        "end_timestamp_seconds": annotation.end_timestamp_seconds,
        "hand_track_ids": list(annotation.hand_track_ids),
        "source": annotation.source,
        "status": annotation.status,
        "created_at_utc": annotation.created_at_utc,
        "updated_at_utc": annotation.updated_at_utc,
        **(
            {"human_handedness": annotation.human_handedness.value}
            if annotation.human_handedness is not None
            else {}
        ),
        **(
            {"gesture_track_id": annotation.gesture_track_id} if annotation.gesture_track_id else {}
        ),
    }


def _deserialize(value: object) -> GestureAnnotation:
    payload = _mapping(value, "gesture annotation")
    try:
        label = GestureAnnotationLabel(_string(payload.get("gesture_label"), "gesture label"))
    except ValueError as exc:
        raise GestureAnnotationError("gesture annotation label is invalid") from exc
    tracks = payload.get("hand_track_ids")
    if not isinstance(tracks, list):
        raise GestureAnnotationError("gesture annotation tracks are invalid")
    return GestureAnnotation(
        annotation_id=_string(payload.get("annotation_id"), "annotation ID"),
        gesture_label=label,
        start_timestamp_seconds=_number(payload.get("start_timestamp_seconds"), "start timestamp"),
        end_timestamp_seconds=_number(payload.get("end_timestamp_seconds"), "end timestamp"),
        hand_track_ids=tuple(_string(item, "hand track ID") for item in tracks),
        source=_string(payload.get("source"), "annotation source"),
        status=_string(payload.get("status"), "annotation status"),
        created_at_utc=_string(payload.get("created_at_utc"), "created timestamp"),
        updated_at_utc=_string(payload.get("updated_at_utc"), "updated timestamp"),
        human_handedness=_optional_handedness(payload.get("human_handedness")),
        gesture_track_id=_optional_string(payload.get("gesture_track_id")),
    )


def _mapping(value: object, name: str) -> dict[str, object]:
    if not isinstance(value, dict):
        raise GestureAnnotationError(f"{name} is invalid")
    return value


def _string(value: object, name: str) -> str:
    if not isinstance(value, str) or not value:
        raise GestureAnnotationError(f"{name} is invalid")
    return value


def _number(value: object, name: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise GestureAnnotationError(f"{name} is invalid")
    return float(value)


def _sha256(value: object, name: str) -> str:
    value = _string(value, name)
    if len(value) != 64 or any(character not in "0123456789abcdef" for character in value):
        raise GestureAnnotationError(f"{name} is invalid")
    return value


def _optional_handedness(value: object) -> HumanHandedness | None:
    if value is None:
        return None
    try:
        return HumanHandedness(_string(value, "human handedness"))
    except ValueError as exc:
        raise GestureAnnotationError("human handedness is invalid") from exc


def _optional_string(value: object) -> str | None:
    return None if value is None else _string(value, "gesture track ID")


def _serialize_track(track: GestureTrack) -> dict[str, object]:
    return {
        "gesture_track_id": track.gesture_track_id,
        "start_timestamp_seconds": track.start_timestamp_seconds,
        "end_timestamp_seconds": track.end_timestamp_seconds,
        "status": track.status,
    }


def _deserialize_track(value: object) -> GestureTrack:
    payload = _mapping(value, "gesture track")
    return GestureTrack(
        _string(payload.get("gesture_track_id"), "gesture track ID"),
        _number(payload.get("start_timestamp_seconds"), "gesture track start"),
        _number(payload.get("end_timestamp_seconds"), "gesture track end"),
        _string(payload.get("status"), "gesture track status"),
    )


def _utc_now() -> str:
    return datetime.now(UTC).isoformat()


__all__ = [
    "GestureAnnotation",
    "GestureAnnotationArtifact",
    "GestureAnnotationError",
    "GestureAnnotationLabel",
    "GestureAnnotationStore",
    "HumanHandedness",
]
