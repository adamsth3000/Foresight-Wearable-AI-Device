"""Event-local motion candidates derived from hand tracks, never object targets."""

from __future__ import annotations

from dataclasses import dataclass

from foresight_device.body_perception.models import SelfAssociationStatus


@dataclass(frozen=True, slots=True)
class GestureEventCandidate:
    gesture_event_id: str
    event_id: str
    hand_track_id: str
    observation_ids: tuple[str, ...]
    start_timestamp_seconds: float
    end_timestamp_seconds: float
    peak_timestamp_seconds: float
    gesture_type: str
    gesture_confidence: float
    motion_confidence: float
    self_association_status: SelfAssociationStatus
    fingertip_x: float | None
    fingertip_y: float | None

    def __post_init__(self) -> None:
        if not self.gesture_event_id or not self.hand_track_id or not self.observation_ids:
            raise ValueError("gesture events require identities and hand evidence")
        if not self.event_id or self.start_timestamp_seconds < 0:
            raise ValueError("gesture events require a valid event and timestamp")
        if (
            not self.start_timestamp_seconds
            <= self.peak_timestamp_seconds
            <= self.end_timestamp_seconds
        ):
            raise ValueError("gesture event timestamps must be ordered")
        if not 0 <= self.gesture_confidence <= 1 or not 0 <= self.motion_confidence <= 1:
            raise ValueError("gesture confidences must be within 0.0 and 1.0")
