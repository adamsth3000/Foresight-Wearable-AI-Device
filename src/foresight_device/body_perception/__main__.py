"""Run independent high-frequency body sampling with a dependency-free backend."""

from __future__ import annotations

import argparse
from pathlib import Path

from foresight_device.perception.frame_sampler import FfmpegFrameSampler

from .pipeline import process
from .provider import BodyPerceptionProvider, EmptyBodyProvider, ProviderUnavailableError


def main() -> int:
    parser = argparse.ArgumentParser(description="Run high-frequency offline hand perception.")
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--data-root", type=Path, default=Path("data/capture"))
    parser.add_argument("--sample-interval", type=float, default=0.2)
    parser.add_argument("--backend", choices=("mediapipe", "empty"), default="mediapipe")
    parser.add_argument(
        "--mediapipe-model",
        type=Path,
        default=Path(r"C:\venvs\foresight-perception\models\hand_landmarker.task"),
    )
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    options = parser.parse_args()
    if options.backend == "mediapipe":
        from .mediapipe_hands import MediaPipeHandsProvider

        provider: BodyPerceptionProvider = MediaPipeHandsProvider(options.mediapipe_model)
    else:
        provider = EmptyBodyProvider()
    try:
        path, frames, observations, tracks = process(
            options.data_root / "events" / options.event_id,
            FfmpegFrameSampler(
                ffmpeg_executable=options.ffmpeg,
                ffprobe_executable=options.ffprobe,
            ),
            provider,
            interval=options.sample_interval,
        )
    except (ProviderUnavailableError, ValueError) as exc:
        parser.error(str(exc))
    print(
        f"Body perception complete: {frames} sampled frame(s), {observations} hand observation(s), "
        f"{tracks} hand track(s), {path}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
