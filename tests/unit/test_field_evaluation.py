"""Cheap safeguards for the isolated field-evaluation workflow."""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import yaml

SCRIPTS = Path(__file__).resolve().parents[2] / "training" / "wake" / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import field_evaluation  # noqa: E402
import prepare_real_positives  # noqa: E402
import prepare_real_positives_v3  # noqa: E402
from evaluate_field import build_report  # noqa: E402


def test_field_inputs_include_only_accepted_metadata_records(tmp_path: Path) -> None:
    positive_dir = tmp_path / "positive"
    negative_dir = tmp_path / "ordinary_speech"
    accepted = positive_dir / "positive_001.wav"
    accepted.parent.mkdir(parents=True)
    accepted.write_bytes(b"wav")
    negative = negative_dir / "ordinary_speech_001.wav"
    negative.parent.mkdir()
    negative.write_bytes(b"wav")
    field_evaluation.append_metadata(
        positive_dir,
        {
            "path": str(accepted),
            "category": "positive",
            "status": "accepted",
            "source_device": "laptop_mic",
        },
    )
    field_evaluation.append_metadata(
        positive_dir,
        {
            "path": None,
            "category": "positive",
            "status": "rejected",
            "source_device": "laptop_mic",
        },
    )
    field_evaluation.append_metadata(
        negative_dir,
        {
            "path": str(negative),
            "category": "ordinary_speech",
            "status": "accepted",
            "source_device": "laptop_mic",
        },
    )

    inputs = field_evaluation.field_evaluation_inputs(tmp_path)

    assert [record["path"] for record in inputs] == [negative, accepted]
    assert [record["positive"] for record in inputs] == [False, True]


def test_training_split_source_excludes_field_evaluation_root(
    tmp_path: Path, monkeypatch: object
) -> None:
    legacy = tmp_path / "data" / "evaluation" / "positive"
    field = tmp_path / "data" / "field_evaluation" / "positive"
    legacy.mkdir(parents=True)
    field.mkdir(parents=True)
    (legacy / "legacy.wav").write_bytes(b"legacy")
    (field / "field.wav").write_bytes(b"field")
    monkeypatch.setattr(prepare_real_positives, "TRAINING_ROOT", tmp_path)

    recordings = prepare_real_positives.training_positive_recordings()

    assert recordings == [legacy / "legacy.wav"]


def test_v3_real_positive_collection_root_is_not_field_evaluation(monkeypatch: object) -> None:
    collection_root = Path("training/wake/data/real_positive_v3")
    field_root = Path("training/wake/data/field_evaluation")

    assert collection_root != field_root
    assert "field_evaluation" not in str(prepare_real_positives_v3.v3_real_positive_root())


def test_v3_profile_matches_completed_real_positive_collection() -> None:
    config_path = SCRIPTS.parent / "config" / "prototype-v3.yaml"
    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))

    assert config["real_positive_train_samples"] == 100
    assert config["real_positive_validation_samples"] == 20
    assert config["positive_train_samples"] == 2700
    assert config["positive_validation_samples"] == 330


def test_v3_recorder_cli_help_executes_without_microphone_access() -> None:
    result = subprocess.run(
        [sys.executable, str(SCRIPTS / "record_real_positives_v3.py"), "--help"],
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0
    assert "{train,validation}" in result.stdout


def test_field_report_contains_full_manual_threshold_metrics_and_device_groups() -> None:
    report = build_report(
        [
            {
                "score": 0.4,
                "positive": True,
                "category": "positive",
                "source_device": "laptop_mic",
            },
            {
                "score": 0.2,
                "positive": False,
                "category": "ordinary_speech",
                "source_device": "laptop_mic",
            },
        ]
    )

    assert report["threshold_sweep"]["0.10"] == {
        "recall": 1.0,
        "false_negatives": 0,
        "false_activations": 1,
        "false_positive_rate": 1.0,
        "precision": 0.5,
    }
    assert report["threshold_sweep"]["0.50"]["recall"] == 0.0
    assert report["score_ranges_overlap"] is False
    assert "source_device:laptop_mic" in report["grouped_scores"]
    assert "negative_category:ordinary_speech" in report["grouped_scores"]
