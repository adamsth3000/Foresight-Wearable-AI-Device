"""Pure, conservative hand geometry and temporal primitive recognition."""

from __future__ import annotations

from dataclasses import dataclass
from math import hypot
from uuid import NAMESPACE_URL, uuid5

from foresight_device.body_perception.models import HandObservation, HandTrack, NormalizedLandmark

from .models import GestureEventCandidate, HandState, SemanticGestureEvidence

NAMESPACE = uuid5(NAMESPACE_URL, "foresight-semantic-hand-gestures")
FINGERS = ("index", "middle", "ring", "pinky")


@dataclass(frozen=True, slots=True)
class HandStateConfig:
    """Normalized geometry thresholds, kept together for later physical tuning."""

    minimum_observation_confidence: float = 0.5
    minimum_palm_scale: float = 0.015
    extended_distance_margin: float = 0.45
    flexed_distance_margin: float = 0.25
    straightness_cosine: float = 0.7
    pinch_distance: float = 0.42
    open_spread: float = 0.4
    minimum_open_extended_fingers: int = 3
    minimum_closed_flexed_fingers: int = 3
    minimum_point_flexed_fingers: int = 2


@dataclass(frozen=True, slots=True)
class TemporalGestureConfig:
    """Minimum stable support before per-frame evidence becomes a gesture candidate."""

    minimum_observations: int = 3
    minimum_duration_seconds: float = 0.3
    minimum_consistency: float = 0.75


DEFAULT_HAND_STATE_CONFIG = HandStateConfig()
DEFAULT_TEMPORAL_GESTURE_CONFIG = TemporalGestureConfig()


@dataclass(frozen=True, slots=True)
class HandGeometryFeatures:
    palm_scale: float | None
    thumb_index_distance_normalized: float | None
    spread_normalized: float | None
    extended_fingers: tuple[str, ...]
    flexed_fingers: tuple[str, ...]
    finger_extension_margins: tuple[tuple[str, float], ...]


@dataclass(frozen=True, slots=True)
class HandStateClassification:
    state: HandState
    confidence: float
    features: HandGeometryFeatures
    reasons: tuple[str, ...]


def classify_hand(
    observation: HandObservation, config: HandStateConfig = DEFAULT_HAND_STATE_CONFIG
) -> HandStateClassification:
    """Classify one complete hand skeleton without inferring a user command or target."""

    empty = HandGeometryFeatures(None, None, None, (), (), ())
    if observation.confidence < config.minimum_observation_confidence:
        return HandStateClassification(HandState.UNKNOWN, 0.0, empty, ("low_hand_confidence",))
    points = {item.name: item for item in observation.landmarks}
    required = {"wrist", "thumb_tip", "index_tip", "middle_mcp", "pinky_mcp"}
    required.update(
        f"{finger}_{joint}" for finger in FINGERS for joint in ("mcp", "pip", "dip", "tip")
    )
    if not required.issubset(points):
        return HandStateClassification(HandState.UNKNOWN, 0.0, empty, ("incomplete_hand_skeleton",))
    scale = _distance(points["wrist"], points["middle_mcp"])
    if scale < config.minimum_palm_scale:
        return HandStateClassification(HandState.UNKNOWN, 0.0, empty, ("degenerate_palm_scale",))

    margins: list[tuple[str, float]] = []
    extended: list[str] = []
    flexed: list[str] = []
    for finger in FINGERS:
        margin, straightness = _finger_extension(points, finger, scale)
        margins.append((finger, margin))
        if margin >= config.extended_distance_margin and straightness >= config.straightness_cosine:
            extended.append(finger)
        if margin <= config.flexed_distance_margin:
            flexed.append(finger)
    pinch = _distance(points["thumb_tip"], points["index_tip"]) / scale
    spread = _spread(points, scale)
    features = HandGeometryFeatures(
        scale,
        pinch,
        spread,
        tuple(extended),
        tuple(flexed),
        tuple(margins),
    )
    margin_by_finger = dict(margins)
    if pinch <= config.pinch_distance:
        confidence = _below_margin(pinch, config.pinch_distance)
        return HandStateClassification(
            HandState.PINCH, confidence, features, ("thumb_index_close",)
        )
    if (
        "index" in extended
        and len([finger for finger in flexed if finger != "index"])
        >= config.minimum_point_flexed_fingers
    ):
        evidence = [_above_margin(margin_by_finger["index"], config.extended_distance_margin)]
        evidence.extend(
            _below_margin(margin_by_finger[finger], config.flexed_distance_margin)
            for finger in flexed
            if finger != "index"
        )
        return HandStateClassification(
            HandState.POINT, min(evidence), features, ("index_extended", "other_fingers_flexed")
        )
    if len(extended) >= config.minimum_open_extended_fingers and spread >= config.open_spread:
        confidence = min(
            min(
                _above_margin(margin_by_finger[item], config.extended_distance_margin)
                for item in extended
            ),
            _above_margin(spread, config.open_spread),
        )
        return HandStateClassification(
            HandState.OPEN_HAND, confidence, features, ("multiple_fingers_extended", "hand_spread")
        )
    if len(flexed) >= config.minimum_closed_flexed_fingers:
        confidence = min(
            _below_margin(margin_by_finger[item], config.flexed_distance_margin) for item in flexed
        )
        return HandStateClassification(
            HandState.CLOSED_HAND, confidence, features, ("multiple_fingers_flexed",)
        )
    return HandStateClassification(HandState.UNKNOWN, 0.0, features, ("ambiguous_hand_geometry",))


def detect_semantic_events(
    observations: tuple[HandObservation, ...],
    tracks: tuple[HandTrack, ...],
    *,
    hand_config: HandStateConfig = DEFAULT_HAND_STATE_CONFIG,
    temporal_config: TemporalGestureConfig = DEFAULT_TEMPORAL_GESTURE_CONFIG,
) -> tuple[GestureEventCandidate, ...]:
    """Promote only temporally stable same-state runs into semantic candidates."""

    by_id = {item.hand_observation_id: item for item in observations}
    candidates: list[GestureEventCandidate] = []
    for track in tracks:
        classified = [
            (by_id[identifier], classify_hand(by_id[identifier], hand_config))
            for identifier in track.observation_ids
            if identifier in by_id
        ]
        candidates.extend(_stable_runs(track, classified, temporal_config))
    return tuple(
        sorted(candidates, key=lambda item: (item.start_timestamp_seconds, item.gesture_event_id))
    )


def classifications_for(
    observations: tuple[HandObservation, ...], config: HandStateConfig = DEFAULT_HAND_STATE_CONFIG
) -> tuple[tuple[HandObservation, HandStateClassification], ...]:
    """Return per-frame evidence for a non-destructive evaluation report."""

    return tuple((item, classify_hand(item, config)) for item in observations)


def _stable_runs(
    track: HandTrack,
    classified: list[tuple[HandObservation, HandStateClassification]],
    config: TemporalGestureConfig,
) -> tuple[GestureEventCandidate, ...]:
    runs: list[list[tuple[HandObservation, HandStateClassification]]] = []
    current: list[tuple[HandObservation, HandStateClassification]] = []
    current_state = HandState.UNKNOWN
    for item in classified:
        state = item[1].state
        if state == HandState.UNKNOWN or (current and state != current_state):
            if current:
                runs.append(current)
            current = []
        if state != HandState.UNKNOWN:
            current.append(item)
            current_state = state
        else:
            current_state = HandState.UNKNOWN
    if current:
        runs.append(current)
    return tuple(
        candidate for run in runs if (candidate := _candidate(track, run, config)) is not None
    )


def _candidate(
    track: HandTrack,
    run: list[tuple[HandObservation, HandStateClassification]],
    config: TemporalGestureConfig,
) -> GestureEventCandidate | None:
    start, end = run[0][0], run[-1][0]
    duration = end.media_timestamp_seconds - start.media_timestamp_seconds
    consistency = 1.0
    if (
        len(run) < config.minimum_observations
        or duration < config.minimum_duration_seconds
        or consistency < config.minimum_consistency
    ):
        return None
    peak, peak_classification = max(
        run,
        key=lambda item: (
            item[1].confidence,
            -item[0].media_timestamp_seconds,
            item[0].hand_observation_id,
        ),
    )
    features = peak_classification.features
    if features.palm_scale is None:
        return None
    tip = peak.fingertip
    evidence = SemanticGestureEvidence(
        support_observation_count=len(run),
        duration_seconds=duration,
        consistency=consistency,
        peak_observation_id=peak.hand_observation_id,
        palm_scale=features.palm_scale,
        thumb_index_distance_normalized=features.thumb_index_distance_normalized,
        extended_fingers=features.extended_fingers,
        flexed_fingers=features.flexed_fingers,
        reasons=peak_classification.reasons,
    )
    confidence = sum(item[1].confidence for item in run) / len(run)
    state = peak_classification.state
    return GestureEventCandidate(
        str(
            uuid5(
                NAMESPACE,
                f"{track.hand_track_id}:{state.value}:{start.hand_observation_id}:{end.hand_observation_id}",
            )
        ),
        track.event_id,
        track.hand_track_id,
        tuple(item[0].hand_observation_id for item in run),
        start.media_timestamp_seconds,
        end.media_timestamp_seconds,
        peak.media_timestamp_seconds,
        state.value,
        confidence,
        0.0,
        track.self_association.status,
        tip.x if tip else None,
        tip.y if tip else None,
        evidence,
    )


def _finger_extension(
    points: dict[str, NormalizedLandmark], finger: str, scale: float
) -> tuple[float, float]:
    wrist, mcp, pip, tip = (
        points["wrist"],
        points[f"{finger}_mcp"],
        points[f"{finger}_pip"],
        points[f"{finger}_tip"],
    )
    margin = (_distance(tip, wrist) - _distance(mcp, wrist)) / scale
    first = (pip.x - mcp.x, pip.y - mcp.y)
    second = (tip.x - pip.x, tip.y - pip.y)
    denominator = hypot(*first) * hypot(*second)
    straightness = (
        ((first[0] * second[0] + first[1] * second[1]) / denominator) if denominator else -1.0
    )
    return margin, straightness


def _spread(points: dict[str, NormalizedLandmark], scale: float) -> float:
    tips = [points[f"{finger}_tip"] for finger in FINGERS]
    return sum(_distance(first, second) for first, second in zip(tips, tips[1:], strict=False)) / (
        3 * scale
    )


def _distance(first: NormalizedLandmark, second: NormalizedLandmark) -> float:
    return hypot(first.x - second.x, first.y - second.y)


def _above_margin(value: float, threshold: float) -> float:
    return min(1.0, max(0.0, (value - threshold) / max(1e-6, 1.0 - threshold)))


def _below_margin(value: float, threshold: float) -> float:
    return min(1.0, max(0.0, (threshold - value) / max(1e-6, threshold)))
