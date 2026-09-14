"""Tests for human track-level gesture ground-truth artifacts."""

from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from foresight_device.annotation.gesture_annotations import (
    GestureAnnotationError,
    GestureAnnotationLabel,
    GestureAnnotationStore,
    HumanHandedness,
)
from foresight_device.body_perception.artifact import load_body_artifact
from foresight_device.perception.event_media import ResolvedEventMedia
from foresight_device.visualization.annotation_timeline import gesture_annotation_intervals

EVENT_ID = "31c0c4d2-8f93-4b27-86ae-64c82a70678e"


def test_round_trip_single_and_two_hand_annotations_with_observation_selection(
    tmp_path: Path,
) -> None:
    store = _store(tmp_path)
    single = store.create(
        gesture_label=GestureAnnotationLabel.OPEN_HAND,
        start_timestamp_seconds=1.0,
        end_timestamp_seconds=2.0,
        hand_track_ids=("H001",),
        human_handedness=HumanHandedness.RIGHT,
    )
    two_handed = store.create(
        gesture_label=GestureAnnotationLabel.SNAPSHOT,
        start_timestamp_seconds=2.0,
        end_timestamp_seconds=4.0,
        hand_track_ids=("H001", "H002"),
        human_handedness=HumanHandedness.BOTH,
    )

    assert [item.annotation_id for item in store.load()] == [
        single.annotation_id,
        two_handed.annotation_id,
    ]
    assert [item.human_handedness for item in store.load()] == [
        HumanHandedness.RIGHT,
        HumanHandedness.BOTH,
    ]
    assert [item.hand_observation_id for item in store.observations_for(two_handed)] == [
        "hand-2",
        "hand-3",
    ]
    assert {item.value for item in store.vocabulary} == {
        "OPEN_HAND",
        "CLOSED_HAND",
        "POINT",
        "EXPAND_FINGERS",
        "PINCH_FINGERS",
        "TAKE_PICTURE",
        "QUOTATION_MARKS",
        "FS_WAKE",
        "SNAPSHOT",
        "THUMBS_UP",
        "CUT",
    }


@pytest.mark.parametrize(
    ("start", "end", "tracks", "message"),
    [
        (2.0, 2.0, ("H001",), "start must precede"),
        (0.0, 11.0, ("H001",), "outside media duration"),
        (1.0, 2.0, ("H001", "H001"), "unique hand tracks"),
        (1.0, 2.0, ("missing",), "unknown hand track"),
    ],
)
def test_rejects_invalid_annotation_intervals_and_tracks(
    tmp_path: Path, start: float, end: float, tracks: tuple[str, ...], message: str
) -> None:
    with pytest.raises(GestureAnnotationError, match=message):
        _store(tmp_path).create(
            gesture_label=GestureAnnotationLabel.POINT,
            start_timestamp_seconds=start,
            end_timestamp_seconds=end,
            hand_track_ids=tracks,
            human_handedness=HumanHandedness.RIGHT,
        )


def test_rejects_stale_body_or_authoritative_media_provenance(tmp_path: Path) -> None:
    store = _store(tmp_path)
    store.create(
        gesture_label=GestureAnnotationLabel.PINCH_FINGERS,
        start_timestamp_seconds=1.0,
        end_timestamp_seconds=2.0,
        hand_track_ids=("H001",),
        human_handedness=HumanHandedness.RIGHT,
    )
    body_path = tmp_path / "event_body_perception.json"
    body_path.write_text(
        body_path.read_text(encoding="utf-8") + "\n",
        encoding="utf-8",
    )
    with pytest.raises(GestureAnnotationError, match="stale for body"):
        store.load()

    store = _store(tmp_path / "media-stale")
    store.create(
        gesture_label=GestureAnnotationLabel.THUMBS_UP,
        start_timestamp_seconds=1.0,
        end_timestamp_seconds=2.0,
        hand_track_ids=("H001",),
        human_handedness=HumanHandedness.RIGHT,
    )
    stale = _store(tmp_path / "media-stale", media_sha256="b" * 64)
    with pytest.raises(GestureAnnotationError, match="stale for authoritative media"):
        stale.load()


def test_orange_timeline_intervals_cover_complete_annotations_and_overlap(tmp_path: Path) -> None:
    store = _store(tmp_path)
    first = store.create(
        gesture_label=GestureAnnotationLabel.OPEN_HAND,
        start_timestamp_seconds=1.0,
        end_timestamp_seconds=4.0,
        hand_track_ids=("H001",),
        human_handedness=HumanHandedness.RIGHT,
    )
    second = store.create(
        gesture_label=GestureAnnotationLabel.SNAPSHOT,
        start_timestamp_seconds=3.0,
        end_timestamp_seconds=5.0,
        hand_track_ids=("H001", "H002"),
        human_handedness=HumanHandedness.BOTH,
    )
    intervals = gesture_annotation_intervals(store.load(), duration_seconds=10.0, width=100.0)

    assert [(item.annotation_id, item.start_x, item.end_x, item.color) for item in intervals] == [
        (first.annotation_id, 10.0, 40.0, "orange"),
        (second.annotation_id, 30.0, 50.0, "orange"),
    ]
    assert store.delete(first.annotation_id) is True
    assert [item.annotation_id for item in store.load()] == [second.annotation_id]


def test_both_handedness_without_tracks_is_valid_and_legacy_unset_is_preserved(
    tmp_path: Path,
) -> None:
    store = _store(tmp_path)
    annotation = store.create(
        gesture_label=GestureAnnotationLabel.SNAPSHOT,
        start_timestamp_seconds=1.0,
        end_timestamp_seconds=2.0,
        hand_track_ids=(),
        human_handedness=HumanHandedness.BOTH,
    )
    assert annotation.hand_track_ids == ()
    assert store.observations_for(annotation) == ()

    legacy = {
        "schema_version": 1,
        "event_id": EVENT_ID,
        "source_body_perception": {
            "filename": "event_body_perception.json",
            "sha256": hashlib.sha256(
                (tmp_path / "event_body_perception.json").read_bytes()
            ).hexdigest(),
        },
        "source_media": {
            "filename": "phone_media/authoritative.mp4",
            "sha256": store._media.sha256,
        },
        "annotations": [
            {
                "annotation_id": "9f007ce3-0b09-4204-889b-6f24aec88991",
                "gesture_label": "POINT",
                "start_timestamp_seconds": 1.0,
                "end_timestamp_seconds": 2.0,
                "hand_track_ids": ["H001"],
                "source": "human",
                "status": "confirmed",
                "created_at_utc": "2026-01-01T00:00:00+00:00",
                "updated_at_utc": "2026-01-01T00:00:00+00:00",
            }
        ],
    }
    (tmp_path / "event_gesture_annotations.json").write_text(json.dumps(legacy), encoding="utf-8")

    assert store.load()[0].human_handedness is None


def test_left_handed_round_trip_and_invalid_handedness_are_explicit(tmp_path: Path) -> None:
    store = _store(tmp_path)
    store.create(
        gesture_label=GestureAnnotationLabel.THUMBS_UP,
        start_timestamp_seconds=3.0,
        end_timestamp_seconds=4.0,
        hand_track_ids=("H002",),
        human_handedness=HumanHandedness.LEFT,
    )
    assert store.load()[0].human_handedness is HumanHandedness.LEFT
    with pytest.raises(GestureAnnotationError, match="handedness"):
        store.create(
            gesture_label=GestureAnnotationLabel.POINT,
            start_timestamp_seconds=4.0,
            end_timestamp_seconds=5.0,
            hand_track_ids=("H001",),
            human_handedness="UP",  # type: ignore[arg-type]
        )


def test_confirmed_track_can_be_unlabeled_and_sample_attaches_without_changing_bounds(
    tmp_path: Path,
) -> None:
    store = _store(tmp_path)
    track = store.create_track(start_timestamp_seconds=1.0, end_timestamp_seconds=2.0)
    assert store.load_tracks() == (track,)
    annotation = store.create(
        gesture_label=GestureAnnotationLabel.POINT,
        start_timestamp_seconds=1.0,
        end_timestamp_seconds=2.0,
        hand_track_ids=("H001",),
        human_handedness=HumanHandedness.RIGHT,
        gesture_track_id=track.gesture_track_id,
    )
    assert annotation.gesture_track_id == track.gesture_track_id
    assert store.load_tracks()[0] == track


def test_cut_is_a_round_trip_human_annotation_label(tmp_path: Path) -> None:
    store = _store(tmp_path)
    annotation = store.create(
        gesture_label=GestureAnnotationLabel.CUT,
        start_timestamp_seconds=1.0,
        end_timestamp_seconds=2.0,
        hand_track_ids=("H001",),
        human_handedness=HumanHandedness.LEFT,
    )

    assert annotation.gesture_label is GestureAnnotationLabel.CUT
    assert store.load()[0].gesture_label is GestureAnnotationLabel.CUT
    assert GestureAnnotationLabel("CUT") is GestureAnnotationLabel.CUT


def test_delete_sample_preserves_track_and_delete_track_removes_its_sample(tmp_path: Path) -> None:
    store = _store(tmp_path)
    track = store.create_track(start_timestamp_seconds=1.0, end_timestamp_seconds=2.0)
    annotation = store.create(
        gesture_label=GestureAnnotationLabel.OPEN_HAND,
        start_timestamp_seconds=1.0,
        end_timestamp_seconds=2.0,
        hand_track_ids=("H001",),
        human_handedness=HumanHandedness.RIGHT,
        gesture_track_id=track.gesture_track_id,
    )
    assert store.delete(annotation.annotation_id)
    assert store.load_tracks() == (track,)
    replacement = store.create(
        gesture_label=GestureAnnotationLabel.OPEN_HAND,
        start_timestamp_seconds=1.0,
        end_timestamp_seconds=2.0,
        hand_track_ids=("H001",),
        human_handedness=HumanHandedness.RIGHT,
        gesture_track_id=track.gesture_track_id,
    )
    assert store.delete_track(track.gesture_track_id)
    assert store.load_tracks() == ()
    assert replacement.annotation_id not in {item.annotation_id for item in store.load()}


def _store(root: Path, *, media_sha256: str | None = None) -> GestureAnnotationStore:
    root.mkdir(parents=True, exist_ok=True)
    body_path = root / "event_body_perception.json"
    body_path.write_text(json.dumps(_body_payload()), encoding="utf-8")
    body = load_body_artifact(body_path, event_id=EVENT_ID)
    media = ResolvedEventMedia(
        EVENT_ID,
        root / "authoritative.mp4",
        "phone_media/authoritative.mp4",
        media_sha256 or hashlib.sha256(b"media").hexdigest(),
        "phone_local",
    )
    return GestureAnnotationStore(
        root / "event_gesture_annotations.json",
        event_id=EVENT_ID,
        body_path=body_path,
        body=body,
        media=media,
        media_duration_seconds=10.0,
    )


def _body_payload() -> dict[str, object]:
    return {
        "schema_version": 1,
        "event_id": EVENT_ID,
        "source_media_sha256": "a" * 64,
        "provider": "fixture",
        "configuration": {"sampling_interval_seconds": 0.2},
        "hand_observations": [
            _observation("hand-1", 0.5),
            _observation("hand-2", 2.5),
            _observation("hand-3", 3.5),
        ],
        "hand_tracks": [
            _track("H001", ["hand-1", "hand-2"], 0.5, 2.5),
            _track("H002", ["hand-3"], 3.5, 3.5),
        ],
    }


def _observation(identifier: str, timestamp: float) -> dict[str, object]:
    return {
        "hand_observation_id": identifier,
        "event_id": EVENT_ID,
        "frame_index": int(timestamp * 10),
        "media_timestamp_seconds": timestamp,
        "provider": "fixture",
        "confidence": 0.9,
        "handedness": "right",
        "handedness_confidence": 0.8,
        "landmarks": [
            {"name": "wrist", "x": 0.2, "y": 0.3},
            {"name": "index_tip", "x": 0.3, "y": 0.2},
        ],
    }


def _track(identifier: str, observations: list[str], start: float, end: float) -> dict[str, object]:
    return {
        "hand_track_id": identifier,
        "observation_ids": observations,
        "start_timestamp_seconds": start,
        "end_timestamp_seconds": end,
        "handedness": "right",
        "mean_confidence": 0.9,
    }
