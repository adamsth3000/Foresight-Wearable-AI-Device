"""Conservative movement episodes without semantic target resolution."""

from __future__ import annotations

import math
from uuid import NAMESPACE_URL, uuid5

from foresight_device.body_perception.models import HandObservation, HandTrack

from .models import GestureEventCandidate

NAMESPACE = uuid5(NAMESPACE_URL, "foresight-gesture-events")


def detect_motion_events(
    observations: tuple[HandObservation, ...],
    tracks: tuple[HandTrack, ...],
    *,
    min_displacement: float = 0.04,
    min_duration: float = 0.2,
) -> tuple[GestureEventCandidate, ...]:
    by_id = {item.hand_observation_id: item for item in observations}
    events: list[GestureEventCandidate] = []
    for track in tracks:
        samples = [by_id[item] for item in track.observation_ids]
        pairs = list(zip(samples, samples[1:], strict=False))
        movement = [(first, second, wrist_distance(first, second)) for first, second in pairs]
        active = [item for item in movement if item[2] >= min_displacement]
        if not active:
            continue
        start, end = active[0][0], active[-1][1]
        if end.media_timestamp_seconds - start.media_timestamp_seconds < min_duration:
            continue
        peak = max(active, key=lambda item: (item[2], -item[1].media_timestamp_seconds))[1]
        tip = peak.fingertip
        confidence = min(1.0, max(item[2] for item in active) / 0.2)
        events.append(
            GestureEventCandidate(
                str(
                    uuid5(
                        NAMESPACE,
                        f"{track.hand_track_id}:{start.hand_observation_id}:{end.hand_observation_id}",
                    )
                ),
                track.event_id,
                track.hand_track_id,
                tuple(
                    item.hand_observation_id
                    for item in samples
                    if start.media_timestamp_seconds
                    <= item.media_timestamp_seconds
                    <= end.media_timestamp_seconds
                ),
                start.media_timestamp_seconds,
                end.media_timestamp_seconds,
                peak.media_timestamp_seconds,
                "unknown_motion",
                confidence,
                confidence,
                track.self_association.status,
                tip.x if tip else None,
                tip.y if tip else None,
            )
        )
    return tuple(events)


def wrist_distance(first: HandObservation, second: HandObservation) -> float:
    return math.hypot(first.wrist.x - second.wrist.x, first.wrist.y - second.wrist.y)
