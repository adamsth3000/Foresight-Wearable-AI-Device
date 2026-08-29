"""Cheap integrity tests for the separate wake-training workspace."""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

TRAINING_SCRIPTS = Path(__file__).parents[2] / "training" / "wake" / "scripts"
sys.path.insert(0, str(TRAINING_SCRIPTS))

import common  # noqa: E402
from deploy import validate_provenance  # noqa: E402
from evaluate import require_evaluation_inputs  # noqa: E402
from record_evaluation import VALID_CATEGORIES, next_recording_path  # noqa: E402


def _manifest(profile: str = "prototype", run_id: str = "prototype-v1") -> dict[str, object]:
    return {"state": "complete", "profile": profile, "run_id": run_id, "outputs": []}


def _chain(tmp_path: Path) -> tuple[dict, dict, dict, Path]:
    checkpoint = tmp_path / "checkpoint.pt"
    model = tmp_path / "hey_foresight.onnx"
    export_path = tmp_path / "export.json"
    checkpoint.write_bytes(b"checkpoint")
    model.write_bytes(b"onnx")
    export_path.write_text("export")
    train = _manifest()
    train["outputs"] = [str(checkpoint)]
    train["output_hashes"] = {str(checkpoint): common.artifact_hash(checkpoint)}
    export = _manifest()
    export["_manifest_path"] = export_path
    export["details"] = {
        "passed": True,
        "checkpoint_sha256": common.artifact_hash(checkpoint),
        "model_sha256": common.artifact_hash(model),
    }
    evaluation = _manifest()
    evaluation["details"] = {
        "onnx_sha256": common.artifact_hash(model),
        "export_manifest_sha256": common.artifact_hash(export_path),
        "positive_count": 1,
        "negative_count": 1,
        "threshold_sweep": {"0.5": {"recall": 1.0}},
    }
    return train, export, evaluation, model


def test_completed_stage_rejects_changed_input(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(common, "OUTPUT_ROOT", tmp_path / "outputs")
    source, output = tmp_path / "input.txt", tmp_path / "output.txt"
    source.write_text("one")
    output.write_text("done")
    common.write_manifest("test", "prototype", "hash-check", "complete", (source,), (output,))
    assert common.completed_and_valid("test", "prototype", "hash-check")
    source.write_text("two")
    assert not common.completed_and_valid("test", "prototype", "hash-check")


@pytest.mark.parametrize(
    "field,value", [("passed", False), ("checkpoint_sha256", "wrong"), ("model_sha256", "wrong")]
)
def test_deploy_rejects_invalid_provenance(tmp_path: Path, field: str, value: object) -> None:
    train, export, evaluation, model = _chain(tmp_path)
    export["details"][field] = value
    with pytest.raises(ValueError):
        validate_provenance(train, export, evaluation, model)


def test_deploy_rejects_changed_export_manifest_or_profile(tmp_path: Path) -> None:
    train, export, evaluation, model = _chain(tmp_path)
    export["_manifest_path"].write_text("changed")
    with pytest.raises(ValueError):
        validate_provenance(train, export, evaluation, model)
    train, export, evaluation, model = _chain(tmp_path)
    evaluation["profile"] = "quality"
    with pytest.raises(ValueError):
        validate_provenance(train, export, evaluation, model)


def test_deploy_accepts_consistent_synthetic_chain(tmp_path: Path) -> None:
    train, export, evaluation, model = _chain(tmp_path)
    validate_provenance(train, export, evaluation, model)


def test_evaluation_requires_positive_and_negative_inputs() -> None:
    with pytest.raises(RuntimeError, match="positive"):
        require_evaluation_inputs([])
    with pytest.raises(RuntimeError, match="negative"):
        require_evaluation_inputs([(Path("positive.wav"), "positive", True)])


def test_evaluation_input_change_invalidates_completed_stage(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setattr(common, "OUTPUT_ROOT", tmp_path / "outputs")
    source, output = tmp_path / "evaluation.wav", tmp_path / "report.json"
    source.write_bytes(b"first")
    output.write_text("report")
    common.write_manifest(
        "evaluate", "prototype", "evaluation-check", "complete", (source,), (output,)
    )
    assert common.completed_and_valid("evaluate", "prototype", "evaluation-check")
    source.write_bytes(b"changed")
    assert not common.completed_and_valid("evaluate", "prototype", "evaluation-check")


def test_recorder_uses_next_filename_without_overwrite(tmp_path: Path) -> None:
    first = next_recording_path(tmp_path, "positive")
    first.write_bytes(b"wav")
    assert next_recording_path(tmp_path, "positive").name == "positive_002.wav"
    assert set(VALID_CATEGORIES) == {
        "positive",
        "ordinary_speech",
        "ambient",
        "tv_background",
        "noise",
    }


def test_deploy_rejects_empty_evaluation(tmp_path: Path) -> None:
    train, export, evaluation, model = _chain(tmp_path)
    evaluation["details"]["positive_count"] = 0
    with pytest.raises(ValueError, match="non-empty"):
        validate_provenance(train, export, evaluation, model)
