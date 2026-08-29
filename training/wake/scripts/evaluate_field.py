"""Evaluate a frozen ONNX wake model against accepted field-only recordings."""
# ruff: noqa: E501

from __future__ import annotations

import argparse
import json
from pathlib import Path

from common import (
    add_stage_arguments,
    artifact_hash,
    begin_stage,
    finish_stage,
    manifest_path,
    run_root,
)
from extract_features import _feature_model_paths, default_model_dir
from field_evaluation import (
    field_evaluation_inputs,
    fixed_window_audio,
    metadata_paths,
    require_field_evaluation_inputs,
)

THRESHOLDS = (0.10, 0.15, 0.20, 0.25, 0.30, 0.35, 0.40, 0.45, 0.50, 0.55, 0.60)


def score_summary(scores: list[float]) -> dict[str, float | None]:
    """Return compact score-distribution facts for a non-empty or empty group."""

    import numpy as np

    if not scores:
        return {key: None for key in ("min", "max", "mean", "median")}
    values = np.asarray(scores, dtype=np.float64)
    return {
        "min": float(values.min()),
        "max": float(values.max()),
        "mean": float(values.mean()),
        "median": float(np.median(values)),
    }


def threshold_sweep(positive_scores: list[float], negative_scores: list[float]) -> dict[str, dict[str, float | int | None]]:
    """Report manual-review threshold metrics without selecting a runtime threshold."""

    result: dict[str, dict[str, float | int | None]] = {}
    for threshold in THRESHOLDS:
        true_positives = sum(score >= threshold for score in positive_scores)
        false_negatives = len(positive_scores) - true_positives
        false_activations = sum(score >= threshold for score in negative_scores)
        result[f"{threshold:.2f}"] = {
            "recall": true_positives / len(positive_scores) if positive_scores else None,
            "false_negatives": false_negatives,
            "false_activations": false_activations,
            "false_positive_rate": false_activations / len(negative_scores)
            if negative_scores
            else None,
            "precision": true_positives / (true_positives + false_activations)
            if true_positives + false_activations
            else None,
        }
    return result


def grouped_summaries(records: list[dict[str, object]]) -> dict[str, dict[str, object]]:
    """Summarize scores by source device and negative category for domain comparison."""

    groups: dict[str, list[float]] = {}
    for record in records:
        score = float(record["score"])
        device_key = f"source_device:{record['source_device']}"
        groups.setdefault(device_key, []).append(score)
        if not record["positive"]:
            category_key = f"negative_category:{record['category']}"
            groups.setdefault(category_key, []).append(score)
    return {
        name: {"count": len(scores), **score_summary(scores)} for name, scores in sorted(groups.items())
    }


def build_report(records: list[dict[str, object]]) -> dict[str, object]:
    """Build the complete field report from scored records without model access."""

    positives = [float(record["score"]) for record in records if record["positive"]]
    negatives = [float(record["score"]) for record in records if not record["positive"]]
    positive_summary = score_summary(positives)
    negative_summary = score_summary(negatives)
    return {
        "records": records,
        "positive_count": len(positives),
        "negative_count": len(negatives),
        "threshold_sweep": threshold_sweep(positives, negatives),
        "positive_scores": positive_summary,
        "negative_scores": negative_summary,
        "smallest_positive_score": positive_summary["min"],
        "largest_negative_score": negative_summary["max"],
        "score_ranges_overlap": bool(
            positives
            and negatives
            and float(positive_summary["min"]) <= float(negative_summary["max"])
        ),
        "grouped_scores": grouped_summaries(records),
        "threshold_recommendation": "manual review required; runtime threshold unchanged",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if not args.execute:
        print("No field evaluation ran. Record accepted field takes, then re-run with --execute.")
        return 0
    artifacts = run_root(args.profile, args.run_id) / "artifacts"
    model_path = artifacts / "hey_foresight.onnx"
    report_path = run_root(args.profile, args.run_id) / "reports" / "field_evaluation.json"
    inputs = field_evaluation_inputs()
    require_field_evaluation_inputs(inputs)
    stage_inputs = (
        model_path,
        *metadata_paths(),
        *(Path(str(record["path"])) for record in inputs),
    )
    if not begin_stage("evaluate_field", args, inputs=stage_inputs, outputs=(report_path,)):
        return 0
    import numpy as np
    import onnxruntime
    from openwakeword.utils import AudioFeatures

    mel, embedding = _feature_model_paths(default_model_dir())
    frontend = AudioFeatures(
        melspec_model_path=str(mel),
        embedding_model_path=str(embedding),
        inference_framework="onnx",
        device="cpu",
        ncpu=1,
    )
    features = frontend.embed_clips(fixed_window_audio(inputs), batch_size=min(32, len(inputs)), ncpu=1)
    session = onnxruntime.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    scores = session.run(None, {session.get_inputs()[0].name: features.astype(np.float32)})[0].reshape(-1)
    records = [{**record, "path": str(record["path"]), "score": float(score)} for record, score in zip(inputs, scores, strict=True)]
    report = {
        "profile": args.profile,
        "run_id": args.run_id,
        "onnx_sha256": artifact_hash(model_path),
        "export_manifest_sha256": artifact_hash(manifest_path("export", args.profile, args.run_id)),
        **build_report(records),
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    finish_stage(
        "evaluate_field",
        args,
        inputs=stage_inputs,
        outputs=(report_path,),
        details={key: value for key, value in report.items() if key != "records"},
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
