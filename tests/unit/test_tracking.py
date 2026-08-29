"""Tests for deterministic sparse derived entity tracking and artifact integrity."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from foresight_device.perception.models import NormalizedBoundingBox, VisualObservation
from foresight_device.tracking.artifact import (
    TrackingArtifactError,
    load_track_index,
    write_tracking_artifact,
)
from foresight_device.tracking.baseline import BaselineTracker, TrackingConfig


def _obs(
    identifier: str, timestamp: float, label: str = "person", x: float = 0.1
) -> VisualObservation:
    return VisualObservation(
        identifier,
        "event-1",
        "event.mp4",
        int(timestamp * 30),
        timestamp,
        label,
        0.8,
        NormalizedBoundingBox(x, 0.1, x + 0.1, 0.3),
        "fake",
        "fake",
        label,
    )


@pytest.mark.unit
def test_deterministic_one_to_one_sparse_matching_and_same_timestamp_separation() -> None:
    observations = (
        _obs("a", 0, x=0.1),
        _obs("b", 0, x=0.7),
        _obs("c", 3, x=0.12),
        _obs("d", 3, x=0.72),
    )
    tracker = BaselineTracker()
    first = tracker.track(observations)
    second = tracker.track(tuple(reversed(observations)))

    assert [track.observation_ids for track in first.tracks] == [("a", "c"), ("b", "d")]
    assert [track.as_dict() for track in first.tracks] == [
        track.as_dict() for track in second.tracks
    ]
    assert len({item for track in first.tracks for item in track.observation_ids}) == 4
    assert all(track.self_association is None for track in first.tracks)


@pytest.mark.unit
def test_label_spatial_and_temporal_gates_start_new_tracks() -> None:
    result = BaselineTracker(
        TrackingConfig(max_center_distance=0.2, max_time_gap_seconds=4.0)
    ).track(
        (_obs("a", 0), _obs("label", 3, label="man"), _obs("far", 3, x=0.8), _obs("gap", 6, x=0.1))
    )

    assert [track.observation_ids for track in result.tracks] == [
        ("a",),
        ("far",),
        ("label",),
        ("gap",),
    ]


@pytest.mark.unit
def test_survives_one_sparse_missed_sample_but_empty_and_single_inputs_are_safe() -> None:
    tracker = BaselineTracker()
    result = tracker.track((_obs("a", 0), _obs("b", 6, x=0.15)))

    assert result.tracks[0].observation_ids == ("a", "b")
    assert tracker.track(()).tracks == ()
    assert tracker.track((_obs("only", 0),)).tracks[0].observation_count == 1


@pytest.mark.unit
def test_tracking_artifact_provenance_and_reference_validation(tmp_path: Path) -> None:
    event_dir = tmp_path / "event-1"
    event_dir.mkdir()
    observations = (_obs("a", 0), _obs("b", 3))
    (event_dir / "event_perception.json").write_text(
        json.dumps(
            {
                "event_id": "event-1",
                "media": {"filename": "event.mp4", "sha256": "media-sha"},
                "observations": [item.as_dict() for item in observations],
            }
        ),
        encoding="utf-8",
    )
    result = BaselineTracker().track(observations)

    path = write_tracking_artifact(event_dir, result, observations=observations)
    assert load_track_index(path, observations) == {"a": "T001", "b": "T001"}
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["source_perception"]["media_sha256"] == "media-sha"

    payload["tracks"][0]["observation_ids"].append("missing")
    path.write_text(json.dumps(payload), encoding="utf-8")
    with pytest.raises(TrackingArtifactError, match="references are invalid"):
        load_track_index(path, observations)
