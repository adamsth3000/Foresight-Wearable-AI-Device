"""Tests for immutable-observation human correction persistence."""

from __future__ import annotations

from pathlib import Path

import pytest

from foresight_device.annotation.models import AnnotationAction
from foresight_device.annotation.store import AnnotationStore, AnnotationStoreError


@pytest.mark.unit
def test_annotation_creation_round_trips_without_observation_mutation(tmp_path: Path) -> None:
    path = tmp_path / "event_annotations.json"
    store = AnnotationStore(path, event_id="event-1", observation_ids={"obs-1"})

    annotation = store.create(
        observation_id="obs-1",
        media_timestamp_seconds=1.25,
        action=AnnotationAction.RELABEL,
        original_label="tree",
        corrected_label="sign",
        notes="human correction",
    )

    loaded = store.load()
    assert loaded == (annotation,)
    assert loaded[0].validated is False
    assert loaded[0].rejected is False
    assert '"corrected_label": "sign"' in path.read_text(encoding="utf-8")


@pytest.mark.unit
def test_validation_rejection_and_unknown_observation_rules(tmp_path: Path) -> None:
    store = AnnotationStore(
        tmp_path / "annotations.json", event_id="event-1", observation_ids={"obs-1"}
    )
    validated = store.create(
        observation_id="obs-1", media_timestamp_seconds=0.0, action=AnnotationAction.VALIDATE
    )
    rejected = store.create(
        observation_id="obs-1", media_timestamp_seconds=0.0, action=AnnotationAction.REJECT
    )

    assert validated.validated is True
    assert rejected.rejected is True
    with pytest.raises(AnnotationStoreError, match="unknown observation"):
        store.create(
            observation_id="missing", media_timestamp_seconds=0.0, action=AnnotationAction.REJECT
        )
