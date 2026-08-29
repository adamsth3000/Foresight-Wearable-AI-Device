"""Create deterministic real prototype negative WAV partitions."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import yaml
from common import TRAINING_ROOT, add_stage_arguments, begin_stage, finish_stage
from prototype_data import file_sha256, wav_files, write_two_second_clips


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    config = yaml.safe_load((TRAINING_ROOT / "config" / f"{args.profile}.yaml").read_text())
    root = TRAINING_ROOT / "cache" / "negatives" / args.profile / args.run_id
    train, validation = root / "train", root / "validation"
    manifest = root / "source_manifest.json"
    if not args.execute:
        print("No negatives prepared. Run prepare_assets.py first, then use --execute.")
        return 0
    if not begin_stage("prepare_negatives", args, outputs=(train, validation, manifest)):
        return 0
    speech = sorted(
        (TRAINING_ROOT / "cache" / "downloads" / "LibriSpeech" / "test-clean").glob("**/*.flac")
    )
    local = wav_files(TRAINING_ROOT / "data" / "local" / "ambient_negative")
    fallback = wav_files(TRAINING_ROOT / "cache" / "prototype_assets" / args.run_id)
    ambient = local or [path for path in fallback if "ambient" in path.name]
    if not speech or not ambient:
        raise RuntimeError(
            "Missing LibriSpeech or prototype ambient assets; run prepare_assets.py."
        )
    # Interleave fixed source groups so each partition includes speech and ambient negatives.
    sources = [
        Path(path) for pair in zip(speech, ambient * len(speech), strict=False) for path in pair
    ]
    write_two_second_clips(sources, train, config["negative_train_samples"])
    write_two_second_clips(
        list(reversed(sources)), validation, config["negative_validation_samples"]
    )
    details = {
        "ordinary_speech_source": "LibriSpeech test-clean",
        "ambient_source": "local recordings" if local else "synthetic fallback (weaker)",
        "source_hashes": {str(path): file_sha256(path) for path in (local or fallback)},
    }
    manifest.write_text(json.dumps(details, indent=2) + "\n", encoding="utf-8")
    finish_stage(
        "prepare_negatives",
        args,
        inputs=tuple(speech[:1]),
        outputs=(train, validation, manifest),
        details=details,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
