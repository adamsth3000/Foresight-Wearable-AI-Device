"""Tests for source-neutral recorded-event overlay planning and rendering."""

from __future__ import annotations

import json
from datetime import UTC, datetime
from pathlib import Path

import pytest

from foresight_device.annotation.models import AnnotationAction, HumanAnnotation
from foresight_device.perception.models import NormalizedBoundingBox, VisualObservation
from foresight_device.visualization.ffmpeg_renderer import FfmpegOverlayRenderer, VideoDimensions
from foresight_device.visualization.overlay import OverlayState, OverlayTimeline, to_pixel_box
from foresight_device.visualization.perception_loader import (
    PerceptionArtifactError,
    load_perception,
)


def _observation(*, observation_id: str = "obs-1", timestamp: float = 1.0) -> VisualObservation:
    return VisualObservation(
        observation_id=observation_id,
        event_id="event-1",
        source_media_path="event.mp4",
        frame_index=30,
        media_timestamp_seconds=timestamp,
        label="tree",
        confidence=0.8,
        bounding_box=NormalizedBoundingBox(0.1, 0.2, 0.5, 0.6),
        detector_backend="fake",
        detector_model="fake-v1",
        prompt="tree",
    )


@pytest.mark.unit
def test_normalized_box_timestamp_association_and_deterministic_order() -> None:
    box = to_pixel_box(NormalizedBoundingBox(0.1, 0.2, 0.5, 0.6), width=1280, height=720)
    timeline = OverlayTimeline((_observation(observation_id="b"), _observation(observation_id="a")))

    assert (box.x, box.y, box.width, box.height) == (128, 144, 512, 288)
    assert [
        item.observation.observation_id for item in timeline.at(1.0, width=1280, height=720)
    ] == ["a", "b"]
    assert timeline.at(1.51, width=1280, height=1280) == ()


@pytest.mark.unit
def test_annotation_states_and_relabel_do_not_change_observation() -> None:
    observation = _observation()
    annotation = HumanAnnotation(
        annotation_id="annotation-1",
        observation_id="obs-1",
        event_id="event-1",
        media_timestamp_seconds=1.0,
        action=AnnotationAction.RELABEL,
        original_label="tree",
        corrected_label="sign",
        notes=None,
        created_at_utc=datetime.now(UTC),
    )
    rejected = HumanAnnotation(
        annotation_id="annotation-2",
        observation_id="obs-1",
        event_id="event-1",
        media_timestamp_seconds=1.0,
        action=AnnotationAction.REJECT,
        original_label="tree",
        corrected_label=None,
        notes=None,
        created_at_utc=datetime.now(UTC),
    )

    relabeled = OverlayTimeline((observation,), (annotation,)).at(1.0, width=100, height=100)[0]
    rejected_item = OverlayTimeline((observation,), (rejected,)).at(1.0, width=100, height=100)[0]
    assert relabeled.display_label == "sign"
    assert rejected_item.state == OverlayState.REJECTED
    assert observation.label == "tree"


@pytest.mark.unit
def test_loader_rejects_malformed_boxes_and_accepts_empty_observations(tmp_path: Path) -> None:
    path = tmp_path / "event_perception.json"
    path.write_text(json.dumps({"event_id": "event-1", "observations": []}), encoding="utf-8")
    assert load_perception(path).observations == ()

    path.write_text(
        json.dumps({"event_id": "event-1", "observations": [{"bounding_box": [0, 0, 2, 1]}]}),
        encoding="utf-8",
    )
    with pytest.raises(PerceptionArtifactError, match="invalid schema"):
        load_perception(path)


@pytest.mark.unit
def test_filter_graph_is_timestamp_limited_and_preserves_video_and_audio_mapping() -> None:
    renderer = FfmpegOverlayRenderer(font_file=Path("C:/fonts/test.ttf"))
    dimensions = VideoDimensions(1280, 720, 4.0)
    graph = renderer.filter_graph(OverlayTimeline((_observation(timestamp=1.25),)), dimensions)
    command = renderer.build_command(Path("event.mp4"), Path("annotated.mp4"), graph)

    assert "between(t,0.750,1.750)" in graph
    assert "drawbox" in graph and "drawtext" in graph
    assert "-map" in command and "0:a?" in command
    assert "-c:a" in command and "copy" in command
    assert "-vf" not in command


@pytest.mark.unit
def test_track_identity_is_compact_label_metadata_not_a_visual_state() -> None:
    item = OverlayTimeline((_observation(),), track_ids={"obs-1": "T003"}).at(
        1.0, width=100, height=100
    )[0]

    assert item.display_label == "tree · T003"
    assert item.state == OverlayState.DETECTED
