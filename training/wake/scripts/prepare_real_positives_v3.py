"""Validate and manifest independent v3 real positives without reading field data."""

from __future__ import annotations

import argparse
from pathlib import Path

import yaml
from common import TRAINING_ROOT, add_stage_arguments, begin_stage, finish_stage, run_root
from prototype_data import wav_files
from real_positives import write_split_manifest


def v3_real_positive_root() -> Path:
    """Return the dedicated v3 collection area, separate from frozen field evaluation."""

    return TRAINING_ROOT / "data" / "real_positive_v3"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    parser.add_argument("--execute", action="store_true")
    args = parser.parse_args()
    if args.profile != "prototype-v3":
        raise RuntimeError("This manifest stage is only defined for prototype-v3.")
    if not args.execute:
        print("No v3 real-positive manifest was written. Re-run with --execute after collection.")
        return 0
    config = yaml.safe_load((TRAINING_ROOT / "config" / f"{args.profile}.yaml").read_text())
    root = v3_real_positive_root()
    split = {
        "train": wav_files(root / "train"),
        "validation": wav_files(root / "validation"),
        "held_out": [],
    }
    expected = {
        "train": config["real_positive_train_samples"],
        "validation": config["real_positive_validation_samples"],
        "held_out": 0,
    }
    for name, count in expected.items():
        if len(split[name]) != count:
            raise RuntimeError(f"v3 real {name} expects {count} WAVs, found {len(split[name])}.")
    manifest = run_root(args.profile, args.run_id) / "artifacts" / "real_positive_split.json"
    inputs = (*split["train"], *split["validation"])
    if not begin_stage("prepare_real_positives", args, inputs=inputs, outputs=(manifest,)):
        return 0
    write_split_manifest(manifest, args.profile, args.run_id, split)
    finish_stage("prepare_real_positives", args, inputs=inputs, outputs=(manifest,))
    print(f"Wrote v3 real-positive manifest: {manifest}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
