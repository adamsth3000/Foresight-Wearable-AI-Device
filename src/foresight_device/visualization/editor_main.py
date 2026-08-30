"""Dedicated launcher for the interactive Phase 1E perception editor."""

from __future__ import annotations

import argparse
from pathlib import Path

from foresight_device.annotation.store import AnnotationStore
from foresight_device.annotation.track_store import TrackAnnotationStore
from foresight_device.body_perception.artifact import (
    ArtifactProvenanceError,
    ArtifactValidationError,
    load_body_artifact,
    verify_body_media,
)
from foresight_device.gestures.artifact import (
    GestureArtifactProvenanceError,
    load_gesture_artifact,
)
from foresight_device.perception.event_media import (
    EventMediaResolutionError,
    ResolvedEventMedia,
    resolve_event_media,
)
from foresight_device.tracking.artifact import TrackingArtifactError, load_track_index

from .editor_controller import EditorController
from .editor_window import launch_editor
from .gesture_timeline import GestureTimeline
from .perception_loader import PerceptionArtifactError, load_perception


def main() -> int:
    parser = argparse.ArgumentParser(description="Review and annotate a recorded Foresight event.")
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--data-root", type=Path, default=Path("data/capture"))
    parser.add_argument("--ffmpeg", default="ffmpeg")
    parser.add_argument("--ffprobe", default="ffprobe")
    options = parser.parse_args()
    event_dir = options.data_root / "events" / options.event_id
    try:
        media = resolve_event_media(event_dir)
        controller = load_editor_controller(event_dir, media)
    except (
        ArtifactProvenanceError,
        ArtifactValidationError,
        EventMediaResolutionError,
        GestureArtifactProvenanceError,
        PerceptionArtifactError,
        TrackingArtifactError,
    ) as exc:
        parser.error(str(exc))
    launch_editor(
        controller,
        media.path,
        ffmpeg_executable=options.ffmpeg,
        ffprobe_executable=options.ffprobe,
    )
    return 0


def load_editor_controller(
    event_dir: Path, resolved_media: ResolvedEventMedia | None = None
) -> EditorController:
    """Build an editor controller, optionally adding validated gesture visualization."""
    perception = load_perception(event_dir / "event_perception.json", resolved_media=resolved_media)
    store = AnnotationStore(
        event_dir / "event_annotations.json",
        event_id=perception.event_id,
        observation_ids={item.observation_id for item in perception.observations},
    )
    track_path = event_dir / "event_tracks.json"
    track_ids = (
        load_track_index(track_path, perception.observations) if track_path.is_file() else {}
    )
    track_store = TrackAnnotationStore(
        event_dir / "event_track_annotations.json",
        event_id=perception.event_id,
        track_ids=set(track_ids.values()),
    )
    body_path = event_dir / "event_body_perception.json"
    gesture_path = event_dir / "event_gestures.json"
    gesture_timeline = None
    if gesture_path.is_file() and not body_path.is_file():
        raise ArtifactValidationError("gesture artifact exists without event_body_perception.json")
    if body_path.is_file() and gesture_path.is_file():
        body = load_body_artifact(body_path, event_id=perception.event_id)
        if resolved_media is not None:
            verify_body_media(body, resolved_media)
        gestures = load_gesture_artifact(
            gesture_path,
            event_id=perception.event_id,
            body_artifact_path=body_path,
        )
        gesture_timeline = GestureTimeline.from_artifacts(body, gestures)
    return EditorController(
        perception.observations,
        store,
        track_ids,
        track_store,
        gesture_timeline=gesture_timeline,
    )


if __name__ == "__main__":
    raise SystemExit(main())
