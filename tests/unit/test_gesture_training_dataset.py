"""Tests for read-only human-gesture dataset auditing and extraction."""

from __future__ import annotations

import json
from pathlib import Path

from foresight_device.annotation.gesture_annotations import (
    GestureAnnotation,
    GestureAnnotationLabel,
    GestureTrack,
    HumanHandedness,
)
from foresight_device.body_perception.artifact import BodyArtifact, sha256
from foresight_device.body_perception.models import (
    Handedness,
    HandObservation,
    HandTrack,
    NormalizedLandmark,
    SelfAssociation,
)
from foresight_device.gestures.training_dataset import (
    _format_audit,
    audit_gesture_dataset,
    build_gesture_training_dataset,
    write_gesture_training_dataset,
)
from foresight_device.perception.event_media import ResolvedEventMedia


class _Store:
    event_id = "event-1"

    def __init__(self, annotations: tuple[GestureAnnotation, ...], body: BodyArtifact) -> None:
        self._annotations = annotations
        self._body = body

    def load(self) -> tuple[GestureAnnotation, ...]:
        return self._annotations

    def load_tracks(self) -> tuple[GestureTrack, ...]:
        return tuple(
            GestureTrack(
                item.gesture_track_id or item.annotation_id,
                item.start_timestamp_seconds,
                item.end_timestamp_seconds,
            )
            for item in self._annotations
        )

    def observations_for(self, annotation: GestureAnnotation) -> tuple[HandObservation, ...]:
        observation_ids = {
            observation_id
            for track in self._body.tracks
            if track.hand_track_id in annotation.hand_track_ids
            for observation_id in track.observation_ids
        }
        return tuple(
            observation
            for observation in self._body.observations
            if annotation.start_timestamp_seconds
            <= observation.media_timestamp_seconds
            <= annotation.end_timestamp_seconds
            and observation.hand_observation_id in observation_ids
        )


def test_audit_distinguishes_valid_zero_evidence_samples() -> None:
    body = _body()
    annotations = (
        _annotation("1", GestureAnnotationLabel.POINT, HumanHandedness.RIGHT, ("H001",)),
        _annotation("2", GestureAnnotationLabel.SNAPSHOT, HumanHandedness.BOTH, ()),
    )
    audit = audit_gesture_dataset(_Store(annotations, body), body)  # type: ignore[arg-type]

    assert audit["confirmed_gesture_samples"] == 2
    assert audit["landmark_training_eligible"] == 1
    assert audit["supporting_tracks"] == {"zero": 1, "one": 1, "multiple": 0}
    assert audit["by_gesture"]["SNAPSHOT"]["without_landmark_evidence"] == 1  # type: ignore[index]
    assert "SNAPSHOT" in _format_audit(audit)


def test_extraction_preserves_landmarks_and_zero_evidence_metadata(tmp_path: Path) -> None:
    body = _body()
    annotations = (
        _annotation("1", GestureAnnotationLabel.POINT, HumanHandedness.RIGHT, ("H001",)),
        _annotation("2", GestureAnnotationLabel.CUT, HumanHandedness.LEFT, ()),
    )
    store = _Store(annotations, body)
    annotation_path = tmp_path / "event_gesture_annotations.json"
    body_path = tmp_path / "event_body_perception.json"
    annotation_path.write_text("{}", encoding="utf-8")
    body_path.write_text("{}", encoding="utf-8")
    media = ResolvedEventMedia(
        "event-1", tmp_path / "video.mp4", "phone_media/authoritative.mp4", "a" * 64, "phone_local"
    )
    dataset = build_gesture_training_dataset(
        store, body, annotation_path=annotation_path, body_path=body_path, media=media
    )  # type: ignore[arg-type]

    first, second = dataset["samples"]  # type: ignore[misc]
    assert first["landmark_training_eligible"] is True
    assert first["observations"][0]["landmarks"][0]["name"] == "wrist"
    assert first["observations"][0]["relative_timestamp_seconds"] == 0.25
    assert first["source_body_perception"] == {"sha256": sha256(body_path)}
    assert first["source_media"] == {"sha256": "a" * 64}
    assert second["human_labeled"] is True
    assert second["landmark_training_eligible"] is False
    assert second["observations"] == []
    output = tmp_path / "event_gesture_training_dataset.json"
    write_gesture_training_dataset(output, dataset)
    assert json.loads(output.read_text(encoding="utf-8"))["samples"][1]["label"] == "CUT"


def test_audit_reports_zero_one_and_multi_track_evidence() -> None:
    body = _body()
    annotations = (
        _annotation("1", GestureAnnotationLabel.POINT, HumanHandedness.RIGHT, ()),
        _annotation("2", GestureAnnotationLabel.OPEN_HAND, HumanHandedness.RIGHT, ("H001",)),
        _annotation("3", GestureAnnotationLabel.SNAPSHOT, HumanHandedness.BOTH, ("H001", "H002")),
    )
    audit = audit_gesture_dataset(_Store(annotations, body), body)  # type: ignore[arg-type]

    assert audit["supporting_tracks"] == {"zero": 1, "one": 1, "multiple": 1}
    assert audit["body_observations"] == {"zero": 1, "one": 1, "multiple": 1}
    assert audit["observation_count"] == {"min": 0.0, "median": 1.0, "mean": 1.0, "max": 2.0}
    assert audit["by_gesture"]["SNAPSHOT"]["BOTH"] == 1  # type: ignore[index]


def test_human_valid_sample_without_handedness_is_not_landmark_eligible(tmp_path: Path) -> None:
    body = _body()
    annotation = GestureAnnotation(
        "00000000-0000-4000-8000-000000000004",
        GestureAnnotationLabel.POINT,
        1.0,
        2.0,
        ("H001",),
        "2026-01-01T00:00:00+00:00",
        "2026-01-01T00:00:00+00:00",
        gesture_track_id="10000000-0000-4000-8000-000000000004",
    )
    store = _Store((annotation,), body)
    annotation_path = tmp_path / "event_gesture_annotations.json"
    body_path = tmp_path / "event_body_perception.json"
    annotation_path.write_text("{}", encoding="utf-8")
    body_path.write_text("{}", encoding="utf-8")
    media = ResolvedEventMedia(
        "event-1", tmp_path / "video.mp4", "phone_media/authoritative.mp4", "a" * 64, "phone_local"
    )
    dataset = build_gesture_training_dataset(
        store, body, annotation_path=annotation_path, body_path=body_path, media=media
    )  # type: ignore[arg-type]

    record = dataset["samples"][0]  # type: ignore[index]
    assert record["human_labeled"] is True
    assert record["human_handedness"] == "UNASSIGNED"
    assert record["landmark_training_eligible"] is False


def _annotation(
    suffix: str,
    label: GestureAnnotationLabel,
    handedness: HumanHandedness,
    tracks: tuple[str, ...],
) -> GestureAnnotation:
    return GestureAnnotation(
        f"00000000-0000-4000-8000-00000000000{suffix}",
        label,
        1.0,
        2.0,
        tracks,
        "2026-01-01T00:00:00+00:00",
        "2026-01-01T00:00:00+00:00",
        human_handedness=handedness,
        gesture_track_id=f"10000000-0000-4000-8000-00000000000{suffix}",
    )


def _body() -> BodyArtifact:
    first_observation = HandObservation(
        "O001",
        "event-1",
        1,
        1.25,
        "fixture",
        0.9,
        Handedness.RIGHT,
        0.8,
        (NormalizedLandmark("wrist", 0.2, 0.2), NormalizedLandmark("index_tip", 0.3, 0.2)),
    )
    second_observation = HandObservation(
        "O002",
        "event-1",
        2,
        1.75,
        "fixture",
        0.8,
        Handedness.LEFT,
        0.7,
        (NormalizedLandmark("wrist", 0.4, 0.4), NormalizedLandmark("index_tip", 0.5, 0.4)),
    )
    first_track = HandTrack(
        "H001", "event-1", ("O001",), 1.0, 2.0, Handedness.RIGHT, SelfAssociation(), 0.9
    )
    second_track = HandTrack(
        "H002", "event-1", ("O002",), 1.0, 2.0, Handedness.LEFT, SelfAssociation(), 0.8
    )
    return BodyArtifact(
        "event-1",
        "a" * 64,
        "fixture",
        0.2,
        (first_observation, second_observation),
        (first_track, second_track),
    )
