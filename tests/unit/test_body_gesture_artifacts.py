"""Tests for strict typed loading and provenance of Phase 1G artifacts."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from foresight_device.body_perception.artifact import (
    ArtifactProvenanceError,
    ArtifactValidationError,
    load_body_artifact,
    sha256,
    verify_body_media,
)
from foresight_device.gestures.artifact import (
    GestureArtifactProvenanceError,
    load_gesture_artifact,
)


def body_payload() -> dict[str, object]:
    return {
        "schema_version": 1,
        "event_id": "event-1",
        "source_media_sha256": "a" * 64,
        "provider": "test-provider",
        "configuration": {"sampling_interval_seconds": 0.2},
        "hand_observations": [
            {
                "hand_observation_id": "hand-1",
                "event_id": "event-1",
                "frame_index": 3,
                "media_timestamp_seconds": 0.6,
                "provider": "test-provider",
                "confidence": 0.9,
                "handedness": "right",
                "handedness_confidence": 0.8,
                "self_association": {
                    "status": "candidate",
                    "confidence": 0.7,
                    "reasons": ["camera-relative"],
                },
                "landmarks": [
                    {"name": "wrist", "x": 0.2, "y": 0.3, "z": None},
                    {"name": "index_tip", "x": 0.3, "y": 0.2, "z": -0.1},
                ],
            }
        ],
        "hand_tracks": [
            {
                "hand_track_id": "H001",
                "observation_ids": ["hand-1"],
                "start_timestamp_seconds": 0.6,
                "end_timestamp_seconds": 0.6,
                "handedness": "right",
                "mean_confidence": 0.9,
                "self_association": {
                    "status": "candidate",
                    "confidence": 0.7,
                    "reasons": ["camera-relative"],
                },
            }
        ],
    }


def gesture_payload(body_path: Path) -> dict[str, object]:
    return {
        "schema_version": 1,
        "event_id": "event-1",
        "source_body_perception": {"filename": body_path.name, "sha256": sha256(body_path)},
        "configuration": {"backend": "wrist_motion_v1"},
        "gesture_events": [
            {
                "gesture_event_id": "gesture-1",
                "event_id": "event-1",
                "hand_track_id": "H001",
                "observation_ids": ["hand-1"],
                "start_timestamp_seconds": 0.6,
                "end_timestamp_seconds": 0.8,
                "peak_timestamp_seconds": 0.7,
                "gesture_type": "unknown_motion",
                "gesture_confidence": 0.5,
                "motion_confidence": 0.5,
                "self_association_status": "candidate",
                "fingertip": [0.3, 0.2],
            }
        ],
    }


def write_json(path: Path, payload: dict[str, object]) -> None:
    path.write_text(json.dumps(payload), encoding="utf-8")


@pytest.mark.unit
def test_body_loader_preserves_typed_hand_metadata_and_media_provenance(tmp_path: Path) -> None:
    media = tmp_path / "event.mp4"
    media.write_bytes(b"trusted media")
    payload = body_payload()
    payload["source_media_sha256"] = sha256(media)
    path = tmp_path / "event_body_perception.json"
    write_json(path, payload)

    artifact = load_body_artifact(path, event_id="event-1")

    assert artifact.observations[0].handedness_confidence == 0.8
    assert artifact.observations[0].self_association.status.value == "candidate"
    assert artifact.tracks[0].observation_ids == ("hand-1",)
    verify_body_media(artifact, media)
    media.write_bytes(b"different media")
    with pytest.raises(ArtifactProvenanceError, match="SHA-256"):
        verify_body_media(artifact, media)


@pytest.mark.unit
def test_body_loader_rejects_wrong_event_and_unknown_track_observation(tmp_path: Path) -> None:
    path = tmp_path / "event_body_perception.json"
    payload = body_payload()
    payload["hand_tracks"] = [{**body_payload()["hand_tracks"][0], "observation_ids": ["missing"]}]
    write_json(path, payload)

    with pytest.raises(ArtifactValidationError, match="unknown observation"):
        load_body_artifact(path, event_id="event-1")
    with pytest.raises(ArtifactValidationError, match="does not belong"):
        load_body_artifact(path, event_id="event-2")


@pytest.mark.unit
def test_gesture_loader_validates_body_hash_and_typed_candidate(tmp_path: Path) -> None:
    body_path = tmp_path / "event_body_perception.json"
    write_json(body_path, body_payload())
    gesture_path = tmp_path / "event_gestures.json"
    write_json(gesture_path, gesture_payload(body_path))

    artifact = load_gesture_artifact(gesture_path, event_id="event-1", body_artifact_path=body_path)

    assert artifact.gesture_events[0].gesture_type == "unknown_motion"
    changed_body = body_payload()
    changed_body["provider"] = "changed-provider"
    write_json(body_path, changed_body)
    with pytest.raises(GestureArtifactProvenanceError, match="SHA-256"):
        load_gesture_artifact(gesture_path, event_id="event-1", body_artifact_path=body_path)


@pytest.mark.unit
def test_gesture_loader_rejects_event_mismatch_and_invalid_timestamp_order(tmp_path: Path) -> None:
    body_path = tmp_path / "event_body_perception.json"
    write_json(body_path, body_payload())
    gesture_path = tmp_path / "event_gestures.json"
    payload = gesture_payload(body_path)
    candidate = payload["gesture_events"][0]
    assert isinstance(candidate, dict)
    candidate["peak_timestamp_seconds"] = 0.9
    write_json(gesture_path, payload)

    with pytest.raises(ArtifactValidationError, match="gesture candidate fields are invalid"):
        load_gesture_artifact(gesture_path, event_id="event-1")
