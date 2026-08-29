"""Run offline ONNX wake-model evaluation on available local WAV categories."""
# ruff: noqa: E501

from __future__ import annotations

import argparse
import json
from pathlib import Path

from common import (
    TRAINING_ROOT,
    add_stage_arguments,
    artifact_hash,
    begin_stage,
    finish_stage,
    manifest_path,
    run_root,
)
from extract_features import _feature_model_paths, _fixed_length_audio, default_model_dir
from prototype_data import wav_files
from real_positives import load_split

EVALUATION_CATEGORIES = {
    "positive": True,
    "ordinary_speech": False,
    "ambient": False,
    "tv_background": False,
    "noise": False,
}


def evaluation_inputs(profile: str = "prototype", run_id: str = "manual") -> list[tuple[Path, str, bool]]:
    """Return every evaluation WAV in deterministic category/path order."""

    positive_paths = wav_files(TRAINING_ROOT / "data" / "evaluation" / "positive")
    if profile == "prototype-v2":
        split_manifest = run_root(profile, run_id) / "artifacts" / "real_positive_split.json"
        positive_paths = load_split(split_manifest)["held_out"]
    negative_inputs = [
        (path, category, is_positive)
        for category, is_positive in EVALUATION_CATEGORIES.items()
        if not is_positive
        for path in wav_files(TRAINING_ROOT / "data" / "evaluation" / category)
    ]
    return [(path, "positive", True) for path in positive_paths] + negative_inputs


def require_evaluation_inputs(inputs: list[tuple[Path, str, bool]]) -> None:
    if not any(is_positive for _, _, is_positive in inputs):
        raise RuntimeError("Evaluation requires at least one WAV in data/evaluation/positive/.")
    if not any(not is_positive for _, _, is_positive in inputs):
        raise RuntimeError(
            "Evaluation requires at least one negative WAV across the four negative categories."
        )


def aggregate_scores(
    positive_scores: list[float], negative_scores: list[float]
) -> dict[str, object]:
    thresholds = [0.4, 0.45, 0.5, 0.55, 0.6]
    sweep = {
        str(value): {
            "recall": sum(score >= value for score in positive_scores) / len(positive_scores)
            if positive_scores
            else None,
            "false_activations": sum(score >= value for score in negative_scores),
        }
        for value in thresholds
    }
    return {
        "positive_count": len(positive_scores),
        "negative_count": len(negative_scores),
        "false_negatives_at_0.5": sum(score < 0.5 for score in positive_scores),
        "false_activations_at_0.5": sum(score >= 0.5 for score in negative_scores),
        "threshold_sweep": sweep,
        "recommended_threshold": "manual review required",
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    artifacts = run_root(args.profile, args.run_id) / "artifacts"
    model = artifacts / "hey_foresight.onnx"
    report = run_root(args.profile, args.run_id) / "reports" / "evaluation.json"
    if not args.execute:
        print("No evaluation ran. Add local WAVs then re-run with --execute.")
        return 0
    inputs = evaluation_inputs(args.profile, args.run_id)
    require_evaluation_inputs(inputs)
    stage_inputs = (model, *(path for path, _, _ in inputs))
    if not begin_stage("evaluate", args, inputs=stage_inputs, outputs=(report,)):
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
    session = onnxruntime.InferenceSession(str(model), providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name
    records = []
    for path, category, is_positive in inputs:
        feature = frontend.embed_clips(_fixed_length_audio([path]), batch_size=1, ncpu=1).astype(
            np.float32
        )
        score = float(session.run(None, {input_name: feature})[0].reshape(-1)[0])
        records.append(
            {"path": str(path), "category": category, "positive": is_positive, "score": score}
        )
    positives = [row["score"] for row in records if row["positive"]]
    negatives = [row["score"] for row in records if not row["positive"]]
    export_manifest = manifest_path("export", args.profile, args.run_id)
    result = {
        "records": records,
        "onnx_sha256": artifact_hash(model),
        "export_manifest_sha256": artifact_hash(export_manifest),
        **aggregate_scores(positives, negatives),
    }
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    finish_stage(
        "evaluate",
        args,
        inputs=stage_inputs,
        outputs=(report,),
        details={key: value for key, value in result.items() if key != "records"},
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
