"""Cheap tests for deterministic prototype-v2 real-positive provenance."""
# ruff: noqa: E501

from __future__ import annotations

import sys
from pathlib import Path

import pytest

SCRIPTS = Path(__file__).resolve().parents[2] / "training" / "wake" / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import evaluate  # noqa: E402
from real_positives import deterministic_split, write_split_manifest  # noqa: E402


def test_deterministic_real_positive_split_uses_sorted_non_overlapping_paths(tmp_path: Path) -> None:
    paths = [tmp_path / f"positive_{index:03d}.wav" for index in range(25, 0, -1)]
    for path in paths:
        path.write_bytes(path.name.encode())

    split = deterministic_split(paths, train_count=15, validation_count=5, held_out_count=5)

    assert [path.name for path in split["train"]] == [f"positive_{index:03d}.wav" for index in range(1, 16)]
    assert [path.name for path in split["validation"]] == [
        f"positive_{index:03d}.wav" for index in range(16, 21)
    ]
    assert [path.name for path in split["held_out"]] == [
        f"positive_{index:03d}.wav" for index in range(21, 26)
    ]


def test_deterministic_real_positive_split_rejects_changed_source_count(tmp_path: Path) -> None:
    paths = [tmp_path / f"positive_{index:03d}.wav" for index in range(24)]

    with pytest.raises(RuntimeError, match="Expected exactly 25"):
        deterministic_split(paths, train_count=15, validation_count=5, held_out_count=5)


def test_v2_evaluation_uses_only_manifest_held_out_positives(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    evaluation_root = tmp_path / "data" / "evaluation"
    positive_paths = []
    for category, count in (("positive", 25), ("ordinary_speech", 1)):
        directory = evaluation_root / category
        directory.mkdir(parents=True)
        for index in range(count):
            path = directory / f"{category}_{index:03d}.wav"
            path.write_bytes(b"wav")
            if category == "positive":
                positive_paths.append(path)
    split = deterministic_split(positive_paths, train_count=15, validation_count=5, held_out_count=5)
    run_directory = tmp_path / "outputs" / "prototype-v2" / "prototype-v2"
    split_path = run_directory / "artifacts" / "real_positive_split.json"
    write_split_manifest(split_path, "prototype-v2", "prototype-v2", split)
    monkeypatch.setattr(evaluate, "TRAINING_ROOT", tmp_path)
    monkeypatch.setattr(evaluate, "run_root", lambda *_: run_directory)

    inputs = evaluate.evaluation_inputs("prototype-v2", "prototype-v2")

    positives = [path for path, _, is_positive in inputs if is_positive]
    assert positives == split["held_out"]
    assert len(inputs) == 6
