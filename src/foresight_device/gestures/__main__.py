"""Analyze a validated body-perception artifact without decoding media again."""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict
from datetime import UTC, datetime
from pathlib import Path

from foresight_device.body_perception.artifact import load_body_artifact, sha256

from .analysis import detect_gesture_events
from .hand_state import (
    DEFAULT_HAND_STATE_CONFIG,
    DEFAULT_TEMPORAL_GESTURE_CONFIG,
    classifications_for,
)
from .models import GestureEventCandidate


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Analyze persisted hand motion into gesture candidates."
    )
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--data-root", type=Path, default=Path("data/capture"))
    parser.add_argument(
        "--report-only",
        action="store_true",
        help="Print semantic hand-state evidence without writing event_gestures.json.",
    )
    options = parser.parse_args()

    event_dir = options.data_root / "events" / options.event_id
    body_path = event_dir / "event_body_perception.json"
    body = load_body_artifact(body_path, event_id=options.event_id)
    events = detect_gesture_events(body.observations, body.tracks)
    if options.report_only:
        report = {
            "event_id": options.event_id,
            "source_body_perception": {"filename": body_path.name, "sha256": sha256(body_path)},
            "hand_states": [
                {
                    "hand_observation_id": observation.hand_observation_id,
                    "media_timestamp_seconds": observation.media_timestamp_seconds,
                    "state": classification.state.value,
                    "confidence": classification.confidence,
                    "reasons": list(classification.reasons),
                }
                for observation, classification in classifications_for(body.observations)
            ],
            "stable_semantic_candidates": [
                serialize(item) for item in events if item.semantic_evidence is not None
            ],
        }
        print(json.dumps(report, indent=2))
        return 0
    output = event_dir / "event_gestures.json"
    payload = {
        "schema_version": 1,
        "event_id": options.event_id,
        "source_body_perception": {"filename": body_path.name, "sha256": sha256(body_path)},
        "configuration": {
            "backend": "wrist_motion_and_hand_state_v1",
            "semantic_hand_states": {
                "algorithm": "geometric_hand_state_v1",
                "geometry": asdict(DEFAULT_HAND_STATE_CONFIG),
                "temporal": asdict(DEFAULT_TEMPORAL_GESTURE_CONFIG),
            },
        },
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
        "semantic_evidence": (
            {
                "support_observation_count": candidate.semantic_evidence.support_observation_count,
                "duration_seconds": candidate.semantic_evidence.duration_seconds,
                "consistency": candidate.semantic_evidence.consistency,
                "peak_observation_id": candidate.semantic_evidence.peak_observation_id,
                "palm_scale": candidate.semantic_evidence.palm_scale,
                "thumb_index_distance_normalized": (
                    candidate.semantic_evidence.thumb_index_distance_normalized
                ),
                "extended_fingers": list(candidate.semantic_evidence.extended_fingers),
                "flexed_fingers": list(candidate.semantic_evidence.flexed_fingers),
                "reasons": list(candidate.semantic_evidence.reasons),
            }
            if candidate.semantic_evidence is not None
            else None
        ),
    }


if __name__ == "__main__":
    raise SystemExit(main())
