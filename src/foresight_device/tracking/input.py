"""Read immutable perception artifacts without depending on visualization adapters."""

from __future__ import annotations

import json
from dataclasses import dataclass
from pathlib import Path

from foresight_device.perception.models import NormalizedBoundingBox, VisualObservation


class TrackingInputError(RuntimeError):
    """Raised when an event perception artifact cannot safely feed tracking."""


@dataclass(frozen=True, slots=True)
class TrackingInput:
    event_id: str
    observations: tuple[VisualObservation, ...]


def load_tracking_input(path: Path) -> TrackingInput:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise TrackingInputError(f"perception artifact could not be read: {path}") from exc
    if not isinstance(payload, dict) or not isinstance(payload.get("event_id"), str):
        raise TrackingInputError("perception artifact is missing event_id")
    raw_observations = payload.get("observations")
    if not isinstance(raw_observations, list):
        raise TrackingInputError("perception artifact observations must be a list")
    try:
        observations = tuple(_observation(item) for item in raw_observations)
    except (KeyError, TypeError, ValueError) as exc:
        raise TrackingInputError("perception artifact observation has an invalid schema") from exc
    return TrackingInput(payload["event_id"], observations)


def _observation(value: object) -> VisualObservation:
    if not isinstance(value, dict):
        raise ValueError("observation")
    box = value["bounding_box"]
    if not isinstance(box, list) or len(box) != 4:
        raise ValueError("bounding_box")
    return VisualObservation(
        observation_id=_string(value, "observation_id"),
        event_id=_string(value, "event_id"),
        source_media_path=_string(value, "source_media_path"),
        frame_index=int(value["frame_index"]),
        media_timestamp_seconds=float(value["media_timestamp_seconds"]),
        label=_string(value, "label"),
        confidence=float(value["confidence"]),
        bounding_box=NormalizedBoundingBox(*(float(item) for item in box)),
        detector_backend=_string(value, "detector_backend"),
        detector_model=_string(value, "detector_model"),
        prompt=_optional(value, "prompt"),
    )


def _string(value: dict[object, object], key: str) -> str:
    item = value[key]
    if not isinstance(item, str) or not item:
        raise ValueError(key)
    return item


def _optional(value: dict[object, object], key: str) -> str | None:
    item = value.get(key)
    if item is None or isinstance(item, str):
        return item
    raise ValueError(key)
