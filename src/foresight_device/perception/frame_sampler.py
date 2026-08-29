"""Deterministic FFmpeg-backed event-frame sampling without persistent image files."""

from __future__ import annotations

import json
import math
import shutil
import subprocess
from collections.abc import Iterator
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from .models import SampledFrame


class FrameSamplingError(RuntimeError):
    """Raised when promoted media cannot be probed or decoded."""


class FrameSampler(Protocol):
    """Yield deterministic decoded frames for one event-media file."""

    def sample(self, media_path: Path, interval_seconds: float) -> Iterator[SampledFrame]:
        """Yield in-memory frames in ascending media-timestamp order."""


@dataclass(frozen=True, slots=True)
class MediaInfo:
    """Video metadata sufficient for deterministic timestamp sampling."""

    duration_seconds: float
    width: int
    height: int
    frames_per_second: float


class FfmpegFrameSampler:
    """Sample event media through ffprobe and in-memory PNG frame decoding."""

    def __init__(
        self, *, ffmpeg_executable: str = "ffmpeg", ffprobe_executable: str = "ffprobe"
    ) -> None:
        self._ffmpeg_executable = ffmpeg_executable
        self._ffprobe_executable = ffprobe_executable

    def media_info(self, media_path: Path) -> MediaInfo:
        """Read event-video dimensions, duration, and frame rate."""

        self._require_executable(self._ffprobe_executable, "ffprobe")
        if not media_path.is_file():
            raise FrameSamplingError(f"Event media was not found: {media_path}")
        command = (
            self._ffprobe_executable,
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=width,height,avg_frame_rate,duration:format=duration",
            "-of",
            "json",
            str(media_path),
        )
        result = subprocess.run(command, check=False, capture_output=True, text=True)
        if result.returncode != 0:
            raise FrameSamplingError(
                self._error_message("ffprobe could not inspect event media", result.stderr)
            )
        try:
            payload = json.loads(result.stdout)
            stream = payload["streams"][0]
            duration = float(stream.get("duration") or payload["format"]["duration"])
            width = int(stream["width"])
            height = int(stream["height"])
            frame_rate = _parse_frame_rate(str(stream.get("avg_frame_rate", "0/0")))
        except (KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise FrameSamplingError("ffprobe returned incomplete video metadata") from exc
        if not math.isfinite(duration) or duration <= 0 or width <= 0 or height <= 0:
            raise FrameSamplingError("event media has no usable video duration or dimensions")
        return MediaInfo(duration, width, height, frame_rate)

    def sample(self, media_path: Path, interval_seconds: float) -> Iterator[SampledFrame]:
        """Yield one in-memory PNG per deterministic media timestamp."""

        if interval_seconds <= 0:
            raise ValueError("frame sampling interval must be positive")
        self._require_executable(self._ffmpeg_executable, "ffmpeg")
        info = self.media_info(media_path)
        for timestamp in sampling_timestamps(info.duration_seconds, interval_seconds):
            command = (
                self._ffmpeg_executable,
                "-hide_banner",
                "-loglevel",
                "error",
                "-ss",
                _timestamp_argument(timestamp),
                "-i",
                str(media_path),
                "-frames:v",
                "1",
                "-f",
                "image2pipe",
                "-vcodec",
                "png",
                "pipe:1",
            )
            result = subprocess.run(command, check=False, capture_output=True)
            if result.returncode != 0 or not result.stdout:
                stderr = result.stderr.decode("utf-8", errors="replace")
                raise FrameSamplingError(
                    self._error_message("ffmpeg could not decode event frame", stderr)
                )
            frame_index = int(math.floor(timestamp * info.frames_per_second))
            yield SampledFrame(frame_index, timestamp, info.width, info.height, result.stdout)

    @staticmethod
    def _require_executable(executable: str, name: str) -> None:
        if Path(executable).is_file() or shutil.which(executable) is not None:
            return
        raise FrameSamplingError(f"{name} executable was not found: {executable}")

    @staticmethod
    def _error_message(prefix: str, stderr: str) -> str:
        detail = " ".join(stderr.strip().splitlines()[-3:])
        return f"{prefix}: {detail}" if detail else prefix


def sampling_timestamps(duration_seconds: float, interval_seconds: float) -> tuple[float, ...]:
    """Return sorted unique timestamps in [0, duration) including zero for short media."""

    if not math.isfinite(duration_seconds) or duration_seconds <= 0:
        raise ValueError("media duration must be positive and finite")
    if not math.isfinite(interval_seconds) or interval_seconds <= 0:
        raise ValueError("frame sampling interval must be positive and finite")
    sample_count = max(1, int(math.ceil(duration_seconds / interval_seconds)))
    return tuple(
        timestamp
        for index in range(sample_count)
        if (timestamp := round(index * interval_seconds, 9)) < duration_seconds
    )


def _parse_frame_rate(value: str) -> float:
    numerator, separator, denominator = value.partition("/")
    if not separator or float(denominator) == 0:
        return 0.0
    rate = float(numerator) / float(denominator)
    return rate if math.isfinite(rate) and rate > 0 else 0.0


def _timestamp_argument(timestamp: float) -> str:
    return f"{timestamp:.9f}".rstrip("0").rstrip(".") or "0"
