"""Deterministic wrist-position hand tracks for sparse high-frequency samples."""

from __future__ import annotations

import math

from .models import Handedness, HandObservation, HandTrack, SelfAssociation


def track_hands(
    observations: tuple[HandObservation, ...], *, max_distance: float = 0.25, max_gap: float = 0.6
) -> tuple[HandTrack, ...]:
    groups: list[list[HandObservation]] = []
    for observation in sorted(
        observations, key=lambda item: (item.media_timestamp_seconds, item.hand_observation_id)
    ):
        matches = [
            (distance(observation, group[-1]), index)
            for index, group in enumerate(groups)
            if compatible(observation, group[-1], max_gap)
        ]
        usable = [item for item in matches if item[0] <= max_distance]
        if usable:
            groups[min(usable)[1]].append(observation)
        else:
            groups.append([observation])
    return tuple(make_track(index, group) for index, group in enumerate(groups, 1))


def compatible(current: HandObservation, prior: HandObservation, max_gap: float) -> bool:
    hands_match = (
        current.handedness == Handedness.UNKNOWN
        or prior.handedness == Handedness.UNKNOWN
        or current.handedness == prior.handedness
    )
    return (
        hands_match and current.media_timestamp_seconds - prior.media_timestamp_seconds <= max_gap
    )


def distance(first: HandObservation, second: HandObservation) -> float:
    return math.hypot(first.wrist.x - second.wrist.x, first.wrist.y - second.wrist.y)


def make_track(index: int, group: list[HandObservation]) -> HandTrack:
    handedness = next(
        (item.handedness for item in group if item.handedness != Handedness.UNKNOWN),
        Handedness.UNKNOWN,
    )
    return HandTrack(
        f"H{index:03d}",
        group[0].event_id,
        tuple(item.hand_observation_id for item in group),
        group[0].media_timestamp_seconds,
        group[-1].media_timestamp_seconds,
        handedness,
        SelfAssociation(),
        sum(item.confidence for item in group) / len(group),
    )
