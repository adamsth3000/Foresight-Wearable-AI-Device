"""Prepare reproducible prototype negative and augmentation assets."""

from __future__ import annotations

import argparse
import hashlib
import json
import tarfile
import urllib.request
from pathlib import Path

from common import TRAINING_ROOT, add_stage_arguments, begin_stage, finish_stage, run_root

LIBRISPEECH_URL = "https://www.openslr.org/resources/12/test-clean.tar.gz"
SAMPLE_RATE = 16_000


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _fallback_assets(directory: Path) -> tuple[Path, ...]:
    import numpy as np
    import soundfile as sf

    directory.mkdir(parents=True, exist_ok=True)
    paths: list[Path] = []
    for index in range(24):
        path = directory / f"synthetic_ambient_{index:03d}.wav"
        if not path.exists():
            noise = np.cumsum(np.random.default_rng(index).normal(0, 1, SAMPLE_RATE * 4))
            noise = (noise / max(float(np.max(abs(noise))), 0.001) * 0.08).astype(np.float32)
            sf.write(path, noise, SAMPLE_RATE)
        paths.append(path)
    rir = directory / "prototype_rir.wav"
    if not rir.exists():
        impulse = np.zeros(SAMPLE_RATE // 2, dtype=np.float32)
        impulse[0] = 1.0
        for offset, gain in ((127, 0.42), (541, 0.23), (1553, 0.11), (3811, 0.04)):
            impulse[offset] = gain
        sf.write(rir, impulse, SAMPLE_RATE)
    return (*paths, rir)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--download-librispeech", action="store_true")
    args = parser.parse_args()
    archive = TRAINING_ROOT / "cache" / "downloads" / "LibriSpeech-test-clean.tar.gz"
    speech_root = TRAINING_ROOT / "cache" / "downloads" / "LibriSpeech" / "test-clean"
    local_ambient = TRAINING_ROOT / "data" / "local" / "ambient_negative"
    asset_dir = TRAINING_ROOT / "cache" / "prototype_assets" / args.run_id
    marker = run_root(args.profile, args.run_id) / "prepared_assets.json"
    if not args.execute:
        print("No assets prepared. Use --execute and --download-librispeech if needed.")
        return 0
    if not begin_stage("prepare_assets", args, outputs=(marker,)):
        return 0
    if args.download_librispeech and not speech_root.is_dir():
        archive.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(LIBRISPEECH_URL, archive)
        with tarfile.open(archive) as bundle:
            bundle.extractall(archive.parent)
    if not speech_root.is_dir():
        raise RuntimeError("Missing LibriSpeech test-clean; use --download-librispeech.")
    local_count = len(list(local_ambient.glob("*.wav"))) if local_ambient.is_dir() else 0
    fallback = _fallback_assets(asset_dir)
    marker.parent.mkdir(parents=True, exist_ok=True)
    details = {
        "librispeech_test_clean": str(speech_root),
        "archive_sha256": _sha256(archive) if archive.exists() else "external-existing",
        "ambient_source": "local" if local_count else "synthetic-fallback-weaker",
        "ambient_count": local_count or len(fallback) - 1,
    }
    marker.write_text(json.dumps(details, indent=2) + "\n", encoding="utf-8")
    finish_stage("prepare_assets", args, outputs=(marker,))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
