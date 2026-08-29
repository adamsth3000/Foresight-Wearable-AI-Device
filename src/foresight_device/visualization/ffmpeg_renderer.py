"""FFmpeg orchestration adapter for rendering a reusable overlay timeline."""

from __future__ import annotations

import json
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path

from .overlay import OverlayItem, OverlayState, OverlayTimeline


class VideoRenderError(RuntimeError):
    """Raised when FFmpeg cannot inspect or render an annotated event video."""


@dataclass(frozen=True, slots=True)
class VideoDimensions:
    width: int
    height: int
    duration_seconds: float


class FfmpegOverlayRenderer:
    """Compile a pure overlay timeline into an FFmpeg video filter graph."""

    def __init__(
        self,
        *,
        ffmpeg_executable: str = "ffmpeg",
        ffprobe_executable: str = "ffprobe",
        font_file: Path | None = None,
    ) -> None:
        self._ffmpeg_executable = ffmpeg_executable
        self._ffprobe_executable = ffprobe_executable
        self._font_file = font_file or _default_font_file()

    def probe_dimensions(self, media_path: Path) -> VideoDimensions:
        self._require_executable(self._ffprobe_executable, "ffprobe")
        command = (
            self._ffprobe_executable,
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "stream=width,height:format=duration",
            "-of",
            "json",
            str(media_path),
        )
        result = subprocess.run(command, check=False, capture_output=True, text=True)
        try:
            stream = json.loads(result.stdout)["streams"][0]
            dimensions = VideoDimensions(
                int(stream["width"]),
                int(stream["height"]),
                float(json.loads(result.stdout)["format"]["duration"]),
            )
        except (KeyError, IndexError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise VideoRenderError("ffprobe returned invalid video dimensions") from exc
        if (
            result.returncode != 0
            or dimensions.width <= 0
            or dimensions.height <= 0
            or dimensions.duration_seconds <= 0
        ):
            raise VideoRenderError("ffprobe could not inspect event video dimensions")
        return dimensions

    def render(
        self,
        media_path: Path,
        output_path: Path,
        timeline: OverlayTimeline,
    ) -> None:
        """Render overlays while preserving input dimensions and stream-copying audio."""

        self._require_executable(self._ffmpeg_executable, "ffmpeg")
        dimensions = self.probe_dimensions(media_path)
        filter_graph = self.filter_graph(timeline, dimensions)
        output_path.parent.mkdir(parents=True, exist_ok=True)
        command = self.build_command(media_path, output_path, filter_graph)
        result = subprocess.run(command, check=False, capture_output=True, text=True)
        if result.returncode != 0:
            detail = " ".join(result.stderr.strip().splitlines()[-3:])
            raise VideoRenderError(f"ffmpeg could not render annotated event video: {detail}")

    def filter_graph(
        self,
        timeline: OverlayTimeline,
        dimensions: VideoDimensions,
    ) -> str:
        """Create deterministic thin-box/drawtext filters for all sampled timestamps."""

        filters = [
            _filter_for(
                item, duration_seconds=dimensions.duration_seconds, font_file=self._font_file
            )
            for item in timeline.all(width=dimensions.width, height=dimensions.height)
        ]
        return ",".join(filters) if filters else "null"

    def build_command(
        self, media_path: Path, output_path: Path, filter_graph: str
    ) -> tuple[str, ...]:
        """Return the recording-only command; the overlay model never invokes subprocesses."""

        return (
            self._ffmpeg_executable,
            "-y",
            "-i",
            str(media_path),
            "-filter:v",
            filter_graph,
            "-map",
            "0:v:0",
            "-map",
            "0:a?",
            "-map_metadata",
            "0",
            "-c:v",
            "libx264",
            "-crf",
            "18",
            "-c:a",
            "copy",
            str(output_path),
        )

    @staticmethod
    def _require_executable(executable: str, name: str) -> None:
        if Path(executable).is_file() or shutil.which(executable) is not None:
            return
        raise VideoRenderError(f"{name} executable was not found: {executable}")


def _filter_for(item: OverlayItem, *, duration_seconds: float, font_file: Path | None) -> str:
    observation = item.observation
    box = item.pixel_box
    color = {
        OverlayState.DETECTED: "white@0.9",
        OverlayState.MANUALLY_SELECTED: "red@0.9",
        OverlayState.GESTURE_CANDIDATE: "yellow@0.9",
        OverlayState.GESTURE_TARGETED: "lime@0.9",
        OverlayState.VALIDATED: "cyan@0.9",
        OverlayState.REJECTED: "magenta@0.9",
    }[item.state]
    start = max(0.0, observation.media_timestamp_seconds - 0.5)
    end = min(duration_seconds, observation.media_timestamp_seconds + 0.5)
    enabled = f"between(t,{start:.3f},{end:.3f})"
    label = _escape_filter_text(f"{item.display_label} {observation.confidence:.2f}")
    draw_box = (
        f"drawbox=x={box.x}:y={box.y}:w={box.width}:h={box.height}:"
        f"color={color}:t=2:enable='{enabled}'"
    )
    font = f"fontfile='{_escape_filter_text(str(font_file))}':" if font_file is not None else ""
    draw_text = (
        f"drawtext={font}text='{label}':x={box.x}:y={max(0, box.y - 18)}:"
        f"fontcolor={color}:fontsize=18:enable='{enabled}'"
    )
    return f"{draw_box},{draw_text}"


def _escape_filter_text(value: str) -> str:
    return value.replace("\\", "\\\\").replace("'", "\\'").replace(":", "\\:")


def _default_font_file() -> Path | None:
    candidate = Path(r"C:\Windows\Fonts\arial.ttf")
    return candidate if candidate.is_file() else None
