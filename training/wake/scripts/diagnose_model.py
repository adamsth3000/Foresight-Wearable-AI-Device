"""Read-only diagnostics for an existing Foresight wake-model training run."""
# ruff: noqa: E501

from __future__ import annotations

import argparse
import inspect
import json
from pathlib import Path
from typing import Any

from common import TRAINING_ROOT, add_stage_arguments, run_root
from evaluate import evaluation_inputs
from extract_features import (
    CLIP_SAMPLES,
    SAMPLE_RATE,
    _feature_model_paths,
    _fixed_length_audio,
    default_model_dir,
)
from prototype_data import file_sha256
from train import _construct_benchmark_model, _feature_array_batches

FEATURE_SPLITS = (
    "positive_train",
    "positive_validation",
    "negative_train",
    "negative_validation",
)


def score_summary(scores: Any) -> dict[str, float | int]:
    """Return JSON-safe score statistics without changing an input array."""

    import numpy as np

    values = np.asarray(scores, dtype=np.float64).reshape(-1)
    if not len(values):
        raise ValueError("Cannot summarize an empty score collection.")
    return {
        "n": int(len(values)),
        "min": float(values.min()),
        "max": float(values.max()),
        "mean": float(values.mean()),
        "median": float(np.median(values)),
        "standard_deviation": float(values.std()),
        "p01": float(np.quantile(values, 0.01)),
        "p05": float(np.quantile(values, 0.05)),
        "p25": float(np.quantile(values, 0.25)),
        "p75": float(np.quantile(values, 0.75)),
        "p95": float(np.quantile(values, 0.95)),
        "p99": float(np.quantile(values, 0.99)),
        "proportion_ge_0_5": float(np.mean(values >= 0.5)),
    }


def separation_summary(positive_scores: Any, negative_scores: Any) -> dict[str, float]:
    """Describe validation separation using a bounded, deterministic sample."""

    import numpy as np

    positives = np.asarray(positive_scores, dtype=np.float64).reshape(-1)[:512]
    negatives = np.asarray(negative_scores, dtype=np.float64).reshape(-1)[:512]
    pooled_std = float(np.sqrt((positives.var() + negatives.var()) / 2))
    comparisons = positives[:, None] - negatives[None, :]
    return {
        "mean_gap_positive_minus_negative": float(positives.mean() - negatives.mean()),
        "median_gap_positive_minus_negative": float(
            np.median(positives) - np.median(negatives)
        ),
        "cohens_d": float((positives.mean() - negatives.mean()) / pooled_std)
        if pooled_std
        else 0.0,
        "ranking_accuracy_sampled": float(
            (np.sum(comparisons > 0) + 0.5 * np.sum(comparisons == 0)) / comparisons.size
        ),
    }


def assess_root_cause(
    checkpoint_onnx: dict[str, Any],
    validation_separation: dict[str, float],
    domain_distances: dict[str, float],
) -> dict[str, object]:
    """Classify evidence conservatively; this never recommends a retraining action."""

    if checkpoint_onnx["max_absolute_difference"] > 1e-5:
        return {
            "classification": "ONNX export/output interpretation bug",
            "evidence": "Checkpoint and ONNX scores diverge on identical held-out features.",
        }
    if validation_separation["ranking_accuracy_sampled"] < 0.6:
        return {
            "classification": "insufficient optimization/training or training implementation bug",
            "evidence": (
                "The exported model does not separate its own held-out positive and negative "
                "training features, despite checkpoint/ONNX parity."
            ),
        }
    if (
        domain_distances["real_positive_to_ordinary_speech"]
        < domain_distances["real_positive_to_synthetic_positive"]
    ):
        return {
            "classification": "synthetic-to-real domain gap",
            "evidence": (
                "The model separates held-out training features and ONNX matches the checkpoint, "
                "but real positive embeddings are closer to ordinary speech than synthetic positives."
            ),
        }
    return {
        "classification": "combination",
        "evidence": (
            "Checkpoint/ONNX parity and training-feature separation hold, but the small real-audio "
            "embedding sample does not isolate one cause."
        ),
    }


def _onnx_scores(session: Any, features: Any) -> Any:
    import numpy as np

    input_name = session.get_inputs()[0].name
    batches = []
    for start in range(0, len(features), 128):
        batch = np.asarray(features[start : start + 128], dtype=np.float32)
        batches.append(session.run(None, {input_name: batch})[0].reshape(-1))
    return np.concatenate(batches)


def _frontend_features(paths: list[Path]) -> Any:
    import numpy as np
    from openwakeword.utils import AudioFeatures

    mel, embedding = _feature_model_paths(default_model_dir())
    frontend = AudioFeatures(
        melspec_model_path=str(mel),
        embedding_model_path=str(embedding),
        inference_framework="onnx",
        device="cpu",
        ncpu=1,
    )
    return frontend.embed_clips(_fixed_length_audio(paths), batch_size=len(paths), ncpu=1).astype(
        np.float32
    )


def _embedding_distribution(features: Any) -> dict[str, object]:
    import numpy as np

    values = np.asarray(features, dtype=np.float32)
    return {
        "feature_shape": list(values.shape),
        "dtype": str(values.dtype),
        "element_mean": float(values.mean()),
        "element_standard_deviation": float(values.std()),
        "centroid": values.mean(axis=0).tolist(),
    }


def _centroid_distance(left: Any, right: Any) -> float:
    import numpy as np

    return float(np.linalg.norm(np.asarray(left).mean(axis=0) - np.asarray(right).mean(axis=0)))


def _diagnostic_evaluation_records(
    profile: str, run_id: str, sample_size: int
) -> tuple[list[tuple[Path, str, bool]], dict[str, list[Path]]]:
    """Select only the profile/run evaluation inputs supplied by the shared resolver."""

    if profile == "prototype-v3":
        from field_evaluation import field_evaluation_inputs

        records = [
            (Path(str(record["path"])), str(record["category"]), bool(record["positive"]))
            for record in field_evaluation_inputs()
        ]
    else:
        records = evaluation_inputs(profile, run_id)
    selected = {
        "real_positive": [
            path for path, category, _ in records if category == "positive"
        ][:sample_size],
        "ordinary_speech": [
            path for path, category, _ in records if category == "ordinary_speech"
        ][:sample_size],
    }
    return records, selected


def _training_metadata(
    artifacts: Path, feature_paths: dict[str, Path]
) -> dict[str, object]:
    import numpy as np
    training = json.loads((artifacts / "training.json").read_text(encoding="utf-8"))
    checkpoint_config: dict[str, object]
    import torch

    checkpoint = torch.load(
        artifacts / "hey_foresight_checkpoint.pt", map_location="cpu", weights_only=False
    )
    checkpoint_config = checkpoint.get("config", {})
    positive_batch, labels = next(
        _feature_array_batches(feature_paths["positive_train"], feature_paths["negative_train"])
    )
    positive_count = int(np.load(feature_paths["positive_train"], mmap_mode="r").shape[0])
    return {
        "actual_completed_steps": training.get("steps"),
        "recorded_seconds": training.get("seconds"),
        "recorded_loss_or_metrics": "not persisted by train.py",
        "architecture": {
            "input_shape": [16, 96],
            "model_type": "dnn",
            "layer_dim": 32,
            "n_classes": 1,
        },
        "checkpoint_config": checkpoint_config,
        "batch_composition": {
            "positive_examples": int((labels == 1).sum().item()),
            "negative_examples": int((labels == 0).sum().item()),
            "feature_batch_shape": list(positive_batch.shape),
        },
        "label_encoding": {
            "positive": 1,
            "negative": 0,
            "confirmed": bool(
                (labels[:positive_count] == 1).all().item()
                and (labels[positive_count:] == 0).all().item()
            ),
            "source": "train._feature_array_batches concatenates ones for positives then zeros for negatives.",
        },
        "optimizer": "torch.optim.Adam (openwakeword.train.Model)",
        "loss_function": "binary cross entropy (openwakeword.train.Model)",
        "consumed_feature_counts": {
            name: int(np.load(path, mmap_mode="r").shape[0])
            for name, path in feature_paths.items()
        },
    }


def run_diagnostic(profile: str, run_id: str, sample_size: int) -> Path:
    """Inspect one completed run and write only its diagnostic report."""

    import numpy as np
    import onnxruntime
    import torch

    artifacts = run_root(profile, run_id) / "artifacts"
    model_path = artifacts / "hey_foresight.onnx"
    checkpoint_path = artifacts / "hey_foresight_checkpoint.pt"
    feature_root = TRAINING_ROOT / "cache" / "features" / profile / run_id
    feature_paths = {name: feature_root / f"{name}.npy" for name in FEATURE_SPLITS}
    missing = [path for path in (*feature_paths.values(), model_path, checkpoint_path) if not path.is_file()]
    if missing:
        raise RuntimeError(f"Diagnostic requires completed artifacts: {', '.join(map(str, missing))}")

    session = onnxruntime.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    feature_scores: dict[str, Any] = {}
    raw_scores: dict[str, Any] = {}
    for name, path in feature_paths.items():
        features = np.load(path, mmap_mode="r")
        if features.shape[1:] != (16, 96) or features.dtype != np.float32:
            raise RuntimeError(f"Unexpected feature format at {path}: {features.shape} {features.dtype}.")
        raw_scores[name] = _onnx_scores(session, features)
        feature_scores[name] = score_summary(raw_scores[name])

    validation_separation = separation_summary(
        raw_scores["positive_validation"], raw_scores["negative_validation"]
    )
    payload = torch.load(checkpoint_path, map_location="cpu", weights_only=False)
    checkpoint_model, removed_redirects = _construct_benchmark_model()
    checkpoint_model.model.load_state_dict(payload["state_dict"])
    checkpoint_model.model.eval()
    comparison_features = np.vstack(
        (
            np.asarray(np.load(feature_paths["positive_validation"], mmap_mode="r")[:sample_size]),
            np.asarray(np.load(feature_paths["negative_validation"], mmap_mode="r")[:sample_size]),
        )
    ).astype(np.float32)
    with torch.inference_mode():
        checkpoint_scores = checkpoint_model.model(torch.from_numpy(comparison_features)).numpy().reshape(-1)
    onnx_scores = _onnx_scores(session, comparison_features)
    difference = np.abs(checkpoint_scores - onnx_scores)
    checkpoint_onnx = {
        "sample_count": int(len(comparison_features)),
        "max_absolute_difference": float(difference.max()),
        "mean_absolute_difference": float(difference.mean()),
        "allclose_atol_1e_5": bool(np.allclose(checkpoint_scores, onnx_scores, atol=1e-5)),
        "speechbrain_lazy_redirects_removed": list(removed_redirects),
    }

    provenance_path = feature_root / "positive_feature_provenance.json"
    grouped_training_scores: dict[str, object] = {}
    if provenance_path.is_file():
        provenance = json.loads(provenance_path.read_text(encoding="utf-8"))
        for name, group in provenance["groups"].items():
            group_features = np.load(group["feature_path"], mmap_mode="r")
            grouped_training_scores[name] = score_summary(_onnx_scores(session, group_features))

    records, selected = _diagnostic_evaluation_records(profile, run_id, sample_size)
    if not all(selected.values()):
        raise RuntimeError("Diagnostic requires real positive and ordinary-speech evaluation WAVs.")
    domain_features = {
        "synthetic_positive": np.asarray(
            np.load(feature_paths["positive_validation"], mmap_mode="r")[:sample_size]
        ),
        **{name: _frontend_features(paths) for name, paths in selected.items()},
    }
    domain_distances = {
        "real_positive_to_synthetic_positive": _centroid_distance(
            domain_features["real_positive"], domain_features["synthetic_positive"]
        ),
        "real_positive_to_ordinary_speech": _centroid_distance(
            domain_features["real_positive"], domain_features["ordinary_speech"]
        ),
        "synthetic_positive_to_ordinary_speech": _centroid_distance(
            domain_features["synthetic_positive"], domain_features["ordinary_speech"]
        ),
    }
    evaluation_features = _frontend_features([path for path, _, _ in records])
    evaluation_scores = _onnx_scores(session, evaluation_features)
    held_out_scores = {
        "held_out_real_positives": score_summary(
            [score for score, (_, _, positive) in zip(evaluation_scores, records, strict=True) if positive]
        ),
        "held_out_real_negatives": score_summary(
            [score for score, (_, _, positive) in zip(evaluation_scores, records, strict=True) if not positive]
        ),
    }
    report = {
        "profile": profile,
        "run_id": run_id,
        "read_only_inputs": {
            "onnx_sha256": file_sha256(model_path),
            "checkpoint_sha256": file_sha256(checkpoint_path),
            "feature_arrays": {name: str(path) for name, path in feature_paths.items()},
        },
        "onnx_training_feature_scores": feature_scores,
        "source_group_score_distributions": grouped_training_scores,
        "held_out_real_evaluation_score_distributions": held_out_scores,
        "positive_vs_negative_validation_separation": validation_separation,
        "checkpoint_vs_onnx": checkpoint_onnx,
        "training_metadata": _training_metadata(artifacts, feature_paths),
        "output_semantics": {
            "classification": "sigmoid probability",
            "evidence": "The reconstructed openwakeword DNN ends in Sigmoid; exported ONNX scores are probabilities.",
            "model_source_contains_sigmoid": "Sigmoid" in inspect.getsource(type(checkpoint_model.model)),
        },
        "feature_pipeline_consistency": {
            "consistent": True,
            "training_and_evaluation_frontend": "openwakeword.utils.AudioFeatures with the same cached melspectrogram and embedding ONNX models",
            "sample_rate_hz": SAMPLE_RATE,
            "clip_samples": CLIP_SAMPLES,
            "clip_seconds": CLIP_SAMPLES / SAMPLE_RATE,
            "padding_and_truncation": "zero-pad or truncate to 32,000 samples, then convert float audio to int16",
            "feature_shape": [16, 96],
            "feature_dtype": "float32",
            "model_paths": [str(path) for path in _feature_model_paths(default_model_dir())],
            "evidence": "evaluate.py imports _fixed_length_audio and _feature_model_paths from extract_features.py.",
        },
        "domain_feature_comparison": {
            "sample_size_per_group": sample_size,
            "groups": {name: _embedding_distribution(values) for name, values in domain_features.items()},
            "centroid_l2_distances": domain_distances,
        },
        "root_cause_assessment": assess_root_cause(
            checkpoint_onnx, validation_separation, domain_distances
        ),
    }
    report_path = run_root(profile, run_id) / "reports" / "model_diagnostic.json"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return report_path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    parser.set_defaults(run_id="prototype-v1")
    parser.add_argument("--sample-size", type=int, default=25)
    args = parser.parse_args()
    if args.sample_size < 1:
        raise ValueError("--sample-size must be positive.")
    report = run_diagnostic(args.profile, args.run_id, args.sample_size)
    result = json.loads(report.read_text(encoding="utf-8"))
    print(f"Wrote read-only diagnostic report: {report}")
    print(f"Assessment: {result['root_cause_assessment']['classification']}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
