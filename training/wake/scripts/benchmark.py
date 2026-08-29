"""Run a tiny, dependency-aware planning benchmark without training a model."""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from pathlib import Path

from common import (
    TRAINING_ROOT,
    add_stage_arguments,
    begin_stage,
    finish_stage,
    print_missing_packages,
    profile_config_path,
    run_root,
)
from piper_generator_launcher import checkpoint_sha256


def _profile_value(profile: str, key: str) -> int:
    match = re.search(rf"^{key}:\s*(\d+)$", profile_config_path(profile).read_text(), re.MULTILINE)
    if match is None:
        raise RuntimeError(f"{key} is missing from the {profile} profile.")
    return int(match.group(1))


def _measure_torch_cpu() -> dict[str, float] | None:
    """Measure a tiny CPU tensor operation without loading models or data."""

    try:
        import torch
    except ImportError:
        return None

    left = torch.rand((256, 256))
    right = torch.rand((256, 256))
    started = time.perf_counter()
    for _ in range(3):
        torch.matmul(left, right)
    elapsed = max(time.perf_counter() - started, 0.000001)
    return {"operations": 3, "seconds": elapsed, "matrix_multiplications_per_second": 3 / elapsed}


def _measure_positive_generation(
    generator_path: Path,
    voice_model: Path,
    sample_count: int,
    output_dir: Path,
    espeak_path: Path | None,
    expected_checkpoint_sha256: str | None,
) -> dict[str, float | str]:
    """Measure a few external Piper clips only when explicitly requested."""

    if not generator_path.is_file():
        raise RuntimeError(
            "The Piper generator script does not exist. See training/wake/README.md "
            "for the reviewed checkout and asset setup."
        )
    if not voice_model.is_file():
        raise RuntimeError(
            "The reviewed LibriTTS checkpoint does not exist. See training/wake/README.md "
            "for its required location."
        )
    output_dir.mkdir(parents=True, exist_ok=True)
    launcher_path = Path(__file__).with_name("piper_generator_launcher.py")
    command = [
        sys.executable,
        str(launcher_path),
        "--generator-path",
        str(generator_path),
        "--trusted-model",
        str(voice_model),
        *(
            ["--expected-checkpoint-sha256", expected_checkpoint_sha256]
            if expected_checkpoint_sha256 is not None
            else []
        ),
        "--",
        "hey foresight",
        "--max-samples",
        str(sample_count),
        "--model",
        str(voice_model),
        "--output-dir",
        str(output_dir),
        "--max-speakers",
        "3",
    ]
    environment = os.environ.copy()
    compatibility_path = str(Path(__file__).parent)
    environment["PYTHONPATH"] = compatibility_path + os.pathsep + environment.get("PYTHONPATH", "")
    if espeak_path is not None:
        environment["FORESIGHT_TRAINING_ESPEAK_PATH"] = str(espeak_path)
    started = time.perf_counter()
    subprocess.run(command, check=True, env=environment)
    elapsed = max(time.perf_counter() - started, 0.000001)
    clips = len(list(output_dir.glob("*.wav")))
    if clips == 0:
        raise RuntimeError("The Piper command completed without producing WAV clips.")
    clips_per_second = clips / elapsed
    return {
        "checkpoint_sha256": checkpoint_sha256(voice_model),
        "total_generation_seconds": elapsed,
        "clips_generated": clips,
        "clips_per_second": clips_per_second,
        "seconds_per_clip": elapsed / clips,
    }


def _measure_augmentation(
    input_dir: Path,
    output_dir: Path,
    asset_dir: Path,
    sample_count: int,
) -> dict[str, float | int | str]:
    from augment import measure_tiny_augmentation

    return measure_tiny_augmentation(input_dir, output_dir, asset_dir, sample_count)


def _measure_feature_extraction(
    input_dir: Path,
    output_dir: Path,
    model_dir: Path,
    sample_count: int,
) -> dict[str, float | int | list[int] | str]:
    from extract_features import measure_tiny_feature_extraction

    return measure_tiny_feature_extraction(input_dir, output_dir, model_dir, sample_count)


def _measure_training(
    positive_feature_path: Path,
    output_dir: Path,
    steps: int,
) -> dict[str, float | int | str | bool]:
    from train import measure_tiny_training

    return measure_tiny_training(positive_feature_path, output_dir, steps)


def _prior_measurement_reports() -> dict[str, str]:
    """Return the latest report path for each previously measured benchmark stage."""

    known: dict[str, str] = {}
    for report_path in sorted((TRAINING_ROOT / "outputs").glob("*/**/reports/benchmark.json")):
        try:
            report = json.loads(report_path.read_text(encoding="utf-8"))
        except json.JSONDecodeError:
            continue
        for stage in ("positive_generation", "augmentation", "feature_extraction", "training"):
            if isinstance(report.get(f"measured_{stage}"), dict):
                known[stage] = str(report_path)
    return known


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    parser.add_argument(
        "--sample-count",
        type=int,
        default=5,
        help="Tiny real-generation sample count; accepted values are 1 through 10.",
    )
    parser.add_argument("--measure-positive-generation", action="store_true")
    parser.add_argument("--measure-augmentation", action="store_true")
    parser.add_argument("--measure-feature-extraction", action="store_true")
    parser.add_argument("--measure-training", action="store_true")
    parser.add_argument("--generator-path", type=Path)
    parser.add_argument("--voice-model", type=Path)
    parser.add_argument(
        "--augmentation-input-dir",
        type=Path,
        help="Directory containing the existing one-through-ten 16 kHz positive WAV clips.",
    )
    parser.add_argument(
        "--feature-input-dir",
        type=Path,
        help="Directory containing the existing one-through-ten 16 kHz augmented WAV clips.",
    )
    parser.add_argument(
        "--feature-model-dir",
        type=Path,
        default=TRAINING_ROOT / "cache" / "models" / "openwakeword-v0.5.1",
        help="Directory containing the two explicitly provisioned openWakeWord ONNX models.",
    )
    parser.add_argument(
        "--training-positive-features",
        type=Path,
        help="Existing real positive feature array with shape (N, 16, 96).",
    )
    parser.add_argument(
        "--training-steps",
        type=int,
        default=20,
        help="Tiny real training step count; accepted values are 10 through 50.",
    )
    parser.add_argument(
        "--expected-checkpoint-sha256",
        help="Optional SHA-256 required for the reviewed Piper checkpoint.",
    )
    parser.add_argument(
        "--espeak-path",
        type=Path,
        help="Optional x64 espeak-ng.exe path for the Windows-local compatibility module.",
    )
    args = parser.parse_args()
    if not 1 <= args.sample_count <= 10:
        parser.error("--sample-count must be between 1 and 10 for the safe benchmark.")
    if not 10 <= args.training_steps <= 50:
        parser.error("--training-steps must be between 10 and 50 for the safe benchmark.")
    report_path = run_root(args.profile, args.run_id) / "reports" / "benchmark.json"
    if not begin_stage("benchmark", args, outputs=(report_path,)):
        return 0

    started = time.perf_counter()
    _ = [index * index for index in range(max(args.sample_count, 1) * 10_000)]
    seconds = max(time.perf_counter() - started, 0.000001)
    profiles: dict[str, dict[str, int | float]] = {
        name: {
            "n_samples": _profile_value(name, "n_samples"),
            "steps": _profile_value(name, "steps"),
        }
        for name in ("prototype", "quality")
    }
    positive_measurement = None
    augmentation_measurement = None
    feature_extraction_measurement = None
    training_measurement = None
    if args.measure_positive_generation:
        if args.generator_path is None or args.voice_model is None:
            raise SystemExit(
                "--measure-positive-generation requires --generator-path and --voice-model."
            )
        positive_measurement = _measure_positive_generation(
            args.generator_path,
            args.voice_model,
            args.sample_count,
            TRAINING_ROOT / "cache" / "benchmarks" / args.run_id / "positive_generation",
            args.espeak_path,
            args.expected_checkpoint_sha256,
        )
        for values in profiles.values():
            values["projected_generation_seconds_from_measured_throughput"] = values[
                "n_samples"
            ] / float(positive_measurement["clips_per_second"])
    if args.measure_augmentation:
        if args.augmentation_input_dir is None:
            raise SystemExit("--measure-augmentation requires --augmentation-input-dir.")
        augmentation_measurement = _measure_augmentation(
            args.augmentation_input_dir,
            TRAINING_ROOT / "cache" / "benchmarks" / args.run_id / "augmentation",
            TRAINING_ROOT / "cache" / "benchmark_assets" / "tiny_augmentation",
            args.sample_count,
        )
        for values in profiles.values():
            values["projected_augmentation_seconds_from_measured_throughput"] = values[
                "n_samples"
            ] / float(augmentation_measurement["clips_per_second"])
    if args.measure_feature_extraction:
        if args.feature_input_dir is None:
            raise SystemExit("--measure-feature-extraction requires --feature-input-dir.")
        feature_extraction_measurement = _measure_feature_extraction(
            args.feature_input_dir,
            TRAINING_ROOT / "cache" / "benchmarks" / args.run_id / "feature_extraction",
            args.feature_model_dir,
            args.sample_count,
        )
        for values in profiles.values():
            values["projected_feature_extraction_seconds_from_measured_throughput"] = values[
                "n_samples"
            ] / float(feature_extraction_measurement["clips_per_second"])
    if args.measure_training:
        if args.training_positive_features is None:
            raise SystemExit("--measure-training requires --training-positive-features.")
        training_measurement = _measure_training(
            args.training_positive_features,
            TRAINING_ROOT / "cache" / "benchmarks" / args.run_id / "training",
            args.training_steps,
        )
        for values in profiles.values():
            values["projected_training_seconds_from_measured_throughput"] = values["steps"] / float(
                training_measurement["steps_per_second"]
            )
    prior_reports = _prior_measurement_reports()
    current_measurements = {
        "positive_generation": positive_measurement,
        "augmentation": augmentation_measurement,
        "feature_extraction": feature_extraction_measurement,
        "training": training_measurement,
    }
    known_measurements = {
        stage: "current" if value is not None else prior_reports.get(stage)
        for stage, value in current_measurements.items()
    }
    unmeasured_stages = [
        f"{stage.replace('_', ' ')} has not yet completed a tiny benchmark"
        for stage, source in known_measurements.items()
        if source is None
    ]
    report = {
        "sample_count": args.sample_count,
        "cheap_python_smoke_seconds": seconds,
        "measured_torch_cpu": _measure_torch_cpu(),
        "measured_positive_generation": positive_measurement,
        "measured_augmentation": augmentation_measurement,
        "measured_feature_extraction": feature_extraction_measurement,
        "measured_training": training_measurement,
        "known_measurement_reports": known_measurements,
        "unmeasured_stages": unmeasured_stages,
        "profiles": profiles,
        "note": (
            "Stage projections are omitted until their explicit tiny benchmarks measure this "
            "machine. Prior report paths are references, not recreated measurements."
        ),
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    finish_stage("benchmark", args, outputs=(report_path,), details=report)
    print_missing_packages(
        ("openwakeword", "onnxruntime", "torch"),
        "training/wake/requirements/local.txt",
    )
    print(json.dumps(report, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
