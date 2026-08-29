"""Read immutable Phase 1D observations for visualization and annotation."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from foresight_device.perception.models import NormalizedBoundingBox, VisualObservation


class PerceptionArtifactError(RuntimeError):
    """Raised when an event perception artifact is unavailable or malformed."""


@dataclass(frozen=True, slots=True)
class LoadedPerception:
    event_id: str
    observations: tuple[VisualObservation, ...]


def load_perception(path: Path) -> LoadedPerception:
    """Load the Phase 1D JSON artifact without altering model evidence."""

    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PerceptionArtifactError(f"perception artifact could not be read: {path}") from exc
    if not isinstance(payload, dict) or not isinstance(payload.get("event_id"), str):
        raise PerceptionArtifactError("perception artifact is missing event_id")
    raw_observations = payload.get("observations")
    if not isinstance(raw_observations, list):
        raise PerceptionArtifactError("perception artifact observations must be a list")
    observations = tuple(_observation_from_dict(item) for item in raw_observations)
    return LoadedPerception(payload["event_id"], observations)


def _observation_from_dict(value: object) -> VisualObservation:
    if not isinstance(value, dict):
        raise PerceptionArtifactError("perception observation must be an object")
    try:
        raw_box = value["bounding_box"]
        if not isinstance(raw_box, list) or len(raw_box) != 4:
            raise ValueError("bounding_box")
        box = NormalizedBoundingBox(*(float(component) for component in raw_box))
        return VisualObservation(
            observation_id=_required_string(value, "observation_id"),
            event_id=_required_string(value, "event_id"),
            source_media_path=_required_string(value, "source_media_path"),
            frame_index=int(value["frame_index"]),
            media_timestamp_seconds=float(value["media_timestamp_seconds"]),
            label=_required_string(value, "label"),
            confidence=float(value["confidence"]),
            bounding_box=box,
            detector_backend=_required_string(value, "detector_backend"),
            detector_model=_required_string(value, "detector_model"),
            prompt=_optional_string(value, "prompt"),
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise PerceptionArtifactError("perception observation has an invalid schema") from exc


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
