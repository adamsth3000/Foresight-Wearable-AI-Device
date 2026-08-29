"""Small deterministic helpers shared by the real prototype stages."""

from __future__ import annotations

import hashlib
from pathlib import Path

SAMPLE_RATE = 16_000
CLIP_SAMPLES = SAMPLE_RATE * 2


def wav_files(directory: Path) -> list[Path]:
    return sorted(path for path in directory.glob("*.wav") if path.is_file())


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def write_two_second_clips(source_paths: list[Path], output_dir: Path, count: int) -> None:
    """Resample mono source audio and write deterministic non-overlapping clips."""

    import numpy as np
    import soundfile as sf
    from scipy.signal import resample_poly

    output_dir.mkdir(parents=True, exist_ok=True)
    written = len(wav_files(output_dir))
    if written >= count:
        return
    for source in source_paths:
        samples, rate = sf.read(source, dtype="float32", always_2d=True)
        mono = np.mean(samples, axis=1)
        if rate != SAMPLE_RATE:
            mono = resample_poly(mono, SAMPLE_RATE, rate).astype(np.float32)
        for start in range(0, len(mono) - CLIP_SAMPLES + 1, CLIP_SAMPLES):
            if written >= count:
                return
            clip = mono[start : start + CLIP_SAMPLES]
            peak = max(float(np.max(np.abs(clip))), 0.001)
            sf.write(output_dir / f"negative_{written:05d}.wav", clip / peak * 0.8, SAMPLE_RATE)
            written += 1
    if written != count:
        raise RuntimeError(f"Insufficient source audio: expected {count} clips, wrote {written}.")


def validate_features(path: Path, expected: int) -> None:
    import numpy as np

    values = np.load(path, mmap_mode="r")
    if values.shape != (expected, 16, 96) or values.dtype != np.float32:
        raise RuntimeError(f"Unexpected features at {path}: {values.shape} {values.dtype}.")
    if not np.isfinite(values).all():
        raise RuntimeError(f"Non-finite values in {path}.")
