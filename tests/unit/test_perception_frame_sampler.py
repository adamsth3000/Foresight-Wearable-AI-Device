"""Unit coverage for deterministic FFmpeg sampling decisions."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest

from foresight_device.perception.frame_sampler import (
    FfmpegFrameSampler,
    FrameSamplingError,
    sampling_timestamps,
)


@pytest.mark.unit
def test_sampling_timestamps_cover_short_and_fractional_media_without_duplicates() -> None:
    assert sampling_timestamps(0.2, 1.0) == (0.0,)
    assert sampling_timestamps(2.4, 1.0) == (0.0, 1.0, 2.0)
    assert sampling_timestamps(2.4, 0.75) == (0.0, 0.75, 1.5, 2.25)


@pytest.mark.unit
def test_media_info_uses_ffprobe_metadata(monkeypatch: pytest.MonkeyPatch, tmp_path: Path) -> None:
    media_path = tmp_path / "event.mp4"
    media_path.write_bytes(b"not decoded in this unit test")
    probe_payload = {
        "streams": [{"width": 1280, "height": 720, "avg_frame_rate": "30/1"}],
        "format": {"duration": "2.4"},
    }

    monkeypatch.setattr(
        "foresight_device.perception.frame_sampler.shutil.which", lambda _: "ffprobe"
    )
    monkeypatch.setattr(
        "foresight_device.perception.frame_sampler.subprocess.run",
        lambda *args, **kwargs: subprocess.CompletedProcess(
            args[0], 0, json.dumps(probe_payload), ""
        ),
    )

    info = FfmpegFrameSampler().media_info(media_path)

    assert (info.duration_seconds, info.width, info.height, info.frames_per_second) == (
        2.4,
        1280,
        720,
        30.0,
    )


@pytest.mark.unit
def test_corrupt_media_metadata_is_a_clean_error(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    media_path = tmp_path / "event.mp4"
    media_path.write_bytes(b"corrupt")
    monkeypatch.setattr(
        "foresight_device.perception.frame_sampler.shutil.which", lambda _: "ffprobe"
    )
    monkeypatch.setattr(
        "foresight_device.perception.frame_sampler.subprocess.run",
        lambda *args, **kwargs: subprocess.CompletedProcess(args[0], 0, "{}", ""),
    )

    with pytest.raises(FrameSamplingError, match="incomplete"):
        FfmpegFrameSampler().media_info(media_path)
