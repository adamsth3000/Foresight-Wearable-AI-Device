"""Contract tests for the isolated local live-Vision adapter."""

from __future__ import annotations

from io import BytesIO

import pytest

from foresight_device.live_vision.service import LiveVisionRequest, LiveVisionRequestError, LiveVisionService
from foresight_device.perception.models import DetectorDetection, NormalizedBoundingBox


class _FakeDetector:
    backend_identity = "fake_detector"
    model_identity = "fake-model"

    def detect(self, frame: object, prompts: tuple[str, ...]) -> tuple[DetectorDetection, ...]:
        assert getattr(frame, "width") == 4
        assert getattr(frame, "height") == 2
        assert prompts == ("person",)
        return (
            DetectorDetection(
                "person",
                0.9,
                NormalizedBoundingBox(0.1, 0.2, 0.5, 0.8),
                "person",
            ),
        )


def _request() -> LiveVisionRequest:
    image_module = pytest.importorskip("PIL.Image")
    image = image_module.new("RGB", (4, 2), "black")
    output = BytesIO()
    image.save(output, format="JPEG")
    return LiveVisionRequest(3, 4, 5, 4, 2, output.getvalue())


@pytest.mark.unit
def test_live_service_returns_versioned_normalized_detector_response() -> None:
    response = LiveVisionService(_FakeDetector(), prompts=("person",)).detect(_request())

    assert response["schema_version"] == 1
    assert response["runtime_generation"] == 3
    assert response["frame_id"] == 4
    assert response["source"] == {"width": 4, "height": 2}
    assert response["detector"] == {"backend": "fake_detector", "model": "fake-model"}
    assert response["detections"] == [
        {
            "label": "person",
            "confidence": 0.9,
            "bounding_box": {"x_min": 0.1, "y_min": 0.2, "x_max": 0.5, "y_max": 0.8},
            "prompt": "person",
        }
    ]


@pytest.mark.unit
def test_live_service_rejects_declared_dimensions_that_do_not_match_jpeg() -> None:
    request = _request()
    mismatched = LiveVisionRequest(
        request.runtime_generation,
        request.frame_id,
        request.capture_elapsed_realtime_nanos,
        5,
        request.source_height,
        request.jpeg_bytes,
    )

    with pytest.raises(LiveVisionRequestError, match="dimensions"):
        LiveVisionService(_FakeDetector(), prompts=("person",)).detect(mismatched)
