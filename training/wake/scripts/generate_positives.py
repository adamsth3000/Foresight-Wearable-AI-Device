"""Generate configured Piper positive train and validation clips."""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

import yaml
from common import (
    TRAINING_ROOT,
    add_stage_arguments,
    begin_stage,
    finish_stage,
)


def _count(directory: Path) -> int:
    return len(list(directory.glob("*.wav"))) if directory.exists() else 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    add_stage_arguments(parser)
    parser.add_argument("--execute", action="store_true")
    parser.add_argument("--espeak-path", type=Path, required=True)
    parser.add_argument("--expected-checkpoint-sha256")
    args = parser.parse_args()
    config = yaml.safe_load((TRAINING_ROOT / "config" / f"{args.profile}.yaml").read_text())
    root = TRAINING_ROOT / "cache" / "positives" / args.profile / args.run_id
    partitions = (
        (root / "train", config["n_samples"]),
        (root / "validation", config["n_samples_val"]),
    )
    if not args.execute:
        print("No clips generated. Use --execute after reviewing the Piper checkpoint.")
        return 0
    if not begin_stage("generate_positives", args, outputs=tuple(path for path, _ in partitions)):
        return 0
    launcher = Path(__file__).with_name("piper_generator_launcher.py")
    generator = TRAINING_ROOT / "cache" / "tools" / "piper-sample-generator" / "generate_samples.py"
    checkpoint = generator.parent / "models" / "en-us-libritts-high.pt"
    for directory, expected in partitions:
        directory.mkdir(parents=True, exist_ok=True)
        remaining = expected - _count(directory)
        if remaining < 0:
            raise RuntimeError(f"{directory} exceeds its configured count of {expected} clips.")
        if remaining:
            command = [
                sys.executable,
                str(launcher),
                "--generator-path",
                str(generator),
                "--trusted-model",
                str(checkpoint),
            ]
            if args.expected_checkpoint_sha256:
                command += ["--expected-checkpoint-sha256", args.expected_checkpoint_sha256]
            command += [
                "--",
                "hey foresight",
                "--max-samples",
                str(remaining),
                "--model",
                str(checkpoint),
                "--output-dir",
                str(directory),
                "--max-speakers",
                "3",
            ]
            environment = os.environ.copy()
            environment["FORESIGHT_TRAINING_ESPEAK_PATH"] = str(args.espeak_path)
            environment["PYTHONPATH"] = (
                str(Path(__file__).parent) + os.pathsep + environment.get("PYTHONPATH", "")
            )
            subprocess.run(command, check=True, env=environment)
        if _count(directory) != expected:
            raise RuntimeError(
                f"{directory} incomplete: expected {expected}, found {_count(directory)}."
            )
    finish_stage("generate_positives", args, outputs=tuple(path for path, _ in partitions))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
