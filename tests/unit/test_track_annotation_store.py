"""Tests for event-local human labels of derived tracks."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from foresight_device.annotation.track_store import (
    TrackAnnotationStore,
    TrackAnnotationStoreError,
    latest_track_labels,
)


@pytest.mark.unit
def test_track_relabel_round_trips_as_separate_human_event_data(tmp_path: Path) -> None:
    store = TrackAnnotationStore(
        tmp_path / "event_track_annotations.json", event_id="event-1", track_ids={"T004"}
    )

    annotation = store.create_relabel(
        track_id="T004", original_track_label="person", corrected_label="mom"
    )

    assert store.load() == (annotation,)
    assert latest_track_labels(store.load()) == {"T004": "mom"}
    assert '"provenance": "human"' in (tmp_path / "event_track_annotations.json").read_text(
        encoding="utf-8"
    )


@pytest.mark.unit
def test_track_labels_are_event_local_and_unknown_tracks_are_rejected(tmp_path: Path) -> None:
    store = TrackAnnotationStore(
        tmp_path / "event_track_annotations.json", event_id="event-1", track_ids={"T002"}
    )

    with pytest.raises(TrackAnnotationStoreError, match="unknown track"):
        store.create_relabel(
            track_id="T999", original_track_label="chair", corrected_label="office chair"
        )
    annotation = store.create_relabel(
        track_id="T002", original_track_label="chair", corrected_label="office chair"
    )
    assert annotation.corrected_label == "office chair"


@pytest.mark.unit
def test_track_annotation_artifact_rejects_foreign_track_references(tmp_path: Path) -> None:
    path = tmp_path / "event_track_annotations.json"
    path.write_text(
        json.dumps(
            {
                "event_id": "event-1",
                "annotations": [
                    {
                        "annotation_id": "annotation-1",
                        "event_id": "event-1",
                        "track_id": "T999",
                        "action": "relabel_track",
                        "original_track_label": "person",
                        "corrected_label": "mom",
                        "created_at_utc": "2026-08-29T00:00:00+00:00",
                        "provenance": "human",
                    }
                ],
            }
        ),
        encoding="utf-8",
    )
    store = TrackAnnotationStore(path, event_id="event-1", track_ids={"T004"})

    with pytest.raises(TrackAnnotationStoreError, match="another event or track"):
        store.load()
