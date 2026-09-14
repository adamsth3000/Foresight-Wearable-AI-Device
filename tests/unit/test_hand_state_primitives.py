"""Synthetic geometry tests for conservative Phase 1G-G1 hand primitives."""

from __future__ import annotations

import pytest

from foresight_device.body_perception.models import Handedness, HandObservation, NormalizedLandmark
from foresight_device.body_perception.tracking import track_hands
from foresight_device.gestures.analysis import detect_gesture_events, detect_motion_events
from foresight_device.gestures.hand_state import classify_hand, detect_semantic_events
from foresight_device.gestures.models import HandState, SemanticGestureEvidence
from foresight_device.visualization.gesture_timeline import GestureTimeline


def hand(
    identifier: str,
    timestamp: float,
    state: str,
    *,
    scale: float = 1.0,
    dx: float = 0.0,
    dy: float = 0.0,
    handedness: Handedness = Handedness.RIGHT,
) -> HandObservation:
    """Build intentionally obvious normalized skeletons, not real MediaPipe samples."""

    points = {
        "wrist": (0.50, 0.80),
        "thumb_cmc": (0.38, 0.67),
        "thumb_mcp": (0.34, 0.58),
        "thumb_ip": (0.31, 0.48),
        "thumb_tip": (0.28, 0.38),
    }
    x_positions = {"index": 0.39, "middle": 0.50, "ring": 0.61, "pinky": 0.72}
    for finger, x in x_positions.items():
        if state == "closed" or (state == "point" and finger != "index"):
            points.update(
                {
                    f"{finger}_mcp": (x, 0.60),
                    f"{finger}_pip": (x, 0.66),
                    f"{finger}_dip": (x, 0.64),
                    f"{finger}_tip": (x, 0.62),
                }
            )
        elif state == "ambiguous":
            points.update(
                {
                    f"{finger}_mcp": (x, 0.60),
                    f"{finger}_pip": (x, 0.57),
                    f"{finger}_dip": (x, 0.55),
                    f"{finger}_tip": (x, 0.53),
                }
            )
        else:
            points.update(
                {
                    f"{finger}_mcp": (x, 0.60),
                    f"{finger}_pip": (x, 0.42),
                    f"{finger}_dip": (x, 0.25),
                    f"{finger}_tip": (x, 0.10),
                }
            )
    if state == "pinch":
        points["thumb_tip"] = (0.39, 0.12)
    origin_x, origin_y = 0.50, 0.80
    landmarks = tuple(
        NormalizedLandmark(
            name,
            origin_x + (x - origin_x) * scale + dx,
            origin_y + (y - origin_y) * scale + dy,
        )
        for name, (x, y) in points.items()
    )
    return HandObservation(
        identifier,
        "event-1",
        int(timestamp * 30),
        timestamp,
        "synthetic",
        0.95,
        handedness,
        0.95,
        landmarks,
    )


@pytest.mark.unit
@pytest.mark.parametrize(
    ("source", "expected"),
    (
        ("open", HandState.OPEN_HAND),
        ("closed", HandState.CLOSED_HAND),
        ("pinch", HandState.PINCH),
        ("point", HandState.POINT),
    ),
)
def test_obvious_synthetic_hand_states_are_classified(source: str, expected: HandState) -> None:
    result = classify_hand(hand("one", 0.0, source))

    assert result.state == expected
    assert result.confidence > 0
    assert result.features.palm_scale is not None


@pytest.mark.unit
def test_ambiguous_incomplete_and_degenerate_geometry_remains_unknown() -> None:
    assert classify_hand(hand("ambiguous", 0.0, "ambiguous")).state == HandState.UNKNOWN
    incomplete = HandObservation(
        "incomplete",
        "event-1",
        0,
        0.0,
        "synthetic",
        0.95,
        Handedness.RIGHT,
        0.95,
        (NormalizedLandmark("wrist", 0.5, 0.5), NormalizedLandmark("index_tip", 0.5, 0.4)),
    )
    assert classify_hand(incomplete).state == HandState.UNKNOWN
    degenerate = HandObservation(
        "degenerate",
        "event-1",
        0,
        0.0,
        "synthetic",
        0.95,
        Handedness.RIGHT,
        0.95,
        tuple(
            NormalizedLandmark(item.name, 0.5, 0.5) for item in hand("source", 0, "open").landmarks
        ),
    )
    assert classify_hand(degenerate).state == HandState.UNKNOWN


@pytest.mark.unit
def test_hand_state_is_scale_translation_and_handedness_invariant() -> None:
    baseline = classify_hand(hand("baseline", 0.0, "open"))
    transformed = classify_hand(
        hand("transformed", 0.0, "open", scale=0.7, dx=0.08, dy=-0.06, handedness=Handedness.LEFT)
    )

    assert baseline.state == transformed.state == HandState.OPEN_HAND
    assert baseline.features.thumb_index_distance_normalized == pytest.approx(
        transformed.features.thumb_index_distance_normalized
    )


@pytest.mark.unit
def test_single_frame_flicker_and_conflicting_frames_do_not_create_semantic_candidates() -> None:
    flicker = (hand("a", 0.0, "open"),)
    conflict = (
        hand("b", 0.0, "open"),
        hand("c", 0.2, "closed"),
        hand("d", 0.4, "open"),
    )

    assert detect_semantic_events(flicker, track_hands(flicker)) == ()
    assert detect_semantic_events(conflict, track_hands(conflict)) == ()


@pytest.mark.unit
def test_stable_same_state_track_creates_traceable_semantic_candidate() -> None:
    observations = (
        hand("a", 0.0, "point"),
        hand("b", 0.2, "point"),
        hand("c", 0.4, "point"),
    )
    candidate = detect_semantic_events(observations, track_hands(observations))[0]

    assert candidate.gesture_type == HandState.POINT.value
    assert candidate.observation_ids == ("a", "b", "c")
    assert candidate.semantic_evidence is not None
    assert candidate.semantic_evidence.peak_observation_id in candidate.observation_ids
    assert candidate.semantic_evidence.support_observation_count == 3


@pytest.mark.unit
def test_unknown_motion_remains_available_alongside_semantic_candidates() -> None:
    observations = (
        hand("a", 0.0, "open", dx=0.0),
        hand("b", 0.2, "open", dx=0.06),
        hand("c", 0.4, "open", dx=0.12),
    )
    tracks = track_hands(observations)

    assert detect_motion_events(observations, tracks)[0].gesture_type == "unknown_motion"
    assert {item.gesture_type for item in detect_gesture_events(observations, tracks)} == {
        "unknown_motion",
        HandState.OPEN_HAND.value,
    }


@pytest.mark.unit
def test_timeline_displays_semantic_candidate_with_its_existing_type_label() -> None:
    observations = (
        hand("a", 0.0, "point"),
        hand("b", 0.2, "point"),
        hand("c", 0.4, "point"),
    )
    candidate = detect_semantic_events(observations, track_hands(observations))[0]

    ring = GestureTimeline(observations, (candidate,)).at(0.2)[0]

    assert ring.gesture_type == HandState.POINT.value
    assert ring.label == HandState.POINT.value
    assert candidate.semantic_evidence is not None
    assert isinstance(candidate.semantic_evidence, SemanticGestureEvidence)
