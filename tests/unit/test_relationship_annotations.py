"""Tests for stable SELF identity and append-only actor/action/target evidence."""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from foresight_device.annotation.relationships import (
    RelationshipAnnotationError,
    RelationshipAnnotationStore,
    RelationshipTarget,
)
from foresight_device.identity import SELF_ACTOR, ActorRole


@pytest.mark.unit
def test_self_is_stable_and_independent_of_detector_person_numbering(tmp_path: Path) -> None:
    store = RelationshipAnnotationStore(
        tmp_path / "relationships.json",
        event_id="event-1",
        observation_ids={"person-17", "chair-2"},
    )
    annotation = store.create(
        action="point",
        relationship="POINTS_AT",
        source_evidence_observation_ids=("person-17",),
        target=RelationshipTarget(observation_ids=("chair-2",)),
        media_timestamp_seconds=2.0,
    )

    assert annotation.actor == SELF_ACTOR
    assert annotation.actor.actor_id == "self"
    assert annotation.actor.actor_role == ActorRole.WEARER
    assert annotation.target.observation_ids == ("chair-2",)
    assert store.load() == (annotation,)


@pytest.mark.unit
def test_relationship_serialization_and_malformed_records(tmp_path: Path) -> None:
    path = tmp_path / "relationships.json"
    store = RelationshipAnnotationStore(
        path, event_id="event-1", observation_ids={"hand-1", "sign-1"}
    )
    store.create(
        action="indicate",
        relationship="INDICATES",
        source_evidence_observation_ids=("hand-1",),
        target=RelationshipTarget(observation_ids=("sign-1",)),
        media_timestamp_seconds=1.0,
    )
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["relationships"][0]["actor_id"] == "self"
    assert payload["relationships"][0]["target"]["observation_ids"] == ["sign-1"]

    path.write_text(json.dumps({"event_id": "event-1", "relationships": [{}]}), encoding="utf-8")
    with pytest.raises(RelationshipAnnotationError, match="invalid schema"):
        store.load()


@pytest.mark.unit
def test_relationship_store_rejects_unknown_observations(tmp_path: Path) -> None:
    store = RelationshipAnnotationStore(
        tmp_path / "relationships.json", event_id="event-1", observation_ids={"hand-1"}
    )
    with pytest.raises(RelationshipAnnotationError, match="unknown observation"):
        store.create(
            action="point",
            relationship="POINTS_AT",
            source_evidence_observation_ids=("hand-1",),
            target=RelationshipTarget(observation_ids=("missing",)),
            media_timestamp_seconds=1.0,
        )
