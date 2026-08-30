"""Tests for provider-neutral hand models, tracks, and conservative motion events."""

from __future__ import annotations

import pytest

from foresight_device.body_perception.models import Handedness, HandObservation, NormalizedLandmark
from foresight_device.body_perception.tracking import track_hands
from foresight_device.gestures.analysis import detect_motion_events


def hand(
    identifier: str, timestamp: float, x: float, handedness: Handedness = Handedness.UNKNOWN
) -> HandObservation:
    return HandObservation(
        identifier,
        "event-1",
        int(timestamp * 30),
        timestamp,
        "fake",
        0.9,
        handedness,
        None,
        (NormalizedLandmark("wrist", x, 0.4), NormalizedLandmark("index_tip", x + 0.05, 0.3)),
    )


@pytest.mark.unit
def test_hand_validation_and_unknown_self_are_explicit() -> None:
    item = hand("one", 0, 0.2)
    assert item.self_association.status.value == "unknown"
    with pytest.raises(ValueError, match="invalid normalized"):
        NormalizedLandmark("wrist", 1.1, 0.2)


@pytest.mark.unit
def test_hand_tracking_is_deterministic_and_large_jump_breaks_track() -> None:
    observations = (
        hand("a", 0, 0.1, Handedness.LEFT),
        hand("b", 0.2, 0.12, Handedness.LEFT),
        hand("c", 0.4, 0.8, Handedness.LEFT),
    )
    tracks = track_hands(observations)
    assert [item.observation_ids for item in tracks] == [("a", "b"), ("c",)]


@pytest.mark.unit
def test_stationary_hand_has_no_event_and_motion_has_targetless_candidate() -> None:
    stationary = (hand("a", 0, 0.1), hand("b", 0.2, 0.11))
    moving = (hand("c", 0, 0.1), hand("d", 0.2, 0.2), hand("e", 0.4, 0.3))
    assert detect_motion_events(stationary, track_hands(stationary)) == ()
    event = detect_motion_events(moving, track_hands(moving))[0]
    assert event.gesture_type == "unknown_motion"
    assert event.hand_track_id == "H001"
