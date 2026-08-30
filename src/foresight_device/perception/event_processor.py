"""Offline processing of promoted event media into normalized visual evidence."""

from __future__ import annotations

import json
from collections.abc import Sequence
from datetime import UTC, datetime
from pathlib import Path
from uuid import NAMESPACE_URL, uuid5

from .detector import Detector
from .event_media import EventMediaResolutionError, resolve_event_media
from .frame_sampler import FrameSampler
from .models import EventPerceptionResult, VisualObservation

PERCEPTION_SCHEMA_VERSION = 1
OBSERVATION_NAMESPACE = uuid5(NAMESPACE_URL, "foresight-event-perception")


class EventPerceptionError(RuntimeError):
    """Raised when a promoted event cannot be processed safely."""


class EventPerceptionProcessor:
    """Run a detector over sampled event media and atomically replace its artifact."""

    def __init__(self, sampler: FrameSampler, detector: Detector) -> None:
        self._sampler = sampler
        self._detector = detector

    def process(
        self,
        event_dir: Path,
        *,
        prompts: Sequence[str],
        sample_interval_seconds: float = 1.0,
    ) -> EventPerceptionResult:
        """Process one promoted event directory without modifying its manifest."""

        clean_prompts = tuple(prompt.strip() for prompt in prompts if prompt.strip())
        if not clean_prompts:
            raise ValueError("at least one detector prompt is required")
        manifest = _load_manifest(event_dir / "manifest.json")
        event_id = _event_id(event_dir, manifest)
        try:
            media = resolve_event_media(event_dir)
        except EventMediaResolutionError as exc:
            raise EventPerceptionError(str(exc)) from exc
        media_path = media.path
        observations: list[VisualObservation] = []
        frames_processed = 0
        for frame in self._sampler.sample(media_path, sample_interval_seconds):
            frames_processed += 1
            detections = self._detector.detect(frame, clean_prompts)
            for detection_index, detection in enumerate(detections):
                observation_id = str(
                    uuid5(
                        OBSERVATION_NAMESPACE,
                        ":".join(
                            (
                                event_id,
                                str(frame.frame_index),
                                f"{frame.media_timestamp_seconds:.9f}",
                                detection.label,
                                str(detection_index),
                            )
                        ),
                    )
                )
                observations.append(
                    VisualObservation(
                        observation_id=observation_id,
                        event_id=event_id,
                        source_media_path=media.relative_path,
                        frame_index=frame.frame_index,
                        media_timestamp_seconds=frame.media_timestamp_seconds,
                        label=detection.label,
                        confidence=detection.confidence,
                        bounding_box=detection.bounding_box,
                        detector_backend=self._detector.backend_identity,
                        detector_model=self._detector.model_identity,
                        prompt=detection.prompt,
                    )
                )
        ordered_observations = tuple(
            sorted(
                observations,
                key=lambda item: (
                    item.media_timestamp_seconds,
                    item.frame_index,
                    item.label,
                    -item.confidence,
                    item.observation_id,
                ),
            )
        )
        output_path = event_dir / "event_perception.json"
        payload = {
            "schema_version": PERCEPTION_SCHEMA_VERSION,
            "event_id": event_id,
            "media": {
                "filename": media.relative_path,
                "sha256": media.sha256,
                "source": media.source,
            },
            "processing": {
                "created_at_utc": datetime.now(UTC).isoformat(),
                "frame_sampling_interval_seconds": sample_interval_seconds,
                "detector_backend": self._detector.backend_identity,
                "model": self._detector.model_identity,
                "prompts": list(clean_prompts),
                "timing_note": (
                    "media_timestamp_seconds is relative to the exact selected event media, "
                    "not Android elapsedRealtimeNanos."
                ),
            },
            "frames_processed": frames_processed,
            "observations": [observation.as_dict() for observation in ordered_observations],
        }
        _write_json_atomically(output_path, payload)
        return EventPerceptionResult(
            event_id,
            media_path,
            output_path,
            frames_processed,
            ordered_observations,
        )


def _load_manifest(path: Path) -> dict[str, object]:
    if not path.is_file():
        return {}
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise EventPerceptionError(f"event manifest could not be read: {path}") from exc
    if not isinstance(payload, dict):
        raise EventPerceptionError(f"event manifest is not a JSON object: {path}")
    return payload


def _event_id(event_dir: Path, manifest: dict[str, object]) -> str:
    event_id = manifest.get("event_id", event_dir.name)
    if not isinstance(event_id, str) or not event_id:
        raise EventPerceptionError("event manifest contains an invalid event_id")
    return event_id


def _write_json_atomically(path: Path, payload: dict[str, object]) -> None:
    temporary_path = path.with_suffix(".json.tmp")
    temporary_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    temporary_path.replace(path)
