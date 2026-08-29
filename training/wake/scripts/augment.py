"""Run a tiny, local audio augmentation pass without full training assets."""
# ruff: noqa: E501

from __future__ import annotations

import argparse
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
from prototype_data import wav_files
from real_positives import load_split

BENCHMARK_SAMPLE_RATE = 16_000


def _write_tiny_assets(asset_dir: Path) -> tuple[Path, Path]:
    """Create deterministic, small local assets for a transform-throughput check."""

    import numpy as np
    import soundfile as sf

    asset_dir.mkdir(parents=True, exist_ok=True)
    background_path = asset_dir / "synthetic_ambient_noise.wav"
    rir_path = asset_dir / "synthetic_small_room_rir.wav"
    if not background_path.is_file():
        generator = np.random.default_rng(20260826)
        noise = generator.normal(0.0, 1.0, BENCHMARK_SAMPLE_RATE * 4).astype(np.float32)
        ambient = np.cumsum(noise)
        ambient /= max(float(np.max(np.abs(ambient))), 0.000001)
        sf.write(background_path, ambient * 0.08, BENCHMARK_SAMPLE_RATE)
    if not rir_path.is_file():
        rir = np.zeros(BENCHMARK_SAMPLE_RATE // 2, dtype=np.float32)
        rir[0] = 1.0
        for offset, gain in ((137, 0.45), (503, 0.26), (1311, 0.12), (3707, 0.05)):
            rir[offset] = gain
        sf.write(rir_path, rir, BENCHMARK_SAMPLE_RATE)
    return background_path, rir_path


def _mono_audio(path: Path) -> tuple[object, int]:
    import numpy as np
    import soundfile as sf

    samples, sample_rate = sf.read(path, dtype="float32", always_2d=True)
    return np.mean(samples, axis=1), sample_rate


def _augment_clip(
    input_path: Path,
    output_path: Path,
    background: object,
    rir: object,
    *,
    snr_db: float = 15.0,
    output_gain: float = 0.8,
    background_offset: int = 0,
) -> None:
    import numpy as np
    import soundfile as sf
    from scipy.signal import fftconvolve

    samples, sample_rate = _mono_audio(input_path)
    if sample_rate != BENCHMARK_SAMPLE_RATE:
        raise RuntimeError(
            f"{input_path} must be {BENCHMARK_SAMPLE_RATE} Hz, not {sample_rate} Hz."
        )
    reverberated = fftconvolve(samples, rir, mode="full")[: len(samples)]
    peak = max(float(np.max(np.abs(reverberated))), 0.000001)
    reverberated = reverberated / peak * min(peak, 0.9)
    repeated_background = np.resize(np.roll(background, background_offset), len(reverberated))
    speech_power = max(float(np.mean(reverberated**2)), 0.000001)
    background_power = max(float(np.mean(repeated_background**2)), 0.000001)
    snr_linear = 10 ** (snr_db / 10.0)
    background_gain = (speech_power / background_power / snr_linear) ** 0.5
    mixed = reverberated + repeated_background * background_gain
    sf.write(output_path, np.clip(mixed * output_gain, -1.0, 1.0), BENCHMARK_SAMPLE_RATE)


def _augment_real_positive_variants(
    input_paths: list[Path], output_dir: Path, background: object, rir: object, variants: int
) -> None:
    """Create deterministic room/noise/gain variants for the small real-audio anchor."""

    output_dir.mkdir(parents=True, exist_ok=True)
    for source_index, source in enumerate(input_paths):
        for variant in range(variants):
            target = output_dir / f"real_{source_index:03d}_variant_{variant:02d}.wav"
            if target.exists():
                continue
            _augment_clip(
                source,
                target,
                background,
                rir,
                snr_db=12.0 + (variant % 5) * 1.5,
                output_gain=0.70 + (variant % 4) * 0.05,
                background_offset=(source_index + 1) * (variant + 1) * 997,
            )


def measure_tiny_augmentation(
    input_dir: Path,
    output_dir: Path,
    asset_dir: Path,
    sample_count: int,
) -> dict[str, float | int | str]:
    """Augment at most ten existing 16 kHz clips with local tiny assets."""

    input_paths = sorted(input_dir.glob("*.wav"))[:sample_count]
    if not input_paths:
        raise RuntimeError(f"No WAV inputs found in {input_dir}.")
    background_path, rir_path = _write_tiny_assets(asset_dir)
    background, background_rate = _mono_audio(background_path)
    rir, rir_rate = _mono_audio(rir_path)
    if background_rate != BENCHMARK_SAMPLE_RATE or rir_rate != BENCHMARK_SAMPLE_RATE:
        raise RuntimeError("Tiny augmentation assets must be 16 kHz.")

    import time

    output_dir.mkdir(parents=True, exist_ok=True)
    tracemalloc.start()
    started = time.perf_counter()
    for index, input_path in enumerate(input_paths):
        _augment_clip(input_path, output_dir / f"augmented_{index:03d}.wav", background, rir)
    elapsed = max(time.perf_counter() - started, 0.000001)
    _, peak_memory = tracemalloc.get_traced_memory()
    tracemalloc.stop()
    output_count = len(list(output_dir.glob("*.wav")))
    if output_count != len(input_paths):
        raise RuntimeError("Tiny augmentation did not produce one WAV per input clip.")
    clips_per_second = output_count / elapsed
    return {
        "input_clips": len(input_paths),
        "output_clips": output_count,
        "total_augmentation_seconds": elapsed,
        "clips_per_second": clips_per_second,
        "seconds_per_clip": elapsed / output_count,
        "peak_python_tracemalloc_bytes": peak_memory,
        "background_asset": str(background_path),
        "rir_asset": str(rir_path),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    output_dir = TRAINING_ROOT / "cache" / "augmented" / args.profile / args.run_id
    if not args.execute:
        write_manifest("augment", args.profile, args.run_id, "planned", outputs=(output_dir,))
        print("No augmentation ran. Re-run with --execute after preparing positives and assets.")
        return 0
    config = yaml.safe_load((TRAINING_ROOT / "config" / f"{args.profile}.yaml").read_text())
    positives = TRAINING_ROOT / "cache" / "positives" / args.profile / args.run_id
    assets = TRAINING_ROOT / "cache" / "prototype_assets" / args.run_id
    backgrounds = wav_files(TRAINING_ROOT / "data" / "local" / "ambient_negative") or [
        path for path in wav_files(assets) if "ambient" in path.name
    ]
    rirs = [path for path in wav_files(assets) if "rir" in path.name]
    partitions = (("train", config["n_samples"]), ("validation", config["n_samples_val"]))
    is_real_anchor_run = args.profile in {"prototype-v2", "prototype-v3"}
    synthetic_outputs = tuple(
        output_dir / (f"synthetic_{name}" if is_real_anchor_run else name)
        for name, _ in partitions
    )
    split_manifest = run_root(args.profile, args.run_id) / "artifacts" / "real_positive_split.json"
    real_split = load_split(split_manifest) if is_real_anchor_run else {}
    real_outputs = (
        output_dir / "real_train",
        output_dir / "real_validation",
    ) if is_real_anchor_run else ()
    stage_inputs = (positives / "train", assets, split_manifest, *real_split.get("train", []), *real_split.get("validation", [])) if is_real_anchor_run else (positives / "train", assets)
    outputs = (*synthetic_outputs, *real_outputs)
    if not begin_stage("augment", args, inputs=stage_inputs, outputs=outputs):
        return 0
    if config["augmentation_rounds"] != 1 or not backgrounds or not rirs:
        raise RuntimeError(
            "Prototype requires one augmentation round plus prepared background and RIR assets."
        )
    for (name, expected), destination in zip(partitions, synthetic_outputs, strict=True):
        inputs = wav_files(positives / name)
        if len(inputs) != expected:
            raise RuntimeError(f"Positive {name} clips are incomplete.")
        destination.mkdir(parents=True, exist_ok=True)
        background, _ = _mono_audio(backgrounds[0])
        rir, _ = _mono_audio(rirs[0])
        for index, source in enumerate(inputs):
            target = destination / f"augmented_{index:05d}.wav"
            if not target.exists() or args.force:
                _augment_clip(source, target, background, rir)
        if len(wav_files(destination)) != expected:
            raise RuntimeError(f"Augmentation output {name} is incomplete.")
    if is_real_anchor_run:
        variants = config["real_positive_augmentation_variants"]
        for name, destination in zip(("train", "validation"), real_outputs, strict=True):
            background, _ = _mono_audio(backgrounds[0])
            rir, _ = _mono_audio(rirs[0])
            _augment_real_positive_variants(
                real_split[name], destination, background, rir, variants
            )
            expected = len(real_split[name]) * variants
            if len(wav_files(destination)) != expected:
                raise RuntimeError(f"Real-positive augmentation {name} is incomplete.")
    finish_stage("augment", args, inputs=stage_inputs, outputs=outputs)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
