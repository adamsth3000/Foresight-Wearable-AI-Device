"""Compatibility tests for the optional Grounding DINO adapter."""

from __future__ import annotations

from collections.abc import Iterator
from contextlib import nullcontext
from typing import Any

import pytest

from foresight_device.perception.grounding_dino import DetectorResultError, GroundingDinoDetector
from foresight_device.perception.models import SampledFrame


class _FakeTensor:
    def __init__(self, value: Any) -> None:
        self.value = value

    def to(self, device: str) -> _FakeTensor:
        self.device = device
        return self

    def tolist(self) -> Any:
        return self.value

    def __float__(self) -> float:
        return float(self.value)

    def __int__(self) -> int:
        return int(self.value)

    @property
    def ndim(self) -> int:
        return 0

    def item(self) -> Any:
        return self.value


class _FakeTorch:
    @staticmethod
    def no_grad() -> Iterator[None]:
        return nullcontext()

    @staticmethod
    def tensor(value: Any) -> _FakeTensor:
        return _FakeTensor(value)


class _FakeImage:
    def convert(self, mode: str) -> _FakeImage:
        assert mode == "RGB"
        return self


class _FakeImageModule:
    @staticmethod
    def open(_: Any) -> _FakeImage:
        return _FakeImage()


class _FakeModel:
    def __call__(self, **inputs: _FakeTensor) -> object:
        assert all(tensor.device == "cpu" for tensor in inputs.values())
        return object()


class _ModernProcessor:
    def __init__(self) -> None:
        self.text: str | None = None

    def __call__(self, **kwargs: Any) -> dict[str, _FakeTensor]:
        self.text = kwargs["text"]
        return {"input_ids": _FakeTensor([1]), "pixel_values": _FakeTensor([2])}

    def post_process_grounded_object_detection(
        self,
        outputs: object,
        input_ids: _FakeTensor,
        *,
        threshold: float,
        text_threshold: float,
        target_sizes: _FakeTensor,
    ) -> list[dict[str, list[Any]]]:
        assert outputs is not None
        assert input_ids.value == [1]
        assert threshold == 0.35
        assert text_threshold == 0.25
        assert target_sizes.value == [[720, 1280]]
        return [
            {
                "boxes": [_FakeTensor([128.0, 72.0, 640.0, 360.0])],
                "scores": [_FakeTensor(0.9)],
                "text_labels": ["person"],
            }
        ]


class _LegacyProcessor(_ModernProcessor):
    def post_process_grounded_object_detection(
        self,
        outputs: object,
        input_ids: _FakeTensor,
        *,
        box_threshold: float,
        text_threshold: float,
        target_sizes: _FakeTensor,
    ) -> list[dict[str, list[Any]]]:
        assert outputs is not None
        assert input_ids.value == [1]
        assert box_threshold == 0.35
        assert text_threshold == 0.25
        assert target_sizes.value == [[720, 1280]]
        return [{"boxes": [], "scores": [], "labels": []}]


class _PromptLevelTextLabelsProcessor(_ModernProcessor):
    def post_process_grounded_object_detection(
        self,
        outputs: object,
        input_ids: _FakeTensor,
        *,
        threshold: float,
        text_threshold: float,
        target_sizes: _FakeTensor,
    ) -> list[dict[str, list[Any]]]:
        assert outputs is not None
        assert input_ids.value == [1]
        assert threshold == 0.35
        assert text_threshold == 0.25
        assert target_sizes.value == [[720, 1280]]
        return [
            {
                "boxes": [_FakeTensor([0.0, 0.0, 100.0, 100.0])],
                "scores": [_FakeTensor(0.8)],
                "labels": [_FakeTensor(1)],
                "text_labels": ["person", "car"],
            }
        ]


class _InconsistentProcessor(_ModernProcessor):
    def post_process_grounded_object_detection(
        self,
        outputs: object,
        input_ids: _FakeTensor,
        *,
        threshold: float,
        text_threshold: float,
        target_sizes: _FakeTensor,
    ) -> list[dict[str, list[Any]]]:
        assert outputs is not None
        assert input_ids.value == [1]
        assert threshold == 0.35
        assert text_threshold == 0.25
        assert target_sizes.value == [[720, 1280]]
        return [
            {
                "boxes": [_FakeTensor([0.0, 0.0, 100.0, 100.0])],
                "scores": [_FakeTensor(0.8)],
                "labels": [_FakeTensor(3)],
                "text_labels": ["person", "car"],
            }
        ]


class _EmptyWrappedLabelsProcessor(_ModernProcessor):
    def __init__(self, *, nested: bool) -> None:
        super().__init__()
        self.nested = nested

    def post_process_grounded_object_detection(
        self,
        outputs: object,
        input_ids: _FakeTensor,
        *,
        threshold: float,
        text_threshold: float,
        target_sizes: _FakeTensor,
    ) -> list[dict[str, list[Any]]]:
        assert outputs is not None
        assert input_ids.value == [1]
        assert threshold == 0.35
        assert text_threshold == 0.25
        assert target_sizes.value == [[720, 1280]]
        wrappers: list[Any] = [[]] if self.nested else ["person"]
        return [{"boxes": [], "scores": [], "labels": wrappers, "text_labels": wrappers}]


def _detector(processor: object, monkeypatch: pytest.MonkeyPatch) -> GroundingDinoDetector:
    detector = GroundingDinoDetector()
    detector._processor = processor
    detector._model = _FakeModel()
    monkeypatch.setattr("foresight_device.perception.grounding_dino._torch", _FakeTorch)
    monkeypatch.setattr(
        "foresight_device.perception.grounding_dino._image_module", _FakeImageModule
    )
    return detector


@pytest.mark.unit
def test_current_transformers_threshold_and_text_labels_are_supported(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    processor = _ModernProcessor()
    detections = _detector(processor, monkeypatch).detect(
        SampledFrame(0, 0.0, 1280, 720, b"png"), ("person", "car")
    )

    assert len(detections) == 1
    assert processor.text == "person. car."
    assert detections[0].label == "person"
    assert detections[0].prompt == "person"
    assert detections[0].bounding_box.as_list() == [0.1, 0.1, 0.5, 0.5]


@pytest.mark.unit
def test_legacy_box_threshold_api_remains_supported(monkeypatch: pytest.MonkeyPatch) -> None:
    detections = _detector(_LegacyProcessor(), monkeypatch).detect(
        SampledFrame(0, 0.0, 1280, 720, b"png"), ("person",)
    )

    assert detections == ()


@pytest.mark.unit
def test_prompt_level_text_labels_map_numeric_per_detection_labels(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    detections = _detector(_PromptLevelTextLabelsProcessor(), monkeypatch).detect(
        SampledFrame(0, 0.0, 1280, 720, b"png"), ("person", "car")
    )

    assert [detection.label for detection in detections] == ["car"]


@pytest.mark.unit
def test_ambiguous_label_cardinality_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    detector = _detector(_InconsistentProcessor(), monkeypatch)

    with pytest.raises(DetectorResultError, match="ambiguous detection labels"):
        detector.detect(SampledFrame(0, 0.0, 1280, 720, b"png"), ("person", "car"))


@pytest.mark.unit
@pytest.mark.parametrize("nested", (False, True))
def test_zero_detections_ignore_prompt_or_nested_label_wrappers(
    monkeypatch: pytest.MonkeyPatch,
    nested: bool,
) -> None:
    detector = _detector(_EmptyWrappedLabelsProcessor(nested=nested), monkeypatch)

    assert detector.detect(SampledFrame(0, 0.0, 1280, 720, b"png"), ("person",)) == ()
