"""Tests for temporal, evidence-backed Phase 1G gesture ring rendering."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from foresight_device.body_perception.artifact import BodyArtifact
from foresight_device.body_perception.models import (
    Handedness,
    HandObservation,
    NormalizedLandmark,
    SelfAssociation,
)
from foresight_device.gestures.artifact import GestureArtifact, GestureArtifactProvenanceError
from foresight_device.gestures.models import GestureEventCandidate
from foresight_device.perception.models import NormalizedBoundingBox, VisualObservation
from foresight_device.visualization.editor_main import load_editor_controller
from foresight_device.visualization.gesture_timeline import GestureTimeline


def hand(identifier: str, timestamp: float, *, fingertip: bool = True) -> HandObservation:
    landmarks = [NormalizedLandmark("wrist", 0.2 + timestamp / 10, 0.4)]
    if fingertip:
        landmarks.append(NormalizedLandmark("index_tip", 0.4 + timestamp / 10, 0.2))
    else:
        landmarks.extend(
            (
                NormalizedLandmark("thumb_tip", 0.5, 0.3),
                NormalizedLandmark("pinky_tip", 0.3, 0.5),
            )
        )
    return HandObservation(
        identifier,
        "event-1",
        int(timestamp * 10),
        timestamp,
        "fake",
        0.9,
        Handedness.RIGHT,
        0.9,
        tuple(landmarks),
    )


def candidate(observation_ids: tuple[str, ...] = ("hand-1", "hand-2")) -> GestureEventCandidate:
    return GestureEventCandidate(
        "gesture-1",
        "event-1",
        "H001",
        observation_ids,
        1.0,
        2.0,
        1.5,
        "unknown_motion",
        0.6,
        0.6,
        SelfAssociation().status,
        None,
        None,
    )


def timeline(*, fingertip: bool = True) -> GestureTimeline:
    observations = (hand("hand-1", 1.0, fingertip=fingertip), hand("hand-2", 2.0))
    body = BodyArtifact("event-1", "a" * 64, "fake", 0.2, observations, ())
    gestures = GestureArtifact(
        "event-1", "event_body_perception.json", "b" * 64, "fake", (candidate(),)
    )
    return GestureTimeline.from_artifacts(body, gestures)


@pytest.mark.unit
def test_timeline_builds_deterministic_unknown_motion_ring_from_nearest_fingertip() -> None:
    first = timeline().at(1.0)
    second = timeline().at(1.0)

    assert first == second
    ring = first[0]
    assert ring.gesture_event_id == "gesture-1"
    assert ring.gesture_type == "unknown_motion"
    assert ring.center.x == pytest.approx(0.5)  # index fingertip, not wrist
    assert ring.center.y == pytest.approx(0.2)
    assert ring.hand_track_id == "H001"
    assert ring.source_hand_observation_ids == ("hand-1", "hand-2")


@pytest.mark.unit
def test_timeline_uses_hand_region_when_index_fingertip_is_unavailable() -> None:
    ring = timeline(fingertip=False).at(1.0)[0]

    assert ring.center.x == pytest.approx(0.4)
    assert ring.center.y == pytest.approx(0.4)
    assert ring.radius_normalized > 0.03


@pytest.mark.unit
def test_timeline_visibility_is_limited_to_inclusive_candidate_interval() -> None:
    rings = timeline()

    assert rings.at(0.99) == ()
    assert len(rings.at(1.0)) == 1
    assert len(rings.at(2.0)) == 1
    assert rings.at(2.01) == ()


def observation_payload() -> dict[str, object]:
    return VisualObservation(
        "obs-1",
        "event-1",
        "event.mp4",
        1,
        1.0,
        "person",
        0.9,
        NormalizedBoundingBox(0.1, 0.1, 0.3, 0.3),
        "fake",
        "fake",
        "person",
    ).as_dict()


def body_payload() -> dict[str, object]:
    return {
        "schema_version": 1,
        "event_id": "event-1",
        "source_media_sha256": "a" * 64,
        "provider": "fake",
        "configuration": {"sampling_interval_seconds": 0.2},
        "hand_observations": [
            {
                "hand_observation_id": "hand-1",
                "event_id": "event-1",
                "frame_index": 10,
                "media_timestamp_seconds": 1.0,
                "provider": "fake",
                "confidence": 0.9,
                "handedness": "right",
                "landmarks": [
                    {"name": "wrist", "x": 0.2, "y": 0.4, "z": None},
                    {"name": "index_tip", "x": 0.4, "y": 0.2, "z": None},
                ],
            }
        ],
        "hand_tracks": [
            {
                "hand_track_id": "H001",
                "observation_ids": ["hand-1"],
                "start_timestamp_seconds": 1.0,
                "end_timestamp_seconds": 1.0,
                "handedness": "right",
                "mean_confidence": 0.9,
                "self_association": {"status": "unknown", "confidence": None, "reasons": []},
            }
        ],
    }


def write_artifacts(event_dir: Path) -> None:
    body_path = event_dir / "event_body_perception.json"
    body_path.write_text(json.dumps(body_payload()), encoding="utf-8")
    from foresight_device.body_perception.artifact import sha256

    (event_dir / "event_gestures.json").write_text(
        json.dumps(
            {
                "schema_version": 1,
                "event_id": "event-1",
                "source_body_perception": {"filename": body_path.name, "sha256": sha256(body_path)},
                "configuration": {"backend": "fake"},
                "gesture_events": [
                    {
                        "gesture_event_id": "gesture-1",
                        "event_id": "event-1",
                        "hand_track_id": "H001",
                        "observation_ids": ["hand-1"],
                        "start_timestamp_seconds": 1.0,
                        "end_timestamp_seconds": 1.0,
                        "peak_timestamp_seconds": 1.0,
                        "gesture_type": "unknown_motion",
                        "gesture_confidence": 0.5,
                        "motion_confidence": 0.5,
                        "self_association_status": "unknown",
                        "fingertip": [0.4, 0.2],
                    }
                ],
            }
        ),
        encoding="utf-8",
    )


@pytest.mark.unit
def test_editor_loads_optional_typed_gesture_artifacts_without_annotation_side_effects(
    tmp_path: Path,
) -> None:
    (tmp_path / "event_perception.json").write_text(
        json.dumps({"event_id": "event-1", "observations": [observation_payload()]}),
        encoding="utf-8",
    )
    write_artifacts(tmp_path)

    controller = load_editor_controller(tmp_path)

    assert len(controller.gesture_primitives_at(1.0)) == 1
    assert controller.gesture_primitives_at(1.1) == ()
    assert not (tmp_path / "event_annotations.json").exists()


@pytest.mark.unit
def test_editor_remains_usable_without_gesture_artifacts(tmp_path: Path) -> None:
    (tmp_path / "event_perception.json").write_text(
        json.dumps({"event_id": "event-1", "observations": [observation_payload()]}),
        encoding="utf-8",
    )

    controller = load_editor_controller(tmp_path)

    assert controller.gesture_primitives_at(1.0) == ()


@pytest.mark.unit
def test_editor_rejects_stale_gesture_artifact_provenance(tmp_path: Path) -> None:
    (tmp_path / "event_perception.json").write_text(
        json.dumps({"event_id": "event-1", "observations": [observation_payload()]}),
        encoding="utf-8",
    )
    write_artifacts(tmp_path)
    body_path = tmp_path / "event_body_perception.json"
    stale_body = body_payload()
    stale_body["provider"] = "changed-provider"
    body_path.write_text(json.dumps(stale_body), encoding="utf-8")

    with pytest.raises(GestureArtifactProvenanceError, match="SHA-256"):
        load_editor_controller(tmp_path)
