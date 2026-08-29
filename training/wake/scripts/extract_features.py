"""Extract real openWakeWord speech embeddings from a tiny set of augmented clips."""
# ruff: noqa: E501

from __future__ import annotations

import argparse
import json
import tracemalloc
from pathlib import Path

import yaml
from common import TRAINING_ROOT, add_stage_arguments, begin_stage, finish_stage, write_manifest
from prototype_data import file_sha256, validate_features, wav_files
from real_positives import load_split

SAMPLE_RATE = 16_000
CLIP_SAMPLES = 32_000
FEATURE_MODEL_URLS = {
    "melspectrogram.onnx": (
        "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/melspectrogram.onnx"
    ),
    "embedding_model.onnx": (
        "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1/embedding_model.onnx"
    ),
}


def default_model_dir() -> Path:
    return TRAINING_ROOT / "cache" / "models" / "openwakeword-v0.5.1"


def prepare_feature_models(model_dir: Path) -> tuple[Path, Path]:
    """Download only the two upstream ONNX feature graphs when explicitly requested."""

    import requests

    model_dir.mkdir(parents=True, exist_ok=True)
    for filename, url in FEATURE_MODEL_URLS.items():
        destination = model_dir / filename
        if destination.is_file():
            continue
        temporary_path = destination.with_suffix(".part")
        with requests.get(url, stream=True, timeout=60) as response:
            response.raise_for_status()
            with temporary_path.open("wb") as output_file:
                for chunk in response.iter_content(chunk_size=1024 * 1024):
                    if chunk:
                        output_file.write(chunk)
        temporary_path.replace(destination)
    return _feature_model_paths(model_dir)


def _feature_model_paths(model_dir: Path) -> tuple[Path, Path]:
    melspectrogram = model_dir / "melspectrogram.onnx"
    embedding = model_dir / "embedding_model.onnx"
    missing = [str(path) for path in (melspectrogram, embedding) if not path.is_file()]
    if missing:
        raise RuntimeError(
            "Missing openWakeWord feature models. Run extract_features.py --prepare-models "
            f"--model-dir {model_dir} first."
        )
    return melspectrogram, embedding


def _fixed_length_audio(input_paths: list[Path]) -> object:
    import numpy as np
    import soundfile as sf

    clips = np.zeros((len(input_paths), CLIP_SAMPLES), dtype=np.int16)
    for index, path in enumerate(input_paths):
        samples, sample_rate = sf.read(path, dtype="float32", always_2d=True)
        if sample_rate != SAMPLE_RATE:
            raise RuntimeError(f"{path} must be {SAMPLE_RATE} Hz, not {sample_rate} Hz.")
        mono = np.mean(samples, axis=1)
        clipped = np.clip(mono, -1.0, 1.0)
        clips[index, : min(len(clipped), CLIP_SAMPLES)] = (clipped[:CLIP_SAMPLES] * 32767).astype(
            np.int16
        )
    return clips


def _extract_paths(frontend: object, input_paths: list[Path], output: Path) -> None:
    """Extract the standard frontend features for a deterministic group of WAV files."""

    import numpy as np

    batches = []
    for start in range(0, len(input_paths), 32):
        batches.append(
            frontend.embed_clips(
                _fixed_length_audio(input_paths[start : start + 32]),
                batch_size=min(32, len(input_paths) - start),
                ncpu=1,
            )
        )
    np.save(output, np.vstack(batches).astype(np.float32))


def measure_tiny_feature_extraction(
    input_dir: Path,
    output_dir: Path,
    model_dir: Path,
    sample_count: int,
) -> dict[str, float | int | list[int] | str]:
    """Use openWakeWord's ONNX feature pipeline on at most ten existing WAV clips."""

    input_paths = sorted(input_dir.glob("*.wav"))[:sample_count]
    if not input_paths:
        raise RuntimeError(f"No WAV inputs found in {input_dir}.")
    melspectrogram_path, embedding_path = _feature_model_paths(model_dir)

    import time

    import numpy as np
    from openwakeword.utils import AudioFeatures

    clips = _fixed_length_audio(input_paths)
    output_dir.mkdir(parents=True, exist_ok=True)
    output_path = output_dir / "positive_features.npy"
    tracemalloc.start()
    started = time.perf_counter()
    features = AudioFeatures(
        melspec_model_path=str(melspectrogram_path),
        embedding_model_path=str(embedding_path),
        inference_framework="onnx",
        device="cpu",
        ncpu=1,
    ).embed_clips(clips, batch_size=len(clips), ncpu=1)
    np.save(output_path, features)
    elapsed = max(time.perf_counter() - started, 0.000001)
    _, peak_memory = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    clips_per_second = len(input_paths) / elapsed
    return {
        "input_clips": len(input_paths),
        "feature_arrays": 1,
        "feature_shape": list(features.shape),
        "total_extraction_seconds": elapsed,
        "clips_per_second": clips_per_second,
        "seconds_per_clip": elapsed / len(input_paths),
        "output_bytes": output_path.stat().st_size,
        "peak_python_tracemalloc_bytes": peak_memory,
        "feature_output": str(output_path),
        "melspectrogram_model": str(melspectrogram_path),
        "embedding_model": str(embedding_path),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--prepare-models", action="store_true")
    parser.add_argument("--model-dir", type=Path, default=default_model_dir())
    args = parser.parse_args()
    if args.prepare_models:
        models = prepare_feature_models(args.model_dir)
        for model in models:
            print(f"Prepared {model} ({model.stat().st_size} bytes)")
        return 0
    output_dir = TRAINING_ROOT / "cache" / "features" / args.profile / args.run_id
    if not args.execute:
        write_manifest(
            "extract_features", args.profile, args.run_id, "planned", outputs=(output_dir,)
        )
        print("No feature extraction ran. Re-run with --execute after augmentation and negatives.")
        return 0
    config = yaml.safe_load((TRAINING_ROOT / "config" / f"{args.profile}.yaml").read_text())
    if args.profile in {"prototype-v2", "prototype-v3"}:
        root = TRAINING_ROOT / "cache" / "augmented" / args.profile / args.run_id
        negative_root = TRAINING_ROOT / "cache" / "negatives" / args.profile / args.run_id
        split_manifest = (
            TRAINING_ROOT
            / "outputs"
            / args.profile
            / args.run_id
            / "artifacts"
            / "real_positive_split.json"
        )
        split = load_split(split_manifest)
        groups = {
            "synthetic_train": (wav_files(root / "synthetic_train"), config["n_samples"]),
            "synthetic_validation": (
                wav_files(root / "synthetic_validation"),
                config["n_samples_val"],
            ),
            "real_train": (split["train"], config["real_positive_train_samples"]),
            "real_validation": (split["validation"], config["real_positive_validation_samples"]),
            "real_augmented_train": (
                wav_files(root / "real_train"),
                config["real_positive_train_samples"]
                * config["real_positive_augmentation_variants"],
            ),
            "real_augmented_validation": (
                wav_files(root / "real_validation"),
                config["real_positive_validation_samples"]
                * config["real_positive_augmentation_variants"],
            ),
            "negative_train": (
                wav_files(negative_root / "train"),
                config["negative_train_samples"],
            ),
            "negative_validation": (
                wav_files(negative_root / "validation"),
                config["negative_validation_samples"],
            ),
        }
        for name, (paths, expected) in groups.items():
            if len(paths) != expected:
                raise RuntimeError(f"{name} expects {expected} clips, found {len(paths)}.")
        group_outputs = {name: output_dir / f"{name}.npy" for name in groups}
        outputs = (
            output_dir / "positive_train.npy",
            output_dir / "positive_validation.npy",
            output_dir / "negative_train.npy",
            output_dir / "negative_validation.npy",
            *group_outputs.values(),
            output_dir / "positive_feature_provenance.json",
        )
        stage_inputs = (
            split_manifest,
            root / "synthetic_train",
            root / "synthetic_validation",
            root / "real_train",
            root / "real_validation",
            negative_root / "train",
            negative_root / "validation",
            *split["train"],
            *split["validation"],
        )
        if not begin_stage("extract_features", args, inputs=stage_inputs, outputs=outputs):
            return 0
        melspectrogram, embedding = _feature_model_paths(args.model_dir)
        from openwakeword.utils import AudioFeatures

        frontend = AudioFeatures(
            melspec_model_path=str(melspectrogram),
            embedding_model_path=str(embedding),
            inference_framework="onnx",
            device="cpu",
            ncpu=1,
        )
        output_dir.mkdir(parents=True, exist_ok=True)
        for name, (paths, _) in groups.items():
            _extract_paths(frontend, paths, group_outputs[name])
        import numpy as np

        real_train_repeat = config.get("real_positive_train_oversample", 1)
        merged = {
            "positive_train": (
                "synthetic_train",
                *(("real_train", "real_augmented_train") * real_train_repeat),
            ),
            "positive_validation": (
                "synthetic_validation",
                "real_validation",
                "real_augmented_validation",
            ),
        }
        for target, source_names in merged.items():
            np.save(
                output_dir / f"{target}.npy",
                np.vstack([np.load(group_outputs[name]) for name in source_names]).astype(np.float32),
            )
        for name in ("negative_train", "negative_validation"):
            np.save(output_dir / f"{name}.npy", np.load(group_outputs[name]))
        validate_features(output_dir / "positive_train.npy", config["positive_train_samples"])
        validate_features(output_dir / "positive_validation.npy", config["positive_validation_samples"])
        validate_features(output_dir / "negative_train.npy", config["negative_train_samples"])
        validate_features(output_dir / "negative_validation.npy", config["negative_validation_samples"])
        provenance = {
            "profile": args.profile,
            "run_id": args.run_id,
            "real_positive_split_manifest": str(split_manifest),
            "groups": {
                name: {
                    "feature_path": str(group_outputs[name]),
                    "count": expected,
                    "source_paths": [str(path) for path in paths],
                    "sha256": file_sha256(group_outputs[name]),
                }
                for name, (paths, expected) in groups.items()
            },
            "merged": {
                name: {"sources": source_names, "count": config[f"{name}_samples"]}
                for name, source_names in merged.items()
            },
        }
        provenance_path = output_dir / "positive_feature_provenance.json"
        provenance_path.write_text(json.dumps(provenance, indent=2) + "\n", encoding="utf-8")
        finish_stage("extract_features", args, inputs=stage_inputs, outputs=outputs)
        return 0
    groups = {
        "positive_train": (
            TRAINING_ROOT / "cache" / "augmented" / args.profile / args.run_id / "train",
            config["n_samples"],
        ),
        "positive_validation": (
            TRAINING_ROOT / "cache" / "augmented" / args.profile / args.run_id / "validation",
            config["n_samples_val"],
        ),
        "negative_train": (
            TRAINING_ROOT / "cache" / "negatives" / args.profile / args.run_id / "train",
            config["negative_train_samples"],
        ),
        "negative_validation": (
            TRAINING_ROOT / "cache" / "negatives" / args.profile / args.run_id / "validation",
            config["negative_validation_samples"],
        ),
    }
    outputs = tuple(output_dir / f"{name}.npy" for name in groups)
    if not begin_stage(
        "extract_features", args, inputs=tuple(path for path, _ in groups.values()), outputs=outputs
    ):
        return 0
    melspectrogram, embedding = _feature_model_paths(args.model_dir)
    import numpy as np
    from openwakeword.utils import AudioFeatures

    frontend = AudioFeatures(
        melspec_model_path=str(melspectrogram),
        embedding_model_path=str(embedding),
        inference_framework="onnx",
        device="cpu",
        ncpu=1,
    )
    output_dir.mkdir(parents=True, exist_ok=True)
    for (name, (directory, expected)), output in zip(groups.items(), outputs, strict=True):
        inputs = wav_files(directory)
        if len(inputs) != expected:
            raise RuntimeError(f"{name} expects {expected} clips, found {len(inputs)}.")
        batches = []
        for start in range(0, len(inputs), 32):
            batches.append(
                frontend.embed_clips(
                    _fixed_length_audio(inputs[start : start + 32]),
                    batch_size=min(32, len(inputs) - start),
                    ncpu=1,
                )
            )
        np.save(output, np.vstack(batches).astype(np.float32))
        validate_features(output, expected)
    finish_stage(
        "extract_features",
        args,
        outputs=outputs,
        details={"sha256": {path.name: file_sha256(path) for path in outputs}},
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
