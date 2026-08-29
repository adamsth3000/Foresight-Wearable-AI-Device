"""Record the deterministic v2 assignment of existing real wake-word WAVs."""

from __future__ import annotations

import argparse
from pathlib import Path

import yaml
from common import TRAINING_ROOT, add_stage_arguments, begin_stage, finish_stage, run_root
from prototype_data import wav_files
from real_positives import deterministic_split, write_split_manifest


def training_positive_recordings() -> list[Path]:
    """Return only the legacy split source; field evaluation is intentionally excluded."""

    return wav_files(TRAINING_ROOT / "data" / "evaluation" / "positive")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if args.profile != "prototype-v2":
        raise RuntimeError("Real-positive splitting is only defined for the prototype-v2 profile.")
    if not args.execute:
        print("No real recordings assigned. Re-run with --execute after reviewing the source WAVs.")
        return 0
    config = yaml.safe_load((TRAINING_ROOT / "config" / f"{args.profile}.yaml").read_text())
    recordings = training_positive_recordings()
    manifest = run_root(args.profile, args.run_id) / "artifacts" / "real_positive_split.json"
    if not begin_stage("prepare_real_positives", args, inputs=recordings, outputs=(manifest,)):
        return 0
    split = deterministic_split(
        recordings,
        config["real_positive_train_samples"],
        config["real_positive_validation_samples"],
        config["real_positive_held_out_samples"],
    )
    write_split_manifest(manifest, args.profile, args.run_id, split)
    finish_stage("prepare_real_positives", args, inputs=recordings, outputs=(manifest,))
    print(f"Wrote deterministic real-positive split: {manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
