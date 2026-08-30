"""Source-neutral records for offline event perception."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True, slots=True)
class NormalizedBoundingBox:
    """A clamped [x_min, y_min, x_max, y_max] box relative to a decoded frame."""

    x_min: float
    y_min: float
    x_max: float
    y_max: float

    def __post_init__(self) -> None:
        values = (self.x_min, self.y_min, self.x_max, self.y_max)
        if any(value < 0.0 or value > 1.0 for value in values):
            raise ValueError("normalized bounding-box coordinates must be within 0.0 and 1.0")
        if self.x_min > self.x_max or self.y_min > self.y_max:
            raise ValueError("normalized bounding-box minimums cannot exceed maximums")

    @classmethod
    def from_pixel_coordinates(
        cls,
        x_min: float,
        y_min: float,
        x_max: float,
        y_max: float,
        *,
        frame_width: int,
        frame_height: int,
    ) -> NormalizedBoundingBox:
        """Clamp a pixel-space detector box into the canonical representation."""

        if frame_width <= 0 or frame_height <= 0:
            raise ValueError("frame dimensions must be positive")
        left, right = sorted(
            (min(float(frame_width), max(0.0, x_min)), min(float(frame_width), max(0.0, x_max)))
        )
        top, bottom = sorted(
            (min(float(frame_height), max(0.0, y_min)), min(float(frame_height), max(0.0, y_max)))
        )
        return cls(
            x_min=left / frame_width,
            y_min=top / frame_height,
            x_max=right / frame_width,
            y_max=bottom / frame_height,
        )

    def as_list(self) -> list[float]:
        """Serialize the documented canonical coordinate order."""

        return [self.x_min, self.y_min, self.x_max, self.y_max]


@dataclass(frozen=True, slots=True)
class SampledFrame:
    """One decoded frame held in memory for offline perception."""

    frame_index: int
    media_timestamp_seconds: float
    width: int
    height: int
    png_bytes: bytes


@dataclass(frozen=True, slots=True)
class DetectorDetection:
    """A normalized detection returned by a detector backend."""

    label: str
    confidence: float
    bounding_box: NormalizedBoundingBox
    prompt: str | None = None

    def __post_init__(self) -> None:
        if not self.label.strip():
            raise ValueError("detection labels cannot be empty")
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError("detection confidence must be within 0.0 and 1.0")


@dataclass(frozen=True, slots=True)
class VisualObservation:
    """Evidence emitted for a detected object in one event-media frame.

    ``media_timestamp_seconds`` is a position in the exact event media named by the perception
    artifact. It is intentionally not an Android elapsed-realtime timestamp and carries no
    encoded-PTS clock mapping.
    """

    observation_id: str
    event_id: str
    source_media_path: str
    frame_index: int
    media_timestamp_seconds: float
    label: str
    confidence: float
    bounding_box: NormalizedBoundingBox
    detector_backend: str
    detector_model: str
    prompt: str | None

    def as_dict(self) -> dict[str, object]:
        """Serialize an observation for the versioned perception artifact."""

        return {
            "observation_id": self.observation_id,
            "event_id": self.event_id,
            "source_media_path": self.source_media_path,
            "frame_index": self.frame_index,
            "media_timestamp_seconds": self.media_timestamp_seconds,
            "label": self.label,
            "confidence": self.confidence,
            "bounding_box": self.bounding_box.as_list(),
            "detector_backend": self.detector_backend,
            "detector_model": self.detector_model,
            "prompt": self.prompt,
        }


@dataclass(frozen=True, slots=True)
class EventPerceptionResult:
    """Result locations and counts for one replaceable offline perception run."""

    event_id: str
    media_path: Path
    output_path: Path
    frames_processed: int
    observations: tuple[VisualObservation, ...]
