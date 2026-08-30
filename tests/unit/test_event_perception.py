"""Dependency-free event-perception pipeline tests."""

from __future__ import annotations

import hashlib
import json
from collections.abc import Iterator, Sequence
from pathlib import Path

import pytest

from foresight_device.perception.event_processor import (
    EventPerceptionError,
    EventPerceptionProcessor,
)
from foresight_device.perception.models import (
    DetectorDetection,
    NormalizedBoundingBox,
    SampledFrame,
)


class FakeSampler:
    """Yield frames supplied by a test without FFmpeg or persistent images."""

    def __init__(self, frames: Sequence[SampledFrame]) -> None:
        self.frames = frames
        self.media_paths: list[Path] = []

    def sample(self, media_path: Path, interval_seconds: float) -> Iterator[SampledFrame]:
        self.media_paths.append(media_path)
        del interval_seconds
        yield from self.frames


class FakeDetector:
    """Return supplied detections while retaining received prompt order."""

    backend_identity = "fake_detector"
    model_identity = "fake-v1"

    def __init__(self, detections: Sequence[DetectorDetection]) -> None:
        self.detections = detections
        self.prompts: tuple[str, ...] | None = None

    def detect(self, frame: SampledFrame, prompts: Sequence[str]) -> Sequence[DetectorDetection]:
        del frame
        self.prompts = tuple(prompts)
        return self.detections


def _event_dir(
    tmp_path: Path, *, event_id: str = "c4426cce-1ac2-47d6-9e9f-0c8d5c9e0543"
) -> tuple[Path, str]:
    event_dir = tmp_path / "events" / event_id
    event_dir.mkdir(parents=True)
    media = event_dir / "event.mp4"
    media.write_bytes(b"event evidence")
    checksum = hashlib.sha256(media.read_bytes()).hexdigest()
    (event_dir / "manifest.json").write_text(
        json.dumps({"event_id": event_id, "media": {"sha256": checksum}}), encoding="utf-8"
    )
    return event_dir, checksum


def _frame(timestamp: float, index: int) -> SampledFrame:
    return SampledFrame(index, timestamp, 1280, 720, b"in-memory-png")


@pytest.mark.unit
def test_pipeline_writes_ordered_observations_with_manifest_provenance(tmp_path: Path) -> None:
    event_dir, checksum = _event_dir(tmp_path)
    box = NormalizedBoundingBox(0.1, 0.2, 0.3, 0.4)
    detector = FakeDetector(
        (
            DetectorDetection("tree", 0.5, box, "tree"),
            DetectorDetection("car", 0.9, box, "car"),
        )
    )
    sampler = FakeSampler((_frame(1.0, 30), _frame(0.0, 0)))
    processor = EventPerceptionProcessor(sampler, detector)

    result = processor.process(event_dir, prompts=("person", "car", "tree"))
    payload = json.loads(result.output_path.read_text(encoding="utf-8"))

    assert result.frames_processed == 2
    assert detector.prompts == ("person", "car", "tree")
    assert payload["schema_version"] == 1
    assert payload["media"] == {
        "filename": "event.mp4",
        "sha256": checksum,
        "source": "network_capture",
    }
    assert sampler.media_paths == [event_dir / "event.mp4"]
    assert [item["label"] for item in payload["observations"]] == ["car", "tree", "car", "tree"]
    assert [item["media_timestamp_seconds"] for item in payload["observations"]] == [
        0.0,
        0.0,
        1.0,
        1.0,
    ]
    assert payload["observations"][0]["bounding_box"] == [0.1, 0.2, 0.3, 0.4]


@pytest.mark.unit
def test_empty_detector_result_and_rerun_replace_the_artifact(tmp_path: Path) -> None:
    event_dir, _ = _event_dir(tmp_path)
    processor = EventPerceptionProcessor(FakeSampler((_frame(0.0, 0),)), FakeDetector(()))

    first = processor.process(event_dir, prompts=("dog",))
    first.output_path.write_text("obsolete", encoding="utf-8")
    second = processor.process(event_dir, prompts=("dog",))
    payload = json.loads(second.output_path.read_text(encoding="utf-8"))

    assert first.output_path == second.output_path
    assert payload["frames_processed"] == 1
    assert payload["observations"] == []


@pytest.mark.unit
def test_missing_media_is_rejected_without_creating_an_artifact(tmp_path: Path) -> None:
    event_dir = tmp_path / "events" / "c4426cce-1ac2-47d6-9e9f-0c8d5c9e0543"
    event_dir.mkdir(parents=True)
    processor = EventPerceptionProcessor(FakeSampler(()), FakeDetector(()))

    with pytest.raises(EventPerceptionError, match="not found"):
        processor.process(event_dir, prompts=("person",))

    assert not (event_dir / "event_perception.json").exists()
