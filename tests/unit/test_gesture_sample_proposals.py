"""Regression coverage for deterministic, non-persistent sample proposals."""

from __future__ import annotations

from dataclasses import replace

from foresight_device.annotation.gesture_annotations import (
    GestureAnnotation,
    GestureAnnotationLabel,
    HumanHandedness,
)
from foresight_device.body_perception.models import (
    Handedness,
    HandObservation,
    HandTrack,
    NormalizedLandmark,
)
from foresight_device.gestures.models import GestureEventCandidate, SemanticGestureEvidence
from foresight_device.gestures.sample_proposals import (
    GestureSampleProposalConfig,
    GestureSampleSuggestion,
    propose_gesture_samples,
)
from foresight_device.visualization.annotation_timeline import (
    gesture_sample_intervals,
    interval_at_x,
    timestamp_at_x,
)
from foresight_device.visualization.editor_controller import EditorController


def _observation(identifier: str, timestamp: float, *, track: str = "H001") -> HandObservation:
    del track
    return HandObservation(
        identifier,
        "event-1",
        round(timestamp * 10),
        timestamp,
        "test",
        0.9,
        Handedness.RIGHT,
        0.9,
        (NormalizedLandmark("wrist", 0.2, 0.2), NormalizedLandmark("index_tip", 0.3, 0.2)),
    )


def _tracks(*groups: tuple[str, ...]) -> tuple[HandTrack, ...]:
    return tuple(
        HandTrack(
            f"H{index:03d}",
            "event-1",
            group,
            0.0,
            10.0,
            Handedness.RIGHT,
            _observation("template", 0).self_association,
            0.9,
        )
        for index, group in enumerate(groups, 1)
    )


def test_continuous_and_briefly_missing_hands_make_one_deterministic_proposal() -> None:
    observations = (_observation("a", 1.0), _observation("b", 1.15), _observation("c", 1.60))
    config = GestureSampleProposalConfig(
        maximum_absence_gap_seconds=0.6, minimum_duration_seconds=0.3
    )

    first = propose_gesture_samples(observations, _tracks(("a", "b", "c")), config=config)
    second = propose_gesture_samples(observations, _tracks(("a", "b", "c")), config=config)

    assert len(first) == 1
    assert first == second
    assert first[0].observation_ids == ("a", "b", "c")
    assert first[0].suggested_label is GestureSampleSuggestion.UNKNOWN_GESTURE


def test_long_absence_splits_and_filters_short_or_sparse_groups() -> None:
    observations = (
        _observation("a", 1.0),
        _observation("b", 1.3),
        _observation("c", 2.5),
        _observation("d", 2.9),
        _observation("e", 5.0),
    )
    proposals = propose_gesture_samples(observations, _tracks(("a", "b", "c", "d", "e")))

    assert [(item.start_timestamp_seconds, item.end_timestamp_seconds) for item in proposals] == [
        (1.0, 1.3),
        (2.5, 2.9),
    ]


def test_simultaneous_two_hand_evidence_and_semantic_suggestions_are_conservative() -> None:
    observations = (
        _observation("left", 1.0),
        _observation("right", 1.0),
        _observation("both", 1.3),
    )
    tracks = _tracks(("left", "both"), ("right",))
    clear = _candidate("POINT", 0.9)
    proposal = propose_gesture_samples(observations, tracks, candidates=(clear,))[0]
    conflicting = propose_gesture_samples(
        observations, tracks, candidates=(clear, _candidate("OPEN_HAND", 0.8))
    )[0]

    assert proposal.hand_track_ids == ("H001", "H002")
    assert proposal.suggested_label is GestureSampleSuggestion.POINT
    assert proposal.machine_confidence == 0.9
    assert conflicting.suggested_label is GestureSampleSuggestion.UNKNOWN_GESTURE


def test_existing_human_coverage_marks_the_matching_proposal_confirmed_without_writing() -> None:
    observations = (_observation("a", 1.0), _observation("b", 1.4))
    confirmed = GestureAnnotation(
        "9f007ce3-0b09-4204-889b-6f24aec88991",
        GestureAnnotationLabel.OPEN_HAND,
        0.9,
        1.5,
        ("H001",),
        "2026-01-01T00:00:00+00:00",
        "2026-01-01T00:00:00+00:00",
    )

    proposal = propose_gesture_samples(
        observations,
        _tracks(("a", "b")),
        confirmed_annotations=(confirmed,),
    )[0]

    assert proposal.status == "confirmed"
    assert proposal.confirmed_annotation_id == confirmed.annotation_id
    interval = gesture_sample_intervals((proposal,), duration_seconds=2.0, width=100.0)[0]
    assert interval.status == "confirmed"


def test_controller_navigates_proposals_and_confirmation_is_the_only_write() -> None:
    observations = (
        _observation("a", 1.0),
        _observation("b", 1.4),
        _observation("c", 3.0),
        _observation("d", 3.4),
    )
    store = _FakeGestureStore()
    controller = EditorController(
        (),
        store,
        gesture_annotation_store=store,
        hand_observations=observations,
        hand_tracks=_tracks(("a", "b", "c", "d")),
    )

    first = controller.navigate_gesture_sample(1)
    second = controller.navigate_gesture_sample(1)

    assert first is not None and second is not None
    assert (first.start_timestamp_seconds, second.start_timestamp_seconds) == (1.0, 3.0)
    assert store.created == []
    controller.select_gesture_sample(first.proposal_id)
    controller.confirm_gesture_sample(
        gesture_label=GestureAnnotationLabel.OPEN_HAND,
        start_timestamp_seconds=first.start_timestamp_seconds,
        end_timestamp_seconds=first.end_timestamp_seconds,
        hand_track_ids=first.hand_track_ids,
        human_handedness=HumanHandedness.RIGHT,
    )
    assert len(store.created) == 1
    assert controller.gesture_sample_progress == (1, 2)


def test_scrubber_hit_testing_resize_and_boundary_override_are_deterministic() -> None:
    observations = (
        _observation("a", 1.0),
        _observation("b", 1.4),
        _observation("c", 1.2),
    )
    controller = EditorController(
        (),
        _FakeGestureStore(),
        gesture_annotation_store=_FakeGestureStore(),
        hand_observations=observations,
        hand_tracks=_tracks(("a", "b", "c")),
    )
    sample = controller.navigate_gesture_sample(1)
    assert sample is not None
    intervals = gesture_sample_intervals(
        controller.gesture_samples, duration_seconds=10.0, width=100.0
    )
    assert interval_at_x(intervals, x=12.0, width=100.0, duration_seconds=10.0) is not None
    assert interval_at_x(intervals, x=90.0, width=100.0, duration_seconds=10.0) is None
    resized = gesture_sample_intervals(
        controller.gesture_samples, duration_seconds=10.0, width=200.0
    )
    assert resized[0].start_x == intervals[0].start_x * 2
    assert timestamp_at_x(x=90.0, width=100.0, duration_seconds=10.0) == 9.0

    controller.select_gesture_sample(sample.proposal_id)
    adjusted = controller.set_selected_gesture_sample_boundary("start", 1.1)
    assert adjusted.start_timestamp_seconds == 1.1
    assert adjusted.end_timestamp_seconds == sample.end_timestamp_seconds


def test_manual_drafts_infer_one_track_and_persist_three_sequential_samples() -> None:
    observations = (
        _observation("a", 1.0),
        _observation("b", 1.4),
        _observation("c", 3.0),
        _observation("d", 3.4),
        _observation("e", 5.0),
        _observation("f", 5.4),
    )
    store = _FakeGestureStore()
    controller = EditorController(
        (),
        store,
        gesture_annotation_store=store,
        hand_observations=observations,
        hand_tracks=_tracks(("a", "b", "c", "d", "e", "f")),
    )

    for start, end in ((1.0, 1.4), (3.0, 3.4), (5.0, 5.4)):
        controller.set_manual_gesture_sample_boundary("start", start)
        draft = controller.set_manual_gesture_sample_boundary("end", end)
        assert draft.hand_track_ids == ("H001",)
        controller.confirm_manual_gesture_sample(
            gesture_label=GestureAnnotationLabel.OPEN_HAND,
            hand_track_ids=draft.hand_track_ids,
            human_handedness=HumanHandedness.RIGHT,
        )

    assert len(store.created) == 3
    assert controller.manual_gesture_sample_draft is None
    assert len([item for item in controller.gesture_samples if item.status == "confirmed"]) == 3


def test_manual_draft_without_a_proposal_can_still_confirm_with_overlapping_evidence() -> None:
    store = _FakeGestureStore()
    controller = EditorController(
        (),
        store,
        gesture_annotation_store=store,
        hand_observations=(_observation("only", 2.0),),
        hand_tracks=_tracks(("only",)),
    )
    assert controller.gesture_samples == ()

    controller.set_manual_gesture_sample_boundary("start", 1.9)
    draft = controller.set_manual_gesture_sample_boundary("end", 2.1)
    assert draft.hand_track_ids == ("H001",)
    annotation = controller.confirm_manual_gesture_sample(
        gesture_label=GestureAnnotationLabel.POINT,
        hand_track_ids=draft.hand_track_ids,
        human_handedness=HumanHandedness.RIGHT,
    )

    assert annotation.gesture_track_id in {item.proposal_id for item in controller.gesture_samples}


def test_snapshot_both_requires_and_persists_two_human_selected_tracks() -> None:
    store = _FakeGestureStore()
    controller = EditorController(
        (),
        store,
        gesture_annotation_store=store,
        hand_observations=(_observation("left", 2.0), _observation("right", 2.0)),
        hand_tracks=_tracks(("left",), ("right",)),
    )
    controller.set_manual_gesture_sample_boundary("start", 1.9)
    draft = controller.set_manual_gesture_sample_boundary("end", 2.1)
    assert draft.hand_track_ids == ()
    annotation = controller.confirm_manual_gesture_sample(
        gesture_label=GestureAnnotationLabel.SNAPSHOT,
        hand_track_ids=("H001", "H002"),
        human_handedness=HumanHandedness.BOTH,
    )

    assert annotation.human_handedness is HumanHandedness.BOTH
    assert annotation.hand_track_ids == ("H001", "H002")


def test_human_handedness_remains_authoritative_over_mixed_mediapipe_tracks() -> None:
    store = _FakeGestureStore()
    tracks = _tracks(("first",), ("second",))
    tracks = (tracks[0], replace(tracks[1], handedness=Handedness.LEFT))
    controller = EditorController(
        (),
        store,
        gesture_annotation_store=store,
        hand_observations=(_observation("first", 2.0), _observation("second", 2.0)),
        hand_tracks=tracks,
    )
    controller.set_manual_gesture_sample_boundary("start", 1.9)
    draft = controller.set_manual_gesture_sample_boundary("end", 2.1)
    annotation = controller.confirm_manual_gesture_sample(
        gesture_label=GestureAnnotationLabel.THUMBS_UP,
        hand_track_ids=("H001", "H002"),
        human_handedness=HumanHandedness.RIGHT,
    )

    assert annotation.human_handedness is HumanHandedness.RIGHT
    assert {
        item.media_pipe_handedness
        for item in controller.hand_track_choices(
            draft.start_timestamp_seconds, draft.end_timestamp_seconds
        )
    } == {"right", "left"}
    selected = controller.selected_gesture_annotation
    assert selected is not None and selected.human_handedness is HumanHandedness.RIGHT


def test_confirmed_tracks_are_unlabeled_persistent_and_replace_matching_proposals() -> None:
    store = _FakeGestureStore()
    controller = EditorController(
        (),
        store,
        gesture_annotation_store=store,
        hand_observations=(_observation("a", 1.0), _observation("b", 1.4)),
        hand_tracks=_tracks(("a", "b")),
    )
    proposal = controller.navigate_gesture_sample(1)
    assert proposal is not None
    track = controller.confirm_gesture_track(
        proposal.start_timestamp_seconds, proposal.end_timestamp_seconds
    )
    assert controller.selected_gesture_track == track
    assert len(controller.gesture_samples) == 1
    assert controller.gesture_samples[0].proposal_id == track.gesture_track_id
    assert controller.gesture_samples[0].confirmed_annotation_id is None


def test_editor_gesture_vocabulary_exposes_cut_for_human_confirmation() -> None:
    controller = EditorController(
        (), _FakeGestureStore(), gesture_annotation_store=_FakeGestureStore()
    )

    assert GestureAnnotationLabel.CUT in controller.gesture_labels


def test_saved_cut_sample_is_restored_when_its_track_is_selected() -> None:
    store = _FakeGestureStore()
    controller = EditorController(
        (),
        store,
        gesture_annotation_store=store,
        hand_observations=(_observation("a", 1.0), _observation("b", 1.4)),
        hand_tracks=_tracks(("a", "b")),
    )
    proposal = controller.navigate_gesture_sample(1)
    assert proposal is not None
    controller.confirm_gesture_track(
        proposal.start_timestamp_seconds, proposal.end_timestamp_seconds
    )
    annotation = controller.confirm_gesture_sample(
        gesture_label=GestureAnnotationLabel.CUT,
        hand_track_ids=("H001",),
        human_handedness=HumanHandedness.RIGHT,
    )

    selected = controller.select_gesture_sample(annotation.gesture_track_id or "")
    assert selected is not None
    assert controller.selected_gesture_annotation is not None
    assert controller.selected_gesture_annotation.gesture_label is GestureAnnotationLabel.CUT


def test_semantic_confirmation_message_is_set_after_save_and_cleared_by_edit() -> None:
    store = _FakeGestureStore()
    controller = EditorController(
        (),
        store,
        gesture_annotation_store=store,
        hand_observations=(_observation("a", 1.0), _observation("b", 1.4)),
        hand_tracks=_tracks(("a", "b")),
    )
    proposal = controller.navigate_gesture_sample(1)
    assert proposal is not None
    controller.confirm_gesture_track(
        proposal.start_timestamp_seconds, proposal.end_timestamp_seconds
    )
    controller.set_semantic_gesture_label(GestureAnnotationLabel.POINT)
    controller.set_semantic_handedness(HumanHandedness.RIGHT)
    assert controller.semantic_confirmation_message is None

    controller.confirm_gesture_sample(
        gesture_label=GestureAnnotationLabel.POINT,
        hand_track_ids=(),
        human_handedness=HumanHandedness.RIGHT,
    )
    assert controller.semantic_confirmation_message == "Sample confirmed: POINT / RIGHT"

    controller.set_semantic_gesture_label(GestureAnnotationLabel.CUT)
    assert controller.semantic_confirmation_message is None


def test_switching_tracks_clears_transient_confirmation_message() -> None:
    store = _FakeGestureStore()
    controller = EditorController(
        (),
        store,
        gesture_annotation_store=store,
        hand_observations=(
            _observation("a", 1.0),
            _observation("b", 1.4),
            _observation("c", 3.0),
            _observation("d", 3.4),
        ),
        hand_tracks=_tracks(("a", "b"), ("c", "d")),
    )
    first = controller.navigate_gesture_sample(1)
    assert first is not None
    controller.confirm_gesture_track(first.start_timestamp_seconds, first.end_timestamp_seconds)
    controller.confirm_gesture_sample(
        gesture_label=GestureAnnotationLabel.POINT,
        hand_track_ids=(),
        human_handedness=HumanHandedness.RIGHT,
    )
    assert controller.semantic_confirmation_message is not None

    assert controller.navigate_gesture_sample(1) is not None
    assert controller.semantic_confirmation_message is None


def test_semantic_draft_survives_refresh_and_requires_complete_human_choices() -> None:
    store = _FakeGestureStore()
    controller = EditorController(
        (),
        store,
        gesture_annotation_store=store,
        hand_observations=(_observation("a", 1.0), _observation("b", 1.4)),
        hand_tracks=_tracks(("a", "b")),
    )
    proposal = controller.navigate_gesture_sample(1)
    assert proposal is not None
    controller.confirm_gesture_track(
        proposal.start_timestamp_seconds, proposal.end_timestamp_seconds
    )

    assert controller.semantic_confirmation_reason() == "Select a gesture"
    controller.set_semantic_gesture_label(GestureAnnotationLabel.THUMBS_UP)
    controller.set_semantic_handedness(HumanHandedness.RIGHT)
    assert controller.semantic_confirmation_reason() is None
    assert controller.semantic_gesture_draft is not None
    assert controller.semantic_gesture_draft.gesture_label is GestureAnnotationLabel.THUMBS_UP


def test_semantic_drafts_are_owned_by_track_across_navigation() -> None:
    store = _FakeGestureStore()
    controller = EditorController(
        (),
        store,
        gesture_annotation_store=store,
        hand_observations=(
            _observation("a", 1.0),
            _observation("b", 1.4),
            _observation("c", 3.0),
            _observation("d", 3.4),
        ),
        hand_tracks=_tracks(("a", "b"), ("c", "d")),
    )
    first = controller.navigate_gesture_sample(1)
    assert first is not None
    first_track = controller.confirm_gesture_track(
        first.start_timestamp_seconds, first.end_timestamp_seconds
    )
    controller.set_semantic_gesture_label(GestureAnnotationLabel.THUMBS_UP)
    controller.set_semantic_handedness(HumanHandedness.RIGHT)

    second = controller.navigate_gesture_sample(1)
    assert second is not None
    second_track = controller.confirm_gesture_track(
        second.start_timestamp_seconds, second.end_timestamp_seconds
    )
    assert controller.semantic_gesture_draft is not None
    assert controller.semantic_gesture_draft.gesture_label is None

    controller.select_gesture_sample(first_track.gesture_track_id)
    assert controller.semantic_gesture_draft is not None
    assert controller.semantic_gesture_draft.gesture_label is GestureAnnotationLabel.THUMBS_UP
    controller.select_gesture_sample(second_track.gesture_track_id)
    assert controller.semantic_gesture_draft is not None
    assert controller.semantic_gesture_draft.gesture_label is None


def test_both_semantic_draft_allows_optional_supporting_tracks() -> None:
    store = _FakeGestureStore()
    controller = EditorController(
        (),
        store,
        gesture_annotation_store=store,
        hand_observations=(_observation("a", 1.0), _observation("b", 1.4)),
        hand_tracks=_tracks(("a",), ("b",)),
    )
    proposal = controller.navigate_gesture_sample(1)
    assert proposal is not None
    controller.confirm_gesture_track(
        proposal.start_timestamp_seconds, proposal.end_timestamp_seconds
    )
    controller.set_semantic_gesture_label(GestureAnnotationLabel.SNAPSHOT)
    controller.set_semantic_handedness(HumanHandedness.BOTH)
    controller.set_semantic_hand_tracks(())
    assert controller.semantic_confirmation_reason() is None
    controller.set_semantic_hand_tracks(("H001",))
    assert controller.semantic_confirmation_reason() is None
    controller.set_semantic_hand_tracks(("H001", "H002"))
    assert controller.semantic_confirmation_reason() is None


def _candidate(gesture_type: str, confidence: float) -> GestureEventCandidate:
    return GestureEventCandidate(
        f"candidate-{gesture_type}",
        "event-1",
        "H001",
        ("left", "both"),
        1.0,
        1.3,
        1.1,
        gesture_type,
        confidence,
        confidence,
        _observation("template", 0).self_association.status,
        None,
        None,
        SemanticGestureEvidence(2, 0.3, confidence, "both", 0.1, None, (), (), ("test",)),
    )


class _FakeGestureStore:
    event_id = "event-1"

    def __init__(self) -> None:
        self.created: list[GestureAnnotation] = []
        self.tracks = []

    def load(self) -> tuple[GestureAnnotation, ...]:
        return tuple(self.created)

    def create(self, **kwargs: object) -> GestureAnnotation:
        annotation = GestureAnnotation(
            f"9f007ce3-0b09-4204-889b-6f24aec8899{len(self.created)}",
            kwargs["gesture_label"],  # type: ignore[arg-type]
            kwargs["start_timestamp_seconds"],  # type: ignore[arg-type]
            kwargs["end_timestamp_seconds"],  # type: ignore[arg-type]
            kwargs["hand_track_ids"],  # type: ignore[arg-type]
            "2026-01-01T00:00:00+00:00",
            "2026-01-01T00:00:00+00:00",
            human_handedness=kwargs["human_handedness"],  # type: ignore[arg-type]
            gesture_track_id=kwargs.get("gesture_track_id"),  # type: ignore[arg-type]
        )
        self.created.append(annotation)
        return annotation

    def load_tracks(self) -> tuple[object, ...]:
        return tuple(self.tracks)

    def create_track(self, **kwargs: object) -> object:
        from foresight_device.annotation.gesture_annotations import GestureTrack

        track = GestureTrack(
            f"8f007ce3-0b09-4204-889b-6f24aec8899{len(self.tracks)}",
            kwargs["start_timestamp_seconds"],  # type: ignore[arg-type]
            kwargs["end_timestamp_seconds"],  # type: ignore[arg-type]
        )
        self.tracks.append(track)
        return track

    def update(self, annotation_id: str, **kwargs: object) -> GestureAnnotation:
        del annotation_id, kwargs
        raise AssertionError("the first confirmation must create exactly one human annotation")

    def observations_for(self, annotation: GestureAnnotation) -> tuple[HandObservation, ...]:
        del annotation
        return ()
