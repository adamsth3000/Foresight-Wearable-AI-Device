"""Run deterministic derived tracking for one immutable perception artifact."""

from __future__ import annotations

import argparse
from pathlib import Path

from .artifact import TrackingArtifactError, write_tracking_artifact
from .baseline import BaselineTracker, TrackingConfig
from .input import TrackingInputError, load_tracking_input


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Create persistent visual entity tracks for one event."
    )
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--data-root", type=Path, default=Path("data/capture"))
    parser.add_argument("--max-center-distance", type=float, default=0.65)
    parser.add_argument("--max-time-gap-seconds", type=float, default=6.5)
    options = parser.parse_args()
    event_dir = options.data_root / "events" / options.event_id
    try:
        perception = load_tracking_input(event_dir / "event_perception.json")
        result = BaselineTracker(
            TrackingConfig(options.max_center_distance, options.max_time_gap_seconds)
        ).track(perception.observations)
        if result.event_id != perception.event_id and result.event_id:
            raise TrackingArtifactError("tracker returned a mismatched event id")
        if not result.event_id:
            result = type(result)(
                perception.event_id, result.tracks, result.backend_identity, result.configuration
            )
        output = write_tracking_artifact(event_dir, result, observations=perception.observations)
    except (TrackingArtifactError, TrackingInputError, ValueError) as exc:
        parser.error(str(exc))
    frame_count = len({item.frame_index for item in perception.observations})
    print(
        f"Tracking complete: {frame_count} perception frame(s), "
        f"{len(perception.observations)} observation(s), {len(result.tracks)} track(s), {output}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
