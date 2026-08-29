"""Dedicated launcher for the interactive Phase 1E perception editor."""

from __future__ import annotations

import argparse
from pathlib import Path

from foresight_device.annotation.store import AnnotationStore
from foresight_device.annotation.track_store import TrackAnnotationStore
from foresight_device.tracking.artifact import TrackingArtifactError, load_track_index

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
    track_path = event_dir / "event_tracks.json"
    try:
        track_ids = (
            load_track_index(track_path, perception.observations) if track_path.is_file() else {}
        )
    except TrackingArtifactError as exc:
        parser.error(str(exc))
    track_store = TrackAnnotationStore(
        event_dir / "event_track_annotations.json",
        event_id=perception.event_id,
        track_ids=set(track_ids.values()),
    )
    launch_editor(
        EditorController(perception.observations, store, track_ids, track_store),
        event_dir / "event.mp4",
        ffmpeg_executable=options.ffmpeg,
        ffprobe_executable=options.ffprobe,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
