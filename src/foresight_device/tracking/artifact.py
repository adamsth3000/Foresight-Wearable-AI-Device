"""Validated atomic persistence for derived event tracking artifacts."""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime
from pathlib import Path

from foresight_device.perception.models import VisualObservation

from .models import EntityTrack, TrackingResult


class TrackingArtifactError(RuntimeError):
    """Raised when a derived tracking artifact is malformed or inconsistent."""


def write_tracking_artifact(
    event_dir: Path,
    result: TrackingResult,
    *,
    observations: tuple[VisualObservation, ...],
) -> Path:
    _validate_tracks(result.tracks, observations, result.event_id)
    perception_path = event_dir / "event_perception.json"
    perception = _load_json(perception_path)
    media = perception.get("media")
    if not isinstance(media, dict) or not isinstance(media.get("sha256"), str):
        raise TrackingArtifactError("perception artifact is missing media SHA-256 provenance")
    payload = {
        "schema_version": 1,
        "event_id": result.event_id,
        "source_perception": {
            "filename": perception_path.name,
            "sha256": _sha256(perception_path),
            "media_filename": media.get("filename"),
            "media_sha256": media["sha256"],
        },
        "tracking_backend": result.backend_identity,
        "configuration": result.configuration,
        "created_at_utc": datetime.now(UTC).isoformat(),
        "tracks": [track.as_dict() for track in result.tracks],
    }
    path = event_dir / "event_tracks.json"
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)
    return path


def load_track_index(path: Path, observations: tuple[VisualObservation, ...]) -> dict[str, str]:
    """Load only observation-to-track identity for optional editor labels."""

    payload = _load_json(path)
    tracks = payload.get("tracks")
    if not isinstance(tracks, list):
        raise TrackingArtifactError("tracking artifact tracks must be a list")
    known_ids = {item.observation_id for item in observations}
    index: dict[str, str] = {}
    for raw_track in tracks:
        if not isinstance(raw_track, dict):
            raise TrackingArtifactError("tracking artifact track must be an object")
        track_id = raw_track.get("track_id")
        observation_ids = raw_track.get("observation_ids")
        if not isinstance(track_id, str) or not isinstance(observation_ids, list):
            raise TrackingArtifactError("tracking artifact track identity is invalid")
        for observation_id in observation_ids:
            if (
                not isinstance(observation_id, str)
                or observation_id not in known_ids
                or observation_id in index
            ):
                raise TrackingArtifactError("tracking artifact observation references are invalid")
            index[observation_id] = track_id
    return index


def _validate_tracks(
    tracks: tuple[EntityTrack, ...], observations: tuple[VisualObservation, ...], event_id: str
) -> None:
    known = {item.observation_id for item in observations}
    assigned: set[str] = set()
    for track in tracks:
        if track.event_id != event_id or any(item not in known for item in track.observation_ids):
            raise TrackingArtifactError("track references observations outside the event")
        overlap = assigned.intersection(track.observation_ids)
        if overlap:
            raise TrackingArtifactError("an observation cannot belong to multiple tracks")
        assigned.update(track.observation_ids)


def _load_json(path: Path) -> dict[str, object]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise TrackingArtifactError(f"tracking artifact input could not be read: {path}") from exc
    if not isinstance(payload, dict):
        raise TrackingArtifactError("tracking artifact input must be an object")
    return payload


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
