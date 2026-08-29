"""Cheap tests for read-only wake-model diagnostic summaries."""

from __future__ import annotations

import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[2] / "training" / "wake" / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import diagnose_model  # noqa: E402
from diagnose_model import assess_root_cause, score_summary, separation_summary  # noqa: E402


def test_score_summary_reports_probability_threshold_proportion() -> None:
    summary = score_summary([0.1, 0.5, 0.9])

    assert summary["n"] == 3
    assert summary["median"] == 0.5
    assert summary["proportion_ge_0_5"] == 2 / 3


def test_assessment_identifies_checkpoint_onnx_divergence() -> None:
    assessment = assess_root_cause(
        {"max_absolute_difference": 0.1},
        {"ranking_accuracy_sampled": 1.0},
        {
            "real_positive_to_ordinary_speech": 1.0,
            "real_positive_to_synthetic_positive": 0.1,
        },
    )

    assert assessment["classification"] == "ONNX export/output interpretation bug"


def test_separation_summary_reports_perfect_ranking() -> None:
    summary = separation_summary([0.8, 0.9], [0.1, 0.2])

    assert summary["ranking_accuracy_sampled"] == 1.0


def test_diagnostic_uses_only_the_profile_run_held_out_positive_records(
    monkeypatch: object,
) -> None:
    held_out = [Path(f"positive_{index:03d}.wav") for index in range(21, 26)]
    calls: list[tuple[str, str]] = []

    def fake_evaluation_inputs(profile: str, run_id: str) -> list[tuple[Path, str, bool]]:
        calls.append((profile, run_id))
        return [
            *( (path, "positive", True) for path in held_out ),
            (Path("ordinary.wav"), "ordinary_speech", False),
        ]

    monkeypatch.setattr(diagnose_model, "evaluation_inputs", fake_evaluation_inputs)
    _, selected = diagnose_model._diagnostic_evaluation_records("prototype-v2", "prototype-v2", 25)

    assert calls == [("prototype-v2", "prototype-v2")]
    assert selected["real_positive"] == held_out


def test_diagnostic_parser_accepts_the_shared_prototype_v2_profile(
    monkeypatch: object,
) -> None:
    monkeypatch.setattr(sys, "argv", ["diagnose_model.py", "--profile", "prototype-v2"])
    parser = __import__("argparse").ArgumentParser()
    diagnose_model.add_stage_arguments(parser)

    assert parser.parse_args().profile == "prototype-v2"
