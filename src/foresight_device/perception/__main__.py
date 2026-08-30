"""Dedicated Phase 1D entry point that avoids the mixed top-level CLI."""

from __future__ import annotations

import argparse
from collections.abc import Sequence
from pathlib import Path

from .detector import Detector, DetectorUnavailableError
from .event_processor import EventPerceptionError, EventPerceptionProcessor
from .frame_sampler import FfmpegFrameSampler, FrameSamplingError
from .grounding_dino import GroundingDinoDetector
from .models import DetectorDetection, SampledFrame

DEFAULT_PROMPTS = ("person", "car", "bicycle", "tree", "dog", "sign")


class _EmptyDetector:
    """Dependency-free diagnostic backend for pipeline and filesystem checks."""

    @property
    def backend_identity(self) -> str:
        return "empty"

    @property
    def model_identity(self) -> str:
        return "none"

    def detect(self, frame: SampledFrame, prompts: Sequence[str]) -> Sequence[DetectorDetection]:
        del frame, prompts
        return ()


def main() -> int:
    """Process one promoted event without touching capture or top-level wake CLI."""

    parser = argparse.ArgumentParser(description="Run offline Phase 1D event perception.")
    parser.add_argument("--event-id", required=True, help="Promoted event directory name.")
    parser.add_argument(
        "--data-root",
        type=Path,
        default=Path("data/capture"),
        help="Capture data root containing promoted events/<event_id>/ directories.",
    )
    parser.add_argument("--sample-interval", type=float, default=1.0)
    parser.add_argument("--prompt", action="append", dest="prompts")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    parser.add_argument("--backend", choices=("grounding-dino", "empty"), default="grounding-dino")
    options = parser.parse_args()
    detector: Detector = (
        GroundingDinoDetector() if options.backend == "grounding-dino" else _EmptyDetector()
    )
    processor = EventPerceptionProcessor(
        FfmpegFrameSampler(
            ffmpeg_executable=options.ffmpeg,
            ffprobe_executable=options.ffprobe,
        ),
        detector,
    )
    try:
        result = processor.process(
            options.data_root / "events" / options.event_id,
            prompts=tuple(options.prompts or DEFAULT_PROMPTS),
            sample_interval_seconds=options.sample_interval,
        )
    except (DetectorUnavailableError, EventPerceptionError, FrameSamplingError, ValueError) as exc:
        parser.error(str(exc))
    print(
        f"Perception complete: {result.frames_processed} frame(s), "
        f"{len(result.observations)} observation(s), {result.output_path}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
