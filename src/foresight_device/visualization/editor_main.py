"""Dedicated launcher for the interactive Phase 1E perception editor."""

from __future__ import annotations

import argparse
from pathlib import Path

from foresight_device.annotation.store import AnnotationStore

from .editor_controller import EditorController
from .editor_window import launch_editor
from .perception_loader import load_perception


def main() -> int:
    parser = argparse.ArgumentParser(description="Review and annotate a recorded Foresight event.")
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--data-root", type=Path, default=Path("data/capture"))
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
    launch_editor(
        EditorController(perception.observations, store),
        event_dir / "event.mp4",
        ffmpeg_executable=options.ffmpeg,
        ffprobe_executable=options.ffprobe,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
