"""Unit coverage for normalized Phase 1D observation records."""

from __future__ import annotations

import pytest

from foresight_device.perception.models import DetectorDetection, NormalizedBoundingBox


@pytest.mark.unit
def test_pixel_box_is_normalized_clamped_and_ordered() -> None:
    box = NormalizedBoundingBox.from_pixel_coordinates(
        200.0,
        -10.0,
        -5.0,
        120.0,
        frame_width=100,
        frame_height=100,
    )

    assert box.as_list() == [0.0, 0.0, 1.0, 1.0]


@pytest.mark.unit
def test_invalid_normalized_box_and_detection_are_rejected() -> None:
    with pytest.raises(ValueError, match="within"):
        NormalizedBoundingBox(-0.1, 0.0, 1.0, 1.0)
    with pytest.raises(ValueError, match="labels"):
        DetectorDetection(" ", 0.5, NormalizedBoundingBox(0.0, 0.0, 1.0, 1.0))
    with pytest.raises(ValueError, match="confidence"):
        DetectorDetection("tree", 1.1, NormalizedBoundingBox(0.0, 0.0, 1.0, 1.0))
