"""Provider-neutral high-frequency hand and partial-body evidence."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

from foresight_device.perception.models import NormalizedBoundingBox

LANDMARK_NAMES = (
    "wrist",
    "thumb_tip",
    "index_mcp",
    "index_tip",
    "middle_tip",
    "ring_tip",
    "pinky_tip",
)


class Handedness(StrEnum):
    LEFT = "left"
    RIGHT = "right"
    UNKNOWN = "unknown"


class SelfAssociationStatus(StrEnum):
    UNKNOWN = "unknown"
    CANDIDATE = "candidate"
    ASSOCIATED = "associated"
    REJECTED = "rejected"


@dataclass(frozen=True, slots=True)
class NormalizedLandmark:
    name: str
    x: float
    y: float
    z: float | None = None

    def __post_init__(self) -> None:
        if self.name not in LANDMARK_NAMES or not 0 <= self.x <= 1 or not 0 <= self.y <= 1:
            raise ValueError("invalid normalized hand landmark")


@dataclass(frozen=True, slots=True)
class SelfAssociation:
    status: SelfAssociationStatus = SelfAssociationStatus.UNKNOWN
    confidence: float | None = None
    reasons: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        if self.confidence is not None and not 0 <= self.confidence <= 1:
            raise ValueError("self association confidence must be within 0.0 and 1.0")


@dataclass(frozen=True, slots=True)
class HandObservation:
    hand_observation_id: str
    event_id: str
    frame_index: int
    media_timestamp_seconds: float
    provider: str
    confidence: float
    handedness: Handedness
    handedness_confidence: float | None
    landmarks: tuple[NormalizedLandmark, ...]
    self_association: SelfAssociation = SelfAssociation()

    def __post_init__(self) -> None:
        if not self.hand_observation_id or not self.event_id or self.frame_index < 0:
            raise ValueError("invalid hand observation identity")
        if self.media_timestamp_seconds < 0 or not 0 <= self.confidence <= 1 or not self.landmarks:
            raise ValueError("invalid hand observation time, confidence, or landmarks")
        if self.handedness_confidence is not None and not 0 <= self.handedness_confidence <= 1:
            raise ValueError("invalid handedness confidence")

    @property
    def wrist(self) -> NormalizedLandmark:
        return next(item for item in self.landmarks if item.name == "wrist")

    @property
    def fingertip(self) -> NormalizedLandmark | None:
        return next((item for item in self.landmarks if item.name == "index_tip"), None)

    @property
    def bounding_box(self) -> NormalizedBoundingBox:
        return NormalizedBoundingBox(
            min(item.x for item in self.landmarks),
            min(item.y for item in self.landmarks),
            max(item.x for item in self.landmarks),
            max(item.y for item in self.landmarks),
        )


@dataclass(frozen=True, slots=True)
class HandTrack:
    hand_track_id: str
    event_id: str
    observation_ids: tuple[str, ...]
    start_timestamp_seconds: float
    end_timestamp_seconds: float
    handedness: Handedness
    self_association: SelfAssociation
    mean_confidence: float
