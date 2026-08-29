"""Collect independent reviewed real positives for v3 outside field evaluation."""

from __future__ import annotations

import argparse
from pathlib import Path

from field_evaluation import SOURCE_DEVICES
from prepare_real_positives_v3 import v3_real_positive_root
from record_evaluation import next_recording_path
from record_field_evaluation import _capture_take


def _append_metadata(directory: Path, record: dict[str, object]) -> None:
    import json

    directory.mkdir(parents=True, exist_ok=True)
    with (directory / "metadata.jsonl").open("a", encoding="utf-8") as output:
        output.write(json.dumps(record, sort_keys=True) + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("partition", choices=("train", "validation"))
    parser.add_argument("--count", type=int, default=1)
    parser.add_argument("--source-device", choices=SOURCE_DEVICES, default="laptop_mic")
    parser.add_argument("--pre-roll", type=float, default=1.0)
    parser.add_argument("--speech-duration", type=float, default=2.0)
    parser.add_argument("--post-roll", type=float, default=1.0)
    parser.add_argument("--distance", default="unspecified")
    parser.add_argument("--head-orientation", default="unspecified")
    parser.add_argument("--speaking-pace", default="unspecified")
    parser.add_argument("--volume", default="unspecified")
    parser.add_argument("--emphasis", default="unspecified")
    parser.add_argument("--room-condition", default="unspecified")
    parser.add_argument("--background-condition", default="unspecified")
    args = parser.parse_args()
    if args.count < 1 or min(args.pre_roll, args.speech_duration, args.post_roll) < 0:
        parser.error("--count must be positive and recording durations cannot be negative.")
    if args.pre_roll + args.speech_duration + args.post_roll <= 0:
        parser.error("The total recording duration must be positive.")
    import sounddevice as sd
    import soundfile as sf

    directory = v3_real_positive_root() / args.partition
    for number in range(args.count):
        input(f"[{number + 1}/{args.count}] Press Enter when ready to begin capture.")
        destination = next_recording_path(directory, "positive")
        record = _capture_take(
            "positive",
            destination,
            args.source_device,
            args.pre_roll,
            args.speech_duration,
            args.post_roll,
            sd,
            sf,
        )
        record["partition"] = args.partition
        record["collection_conditions"] = {
            "distance": args.distance,
            "head_orientation": args.head_orientation,
            "speaking_pace": args.speaking_pace,
            "volume": args.volume,
            "emphasis": args.emphasis,
            "room_condition": args.room_condition,
            "background_condition": args.background_condition,
        }
        _append_metadata(directory, record)
        outcome = "Accepted:" if record["status"] == "accepted" else "Rejected:"
        print(outcome, record["recording_id"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
