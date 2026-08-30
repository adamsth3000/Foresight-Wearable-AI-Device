"""Independent high-frequency sampling into an immutable body artifact."""

from __future__ import annotations

import hashlib
import json
from datetime import UTC, datetime
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

from foresight_device.perception.event_media import EventMediaResolutionError, resolve_event_media
from foresight_device.perception.frame_sampler import FrameSampler

from .models import HandObservation, HandTrack
from .provider import BodyPerceptionProvider
from .tracking import track_hands


def process(
    event_dir: Path, sampler: FrameSampler, provider: BodyPerceptionProvider, *, interval: float
) -> tuple[Path, int, int, int]:
    try:
        media = resolve_event_media(event_dir)
    except EventMediaResolutionError as exc:
        raise ValueError(str(exc)) from exc
    frames: list[dict[str, object]] = []
    observations: list[HandObservation] = []
    for frame in sampler.sample(media.path, interval):
        frames.append(
            {
                "frame_index": frame.frame_index,
                "media_timestamp_seconds": frame.media_timestamp_seconds,
            }
        )
        for index, detection in enumerate(provider.detect_hands(frame)):
            observation_id = str(
                uuid5(NAMESPACE_URL, f"{event_dir.name}:{frame.frame_index}:{index}")
            )
            observations.append(
                HandObservation(
                    observation_id,
                    event_dir.name,
                    frame.frame_index,
                    frame.media_timestamp_seconds,
                    provider.provider_identity,
                    detection.confidence,
                    detection.handedness,
                    detection.handedness_confidence,
                    detection.landmarks,
                )
            )
    tracks = track_hands(tuple(observations))
    path = event_dir / "event_body_perception.json"
    payload = {
        "schema_version": 1,
        "event_id": event_dir.name,
        # Retain the SHA-only key for older readers and add controlled source provenance.
        "source_media_sha256": media.sha256,
        "source_media": {
            "filename": media.relative_path,
            "sha256": media.sha256,
            "source": media.source,
        },
        "provider": provider.provider_identity,
        "configuration": {"sampling_interval_seconds": interval, "tracking": "wrist_geometry_v1"},
        "frames": frames,
        "hand_observations": [serialize_hand(item) for item in observations],
        "pose_observations": [],
        "hand_tracks": [serialize_track(item) for item in tracks],
        "created_at_utc": datetime.now(UTC).isoformat(),
    }
    temporary = path.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    temporary.replace(path)
    return path, len(frames), len(observations), len(tracks)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def serialize_hand(hand: HandObservation) -> dict[str, object]:
    return {
        "hand_observation_id": hand.hand_observation_id,
        "event_id": hand.event_id,
        "frame_index": hand.frame_index,
        "media_timestamp_seconds": hand.media_timestamp_seconds,
        "provider": hand.provider,
        "confidence": hand.confidence,
        "handedness": hand.handedness.value,
        "handedness_confidence": hand.handedness_confidence,
        "self_association": {
            "status": hand.self_association.status.value,
            "confidence": hand.self_association.confidence,
            "reasons": list(hand.self_association.reasons),
        },
        "landmarks": [
            {"name": point.name, "x": point.x, "y": point.y, "z": point.z}
            for point in hand.landmarks
        ],
    }


def serialize_track(track: HandTrack) -> dict[str, object]:
    return {
        "hand_track_id": track.hand_track_id,
        "observation_ids": list(track.observation_ids),
        "start_timestamp_seconds": track.start_timestamp_seconds,
        "end_timestamp_seconds": track.end_timestamp_seconds,
        "handedness": track.handedness.value,
        "mean_confidence": track.mean_confidence,
        "self_association": {
            "status": track.self_association.status.value,
            "confidence": track.self_association.confidence,
            "reasons": list(track.self_association.reasons),
        },
    }
