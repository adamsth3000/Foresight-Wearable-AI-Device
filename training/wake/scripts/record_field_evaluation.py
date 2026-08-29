"""Record reviewable, pre-rolled field-evaluation WAVs outside all training inputs."""
# ruff: noqa: E501

from __future__ import annotations

import argparse
import time
import uuid
from datetime import UTC, datetime
from pathlib import Path

from field_evaluation import (
    FIELD_CATEGORIES,
    FIELD_EVALUATION_ROOT,
    SOURCE_DEVICES,
    append_metadata,
)
from record_evaluation import PROMPTS, next_recording_path

SAMPLE_RATE = 16_000


def _review_recording(path: Path, sounddevice: object) -> str:
    """Return an explicit accept/reject decision; optional playback stays local."""

    while True:
        decision = input("[a]ccept, [r]edo, or [p]lay back: ").strip().lower()
        if decision in {"a", "r"}:
            return decision
        if decision == "p":
            import soundfile as sf

            samples, sample_rate = sf.read(path, dtype="float32", always_2d=True)
            sounddevice.play(samples, samplerate=sample_rate)
            sounddevice.wait()
        else:
            print("Choose a, r, or p.")


def _capture_take(
    category: str,
    destination: Path,
    source_device: str,
    pre_roll: float,
    speech_duration: float,
    post_roll: float,
    sounddevice: object,
    soundfile: object,
) -> dict[str, object]:
    """Capture before cueing so the full phrase is retained with surrounding context."""

    total_duration = pre_roll + speech_duration + post_roll
    recording_id = uuid.uuid4().hex
    started_at = datetime.now(UTC).isoformat()
    print("Recording has started. Please wait for the cue.")
    recording = sounddevice.rec(
        round(total_duration * SAMPLE_RATE),
        samplerate=SAMPLE_RATE,
        channels=1,
        dtype="float32",
    )
    time.sleep(pre_roll)
    if category in {"positive", "ordinary_speech"}:
        print("[BEEP]")
        print(PROMPTS[category])
    else:
        print(PROMPTS[category])
    time.sleep(speech_duration + post_roll)
    sounddevice.wait()
    temporary = destination.with_suffix(".pending.wav")
    soundfile.write(temporary, recording, SAMPLE_RATE)
    decision = _review_recording(temporary, sounddevice)
    accepted = decision == "a"
    if accepted:
        temporary.replace(destination)
    else:
        temporary.unlink(missing_ok=True)
    return {
        "recording_id": recording_id,
        "timestamp": started_at,
        "source_device": source_device,
        "category": category,
        "status": "accepted" if accepted else "rejected",
        "path": str(destination) if accepted else None,
        "sample_rate": SAMPLE_RATE,
        "duration_seconds": total_duration,
        "pre_roll_seconds": pre_roll,
        "post_roll_seconds": post_roll,
        "analysis_start_seconds": max(0.0, pre_roll - 0.25),
        "analysis_window_seconds": 2.0,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("category", choices=FIELD_CATEGORIES)
    parser.add_argument("--count", type=int, default=1)
    parser.add_argument("--source-device", choices=SOURCE_DEVICES, default="laptop_mic")
    parser.add_argument("--pre-roll", type=float, default=1.0)
    parser.add_argument("--speech-duration", type=float, default=2.0)
    parser.add_argument("--post-roll", type=float, default=1.0)
    args = parser.parse_args()
    if args.count < 1 or min(args.pre_roll, args.speech_duration, args.post_roll) < 0:
        parser.error("--count must be positive and recording durations cannot be negative.")
    if args.pre_roll + args.speech_duration + args.post_roll <= 0:
        parser.error("The total recording duration must be positive.")
    import sounddevice as sd
    import soundfile as sf

    directory = FIELD_EVALUATION_ROOT / args.category
    for number in range(args.count):
        input(f"[{number + 1}/{args.count}] Press Enter when ready to begin capture.")
        destination = next_recording_path(directory, args.category)
        record = _capture_take(
            args.category,
            destination,
            args.source_device,
            args.pre_roll,
            args.speech_duration,
            args.post_roll,
            sd,
            sf,
        )
        append_metadata(directory, record)
        print("Accepted:" if record["status"] == "accepted" else "Rejected:", record["recording_id"])
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
