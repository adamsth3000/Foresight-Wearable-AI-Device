"""Analyze a validated body-perception artifact without decoding media again."""

from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime
from pathlib import Path

from foresight_device.body_perception.artifact import load_body_artifact, sha256

from .analysis import detect_motion_events
from .models import GestureEventCandidate


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Analyze persisted hand motion into gesture candidates."
    )
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--data-root", type=Path, default=Path("data/capture"))
    options = parser.parse_args()

    event_dir = options.data_root / "events" / options.event_id
    body_path = event_dir / "event_body_perception.json"
    body = load_body_artifact(body_path, event_id=options.event_id)
    events = detect_motion_events(body.observations, body.tracks)
    output = event_dir / "event_gestures.json"
    payload = {
        "schema_version": 1,
        "event_id": options.event_id,
        "source_body_perception": {"filename": body_path.name, "sha256": sha256(body_path)},
        "configuration": {"backend": "wrist_motion_v1"},
        "gesture_events": [serialize(item) for item in events],
        "created_at_utc": datetime.now(UTC).isoformat(),
    }
    temporary = output.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    temporary.replace(output)
    print(
        f"Gesture analysis complete: {len(body.tracks)} hand track(s), "
        f"{len(events)} gesture candidate(s), {output}"
    )
    return 0


def serialize(candidate: GestureEventCandidate) -> dict[str, object]:
    return {
        "gesture_event_id": candidate.gesture_event_id,
        "event_id": candidate.event_id,
        "hand_track_id": candidate.hand_track_id,
        "observation_ids": list(candidate.observation_ids),
        "start_timestamp_seconds": candidate.start_timestamp_seconds,
        "end_timestamp_seconds": candidate.end_timestamp_seconds,
        "peak_timestamp_seconds": candidate.peak_timestamp_seconds,
        "gesture_type": candidate.gesture_type,
        "gesture_confidence": candidate.gesture_confidence,
        "motion_confidence": candidate.motion_confidence,
        "self_association_status": candidate.self_association_status.value,
        "fingertip": [candidate.fingertip_x, candidate.fingertip_y],
    }


if __name__ == "__main__":
    raise SystemExit(main())
