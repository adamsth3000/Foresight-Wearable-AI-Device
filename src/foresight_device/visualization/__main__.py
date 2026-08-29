"""Dedicated Phase 1E recorded-event rendering entry point."""

from __future__ import annotations

import argparse
from pathlib import Path

from foresight_device.annotation.store import AnnotationStore

from .ffmpeg_renderer import FfmpegOverlayRenderer
from .overlay import OverlayTimeline
from .perception_loader import load_perception


def main() -> int:
    parser = argparse.ArgumentParser(description="Render recorded event perception overlays.")
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--data-root", type=Path, default=Path("data/capture"))
    parser.add_argument("--font-file", type=Path)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    options = parser.parse_args()
    event_dir = options.data_root / "events" / options.event_id
    perception = load_perception(event_dir / "event_perception.json")
    store = AnnotationStore(
        event_dir / "event_annotations.json",
        event_id=perception.event_id,
        observation_ids={item.observation_id for item in perception.observations},
    )
    renderer = FfmpegOverlayRenderer(
        ffmpeg_executable=options.ffmpeg,
        ffprobe_executable=options.ffprobe,
        font_file=options.font_file,
    )
    renderer.render(
        event_dir / "event.mp4",
        event_dir / "event_perception_annotated.mp4",
        OverlayTimeline(perception.observations, store.load()),
    )
    print(f"Annotated event video written: {event_dir / 'event_perception_annotated.mp4'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
