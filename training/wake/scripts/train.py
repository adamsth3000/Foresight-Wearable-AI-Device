"""Run a tiny real openWakeWord DNN training benchmark without model export."""
# ruff: noqa: E501

from __future__ import annotations

import argparse
import json
import time
import tracemalloc
from pathlib import Path

import yaml
from common import (
    TRAINING_ROOT,
    add_stage_arguments,
    begin_stage,
    finish_stage,
    run_root,
    write_manifest,
)
from prototype_data import file_sha256, validate_features
from training_compat import (
    apply_scipy_acoustics_compatibility,
    remove_unused_speechbrain_lazy_redirects,
)

BENCHMARK_BATCH_PER_CLASS = 64


def _prepare_benchmark_features(positive_path: Path, output_dir: Path) -> tuple[Path, Path]:
    """Tile real positives and create clearly labeled synthetic benchmark negatives."""

    import numpy as np

    positives = np.load(positive_path)
    if positives.ndim != 3 or tuple(positives.shape[1:]) != (16, 96):
        raise RuntimeError(f"{positive_path} must have shape (N, 16, 96), not {positives.shape}.")
    if positives.shape[0] == 0:
        raise RuntimeError(f"{positive_path} contains no positive feature samples.")
    output_dir.mkdir(parents=True, exist_ok=True)
    positive_output = output_dir / "benchmark_positive_features.npy"
    negative_output = output_dir / "benchmark_negative_features_benchmark_only.npy"
    repeat_count = (BENCHMARK_BATCH_PER_CLASS + positives.shape[0] - 1) // positives.shape[0]
    tiled_positives = np.tile(positives, (repeat_count, 1, 1))[:BENCHMARK_BATCH_PER_CLASS]
    tiled_positives = tiled_positives.astype(np.float32)
    generator = np.random.default_rng(20260826)
    negatives = generator.normal(
        loc=float(positives.mean()),
        scale=max(float(positives.std()), 0.001),
        size=tiled_positives.shape,
    ).astype(np.float32)
    np.save(positive_output, tiled_positives)
    np.save(negative_output, negatives)
    return positive_output, negative_output


def _feature_array_batches(positive_path: Path, negative_path: Path):
    """Yield the same balanced feature/label batches used by the upstream generator."""

    import numpy as np
    import torch

    positives = np.load(positive_path, mmap_mode="r")
    negatives = np.load(negative_path, mmap_mode="r")
    while True:
        features = np.vstack((positives, negatives)).astype(np.float32, copy=False)
        labels = np.concatenate(
            (
                np.ones(positives.shape[0], dtype=np.int64),
                np.zeros(negatives.shape[0], dtype=np.int64),
            )
        )
        yield torch.from_numpy(features), torch.from_numpy(labels)


def _construct_benchmark_model():
    """Import and construct the upstream DNN without optional SpeechBrain integrations."""

    apply_scipy_acoustics_compatibility()
    from openwakeword.train import Model

    removed_redirects = remove_unused_speechbrain_lazy_redirects()
    model = Model(n_classes=1, input_shape=(16, 96), model_type="dnn", layer_dim=32)
    return model, removed_redirects


def measure_tiny_training(
    positive_feature_path: Path,
    output_dir: Path,
    steps: int,
) -> dict[str, float | int | str | bool | list[str]]:
    """Train the upstream binary DNN briefly against real positives and tiny synthetic negatives."""

    if not 10 <= steps <= 50:
        raise ValueError("Tiny training steps must be between 10 and 50.")
    if not positive_feature_path.is_file():
        raise RuntimeError(f"Positive feature file does not exist: {positive_feature_path}")

    tracemalloc.start()
    preparation_started = time.perf_counter()
    positive_output, negative_output = _prepare_benchmark_features(
        positive_feature_path, output_dir
    )
    model, removed_redirects = _construct_benchmark_model()
    data_loader = _feature_array_batches(positive_output, negative_output)
    startup_seconds = time.perf_counter() - preparation_started
    training_started = time.perf_counter()
    model.train_model(
        data_loader,
        max_steps=steps,
        warmup_steps=1,
        hold_steps=0,
        negative_weight_schedule=[1],
        val_steps=[],
    )
    training_seconds = max(time.perf_counter() - training_started, 0.000001)
    _, peak_memory = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    steps_per_second = steps / training_seconds
    return {
        "actual_training_steps": steps,
        "startup_and_dataset_seconds": startup_seconds,
        "training_seconds": training_seconds,
        "seconds_per_step": training_seconds / steps,
        "steps_per_second": steps_per_second,
        "peak_python_tracemalloc_bytes": peak_memory,
        "positive_feature_input": str(positive_feature_path),
        "benchmark_positive_features": str(positive_output),
        "benchmark_negative_features": str(negative_output),
        "benchmark_feature_shape": "[64, 16, 96] per class",
        "speechbrain_lazy_redirects_removed": list(removed_redirects),
        "benchmark_negative_warning": (
            "Synthetic benchmark-only negatives are not suitable for a prototype or quality model."
        ),
        "checkpoint_produced": False,
        "model_output_bytes": 0,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--training-config", help="Explicit resolved openWakeWord config path.")
    parser.add_argument("--preflight-imports", action="store_true")
    args = parser.parse_args()
    if args.preflight_imports:
        applied = apply_scipy_acoustics_compatibility()
        from openwakeword.data import mmap_batch_generator

        model, removed_redirects = _construct_benchmark_model()

        print(f"SciPy acoustics compatibility alias applied: {applied}")
        print(f"mmap_batch_generator import resolved: {mmap_batch_generator.__module__}")
        print(f"Removed optional SpeechBrain lazy redirects: {list(removed_redirects)}")
        print(f"DNN model construction succeeded: {type(model).__name__}")
        return 0
    config = yaml.safe_load((TRAINING_ROOT / "config" / f"{args.profile}.yaml").read_text())
    output_dir = run_root(args.profile, args.run_id) / "artifacts"
    checkpoint = output_dir / "hey_foresight_checkpoint.pt"
    if args.execute:
        features = TRAINING_ROOT / "cache" / "features" / args.profile / args.run_id
        positive = features / "positive_train.npy"
        negative = features / "negative_train.npy"
        validation_positive = features / "positive_validation.npy"
        validation_negative = features / "negative_validation.npy"
        blocked = [path for path in (positive, negative) if "benchmark" in str(path).lower()]
        if blocked or "benchmark_negative_features_benchmark_only" in str(negative):
            raise RuntimeError("Real training refuses benchmark-only negative artifacts.")
        for path, count in (
            (positive, config.get("positive_train_samples", config["n_samples"])),
            (negative, config["negative_train_samples"]),
            (validation_positive, config.get("positive_validation_samples", config["n_samples_val"])),
            (validation_negative, config["negative_validation_samples"]),
        ):
            validate_features(path, count)
        provenance = features / "positive_feature_provenance.json"
        stage_inputs = (positive, negative, provenance) if provenance.is_file() else (positive, negative)
        if not begin_stage("train", args, inputs=stage_inputs, outputs=(checkpoint,)):
            return 0
        model, redirects = _construct_benchmark_model()
        loader = _feature_array_batches(positive, negative)
        started = time.perf_counter()
        model.train_model(
            loader,
            max_steps=config["steps"],
            warmup_steps=1,
            hold_steps=0,
            negative_weight_schedule=[1],
            val_steps=[],
        )
        import torch

        output_dir.mkdir(parents=True, exist_ok=True)
        torch.save({"state_dict": model.model.state_dict(), "config": config}, checkpoint)
        details = {
            "steps": config["steps"],
            "seconds": time.perf_counter() - started,
            "input_hashes": {path.name: file_sha256(path) for path in (positive, negative)},
            "speechbrain_redirects_removed": list(redirects),
            "positive_feature_provenance_sha256": file_sha256(provenance)
            if provenance.is_file()
            else None,
        }
        (output_dir / "training.json").write_text(
            json.dumps(details, indent=2) + "\n", encoding="utf-8"
        )
        finish_stage(
            "train", args, inputs=stage_inputs, outputs=(checkpoint,), details=details
        )
        print(f"Trained {config['steps']} steps and saved {checkpoint}")
        return 0
    write_manifest(
        "train",
        args.profile,
        args.run_id,
        "planned",
        outputs=(output_dir,),
        details={
            "execute_requested": args.execute,
            "training_config": args.training_config,
            "note": "Use benchmark.py --measure-training for the tiny real DNN pass.",
        },
    )
    print(
        "No model training ran. Prototype may use CPU; quality training belongs on "
        "Linux/NVIDIA CUDA."
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
