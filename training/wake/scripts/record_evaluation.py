"""Record short local WAV clips for prototype wake-model evaluation."""

from __future__ import annotations

import argparse
from pathlib import Path

from common import TRAINING_ROOT

VALID_CATEGORIES = ("positive", "ordinary_speech", "ambient", "tv_background", "noise")
PROMPTS = {
    "positive": 'Say "Hey Foresight" naturally.',
    "ordinary_speech": "Speak normally without saying the wake phrase.",
    "ambient": "Record ordinary room or outdoor ambient sound.",
    "tv_background": "Record television or other background media.",
    "noise": "Record non-speech environmental noise.",
}


def next_recording_path(directory: Path, category: str) -> Path:
    """Choose the next sequential filename without overwriting recordings."""

    directory.mkdir(parents=True, exist_ok=True)
    index = 1
    while (candidate := directory / f"{category}_{index:03d}.wav").exists():
        index += 1
    return candidate


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("category", choices=VALID_CATEGORIES)
    parser.add_argument("--count", type=int, default=1)
    parser.add_argument("--duration", type=float, default=2.0)
    args = parser.parse_args()
    if args.count < 1 or args.duration <= 0:
        parser.error("--count and --duration must be positive.")
    import sounddevice as sd
    import soundfile as sf

    directory = TRAINING_ROOT / "data" / "evaluation" / args.category
    frames = round(args.duration * 16_000)
    for number in range(args.count):
        path = next_recording_path(directory, args.category)
        print(f"[{number + 1}/{args.count}] {PROMPTS[args.category]}")
        recording = sd.rec(frames, samplerate=16_000, channels=1, dtype="float32")
        sd.wait()
        sf.write(path, recording, 16_000)
        print(f"Saved {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
