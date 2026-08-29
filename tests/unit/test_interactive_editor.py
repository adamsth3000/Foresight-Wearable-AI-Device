"""Tests for interactive editor state, hit testing, and annotation boundaries."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from foresight_device.annotation.models import AnnotationAction
from foresight_device.annotation.store import AnnotationStore
from foresight_device.perception.models import NormalizedBoundingBox, VisualObservation
from foresight_device.visualization.editor_controller import EditorController, VideoViewport
from foresight_device.visualization.interaction import (
    GestureAssociationDebug,
    GestureCandidate,
    NormalizedPoint,
    PointingVector,
    RelationshipArrowPrimitive,
)
from foresight_device.visualization.label_choices import known_label_choices
from foresight_device.visualization.overlay import OverlayState
from foresight_device.visualization.perception_loader import (
    PerceptionArtifactError,
    load_perception,
)


def _observation(
    *,
    observation_id: str = "obs-1",
    timestamp: float = 3.0,
    label: str = "tree",
    box: NormalizedBoundingBox | None = None,
) -> VisualObservation:
    return VisualObservation(
        observation_id=observation_id,
        event_id="event-1",
        source_media_path="event.mp4",
        frame_index=90,
        media_timestamp_seconds=timestamp,
        label=label,
        confidence=0.8,
        bounding_box=box or NormalizedBoundingBox(0.1, 0.2, 0.5, 0.7),
        detector_backend="grounding_dino",
        detector_model="base",
        prompt="tree",
    )


def _controller(tmp_path: Path, observations: tuple[VisualObservation, ...]) -> EditorController:
    return EditorController(
        observations,
        AnnotationStore(
            tmp_path / "event_annotations.json",
            event_id="event-1",
            observation_ids={item.observation_id for item in observations},
        ),
    )


@pytest.mark.unit
def test_click_selection_clear_and_display_coordinate_mapping(tmp_path: Path) -> None:
    controller = _controller(tmp_path, (_observation(),))
    viewport = VideoViewport(100, 100, 300, 100)

    assert viewport.source_coordinates(99, 25) is None  # left letterbox
    selected = controller.click(125, 30, timestamp_seconds=3.0, viewport=viewport)
    assert selected is not None and selected.observation_id == "obs-1"
    assert (
        controller.overlays_at(3.0, width=100, height=100)[0].state
        == OverlayState.MANUALLY_SELECTED
    )

    assert controller.click(250, 95, timestamp_seconds=3.0, viewport=viewport) is None
    assert controller.interaction.selected_observation_id is None
    assert controller.overlays_at(3.0, width=100, height=100)[0].state == OverlayState.DETECTED


@pytest.mark.unit
def test_overlapping_boxes_select_smallest_deterministically(tmp_path: Path) -> None:
    large = _observation(observation_id="large", box=NormalizedBoundingBox(0.1, 0.1, 0.9, 0.9))
    small = _observation(observation_id="small", box=NormalizedBoundingBox(0.4, 0.4, 0.6, 0.6))
    controller = _controller(tmp_path, (large, small))

    selected = controller.click(
        50, 50, timestamp_seconds=3.0, viewport=VideoViewport(100, 100, 100, 100)
    )

    assert selected is not None and selected.observation_id == "small"


@pytest.mark.unit
def test_gesture_candidate_and_target_are_transient_and_distinct(tmp_path: Path) -> None:
    candidate = _observation(observation_id="candidate")
    resolved = _observation(observation_id="resolved")
    controller = _controller(tmp_path, (candidate, resolved))
    controller.set_gesture_debug(
        GestureAssociationDebug(
            gesture_id="gesture-1",
            media_timestamp_seconds=3.0,
            hand_detected=True,
            hand_landmarks=(NormalizedPoint(0.1, 0.2),),
            fingertip=NormalizedPoint(0.2, 0.3),
            recognized_gesture="point",
            gesture_confidence=0.9,
            pointing_origin=NormalizedPoint(0.2, 0.3),
            pointing_vector=PointingVector(0.8, 0.0),
            candidates=(
                GestureCandidate(
                    "candidate",
                    association_confidence=0.6,
                    vector_intersects_object=True,
                    angular_error_degrees=4.0,
                    spatial_distance_normalized=0.2,
                    target_selection_score=0.6,
                    rejection_reason="awaiting target resolution",
                ),
            ),
            resolved_target_observation_id="resolved",
        )
    )

    states = {
        item.observation.observation_id: item.state
        for item in controller.overlays_at(3.0, width=100, height=100)
    }
    assert states == {
        "candidate": OverlayState.GESTURE_CANDIDATE,
        "resolved": OverlayState.GESTURE_TARGETED,
    }
    assert (tmp_path / "event_annotations.json").exists() is False
    primitives = controller.gesture_primitives_at(3.0)
    assert len(primitives) == 3
    assert any(
        isinstance(item, RelationshipArrowPrimitive) and item.resolved for item in primitives
    )

    controller.click(20, 30, timestamp_seconds=3.0, viewport=VideoViewport(100, 100, 100, 100))
    annotation = controller.annotate_selected(AnnotationAction.VALIDATE)

    assert annotation.action == AnnotationAction.VALIDATE
    assert (
        controller.overlays_at(3.0, width=100, height=100)[0].state
        == OverlayState.MANUALLY_SELECTED
    )
    assert (
        len(
            AnnotationStore(
                tmp_path / "event_annotations.json", event_id="event-1", observation_ids={"obs-1"}
            ).load()
        )
        == 1
    )


@pytest.mark.unit
def test_unresolved_gesture_candidate_is_yellow_only_at_its_debug_timestamp(tmp_path: Path) -> None:
    observation = _observation()
    controller = _controller(tmp_path, (observation,))
    controller.set_gesture_debug(
        GestureAssociationDebug(
            gesture_id="gesture-failure",
            media_timestamp_seconds=3.0,
            hand_detected=True,
            candidates=(GestureCandidate("obs-1", rejection_reason="ray missed target"),),
            failure_reason="no candidate target satisfied the association policy",
        )
    )

    assert (
        controller.overlays_at(3.0, width=100, height=100)[0].state
        == OverlayState.GESTURE_CANDIDATE
    )
    assert controller.overlays_at(3.6, width=100, height=100) == ()
    assert (tmp_path / "event_annotations.json").exists() is False


@pytest.mark.unit
def test_validate_reject_relabel_persist_without_mutating_model_observation(tmp_path: Path) -> None:
    observation = _observation()
    controller = _controller(tmp_path, (observation,))
    controller.click(20, 30, timestamp_seconds=3.0, viewport=VideoViewport(100, 100, 100, 100))

    validated = controller.annotate_selected(AnnotationAction.VALIDATE)
    rejected = controller.annotate_selected(AnnotationAction.REJECT)
    relabeled = controller.annotate_selected(AnnotationAction.RELABEL, corrected_label="sign")
    reloaded = AnnotationStore(
        tmp_path / "event_annotations.json", event_id="event-1", observation_ids={"obs-1"}
    ).load()

    assert [item.action for item in reloaded] == [
        validated.action,
        rejected.action,
        relabeled.action,
    ]
    assert reloaded[-1].original_label == "tree"
    assert reloaded[-1].corrected_label == "sign"
    assert observation.label == "tree"
    overlay = controller.overlays_at(3.0, width=100, height=100)[0]
    assert overlay.display_label == "sign"


@pytest.mark.unit
def test_known_relabel_choices_are_convenient_but_arbitrary_labels_remain_valid(
    tmp_path: Path,
) -> None:
    observation = _observation()
    controller = _controller(tmp_path, (observation,))

    assert controller.known_labels == ("tree",)
    controller.click(20, 30, timestamp_seconds=3.0, viewport=VideoViewport(100, 100, 100, 100))
    annotation = controller.annotate_selected(
        AnnotationAction.RELABEL, corrected_label="custom fixture"
    )

    assert annotation.original_label == "tree"
    assert annotation.corrected_label == "custom fixture"


@pytest.mark.unit
def test_known_label_choices_reach_the_combobox_facing_event_collection() -> None:
    observations = (
        _observation(observation_id="one", label="tree"),
        _observation(observation_id="two", label="Person"),
        _observation(observation_id="three", label="chair"),
        _observation(observation_id="four", label="person"),
    )

    assert known_label_choices(observations) == ("chair", "Person", "tree")


@pytest.mark.unit
def test_timeline_scrubbing_hides_stale_observations(tmp_path: Path) -> None:
    first = _observation(observation_id="first", timestamp=3.0)
    second = _observation(observation_id="second", timestamp=6.0)
    controller = _controller(tmp_path, (first, second))

    assert [
        item.observation.observation_id
        for item in controller.overlays_at(3.0, width=100, height=100)
    ] == ["first"]
    assert [
        item.observation.observation_id
        for item in controller.overlays_at(6.0, width=100, height=100)
    ] == ["second"]
    assert controller.overlays_at(4.0, width=100, height=100) == ()


@pytest.mark.unit
def test_real_schema_fixture_and_missing_or_malformed_artifacts(tmp_path: Path) -> None:
    path = tmp_path / "event_perception.json"
    path.write_text(
        json.dumps(
            {
                "event_id": "event-1",
                "observations": [_observation().as_dict()],
            }
        ),
        encoding="utf-8",
    )
    loaded = load_perception(path)
    assert loaded.event_id == "event-1"
    assert loaded.observations[0].observation_id == "obs-1"

    with pytest.raises(PerceptionArtifactError, match="could not be read"):
        load_perception(tmp_path / "missing.json")
    path.write_text("{}", encoding="utf-8")
    with pytest.raises(PerceptionArtifactError, match="missing event_id"):
        load_perception(path)
