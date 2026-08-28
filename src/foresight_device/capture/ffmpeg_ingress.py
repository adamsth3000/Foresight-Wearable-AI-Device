"""FFmpeg-backed RTSP ingress that writes closed stream-copy segments locally."""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import threading
import time
from collections.abc import Callable, Sequence
from datetime import UTC, datetime, timedelta
from pathlib import Path

from foresight_device.core.logging import get_logger

from .media_source import MediaSource
from .models import MediaSegment

LOGGER = get_logger(__name__)


class FfmpegIngressError(RuntimeError):
    """Raised when FFmpeg ingress cannot start or promote local media."""


class FfmpegRtspIngress:
    """Stream-copy an RTSP/TCP source into closed fMP4 rolling-buffer segments."""

    def __init__(
        self,
        source: MediaSource,
        buffer_dir: Path,
        on_segment: Callable[[MediaSegment], None],
        *,
        executable: str = "ffmpeg",
        segment_seconds: float = 2.0,
    ) -> None:
        if segment_seconds <= 0:
            raise ValueError("segment_seconds must be positive")
        self._source = source
        self._buffer_dir = buffer_dir
        self._on_segment = on_segment
        self._executable = executable
        self._segment_seconds = segment_seconds
        self._process: subprocess.Popen[bytes] | None = None
        self._monitor: threading.Thread | None = None
        self._stop_monitor = threading.Event()
        self._seen_paths: set[Path] = set()
        self._next_sequence = 0

    @property
    def command(self) -> tuple[str, ...]:
        """Return the exact stream-copy command without invoking a shell."""

        output_pattern = self._buffer_dir / "segment-%09d.mp4"
        return (
            self._executable,
            "-hide_banner",
            "-loglevel",
            "warning",
            "-rtsp_transport",
            "tcp",
            "-i",
            self._source.descriptor.uri,
            "-map",
            "0:v?",
            "-map",
            "0:a?",
            "-c",
            "copy",
            "-f",
            "segment",
            "-segment_time",
            str(self._segment_seconds),
            "-reset_timestamps",
            "1",
            "-segment_format",
            "mp4",
            "-segment_format_options",
            "movflags=+frag_keyframe+empty_moov+default_base_moof",
            str(output_pattern),
        )

    @property
    def is_running(self) -> bool:
        """Report whether the FFmpeg process has not yet exited."""

        return self._process is not None and self._process.poll() is None

    @property
    def failure_message(self) -> str | None:
        """Describe an unexpected FFmpeg exit once the process has stopped."""

        if self._process is None or self._process.poll() is None:
            return None
        return f"FFmpeg exited unexpectedly with code {self._process.returncode}."

    def start(self) -> None:
        """Start ingress and a small monitor for completed segment files."""

        if self.is_running:
            return
        if not Path(self._executable).is_file() and shutil.which(self._executable) is None:
            raise FfmpegIngressError(f"FFmpeg executable was not found: {self._executable}")
        self._buffer_dir.mkdir(parents=True, exist_ok=True)
        LOGGER.info(
            "capture ingress starting session_buffer_dir=%s existing_segment_count=%d",
            self._buffer_dir,
            len(tuple(self._buffer_dir.glob("segment-*.mp4"))),
        )
        self._stop_monitor.clear()
        try:
            self._process = subprocess.Popen(
                self.command,
                stdin=subprocess.DEVNULL,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
            )
        except OSError as exc:
            raise FfmpegIngressError(f"Unable to start FFmpeg: {exc}") from exc
        self._monitor = threading.Thread(target=self._monitor_segments, daemon=True)
        self._monitor.start()

    def stop(self) -> None:
        """Stop FFmpeg and register any closed segments before returning."""

        self._stop_monitor.set()
        if self._process is not None and self._process.poll() is None:
            self._process.terminate()
            try:
                self._process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self._process.kill()
                self._process.wait(timeout=5)
        if self._monitor is not None:
            self._monitor.join(timeout=3)
        self.scan_completed_segments(force=True)

    def cleanup_temporary_media(self) -> str | None:
        """Remove this stopped session's rolling media without touching event artifacts."""

        if self.is_running:
            message = "Temporary capture cleanup skipped because FFmpeg is still running."
            LOGGER.warning(message)
            return message
        for attempt in range(1, 4):
            try:
                shutil.rmtree(self._buffer_dir)
            except FileNotFoundError:
                LOGGER.info("capture temporary media already absent path=%s", self._buffer_dir)
                return None
            except OSError as exc:
                if attempt < 3:
                    time.sleep(0.1)
                    continue
                message = (
                    f"Temporary capture cleanup failed for {self._buffer_dir}: {exc}"
                )
                LOGGER.warning(message)
                return message
            LOGGER.info("capture temporary media removed path=%s", self._buffer_dir)
            return None
        raise AssertionError("Temporary capture cleanup retry loop did not return.")

    def scan_completed_segments(self, *, force: bool = False) -> tuple[MediaSegment, ...]:
        """Register closed segment files; tests may call this without RTSP hardware."""

        now_epoch = time.time()
        registered: list[MediaSegment] = []
        for path in sorted(self._buffer_dir.glob("segment-*.mp4")):
            if path in self._seen_paths or path.stat().st_size == 0:
                continue
            modified_epoch = path.stat().st_mtime
            if not force and now_epoch - modified_epoch < 0.5:
                continue
            ended_at = datetime.fromtimestamp(modified_epoch, tz=UTC)
            segment = MediaSegment(
                sequence=self._next_sequence,
                path=path,
                started_at_utc=ended_at - timedelta(seconds=self._segment_seconds),
                ended_at_utc=ended_at,
            )
            self._next_sequence += 1
            self._seen_paths.add(path)
            LOGGER.debug(
                "capture segment discovered sequence=%d path=%s file_mtime_utc=%s "
                "inferred_start_utc=%s inferred_end_utc=%s discovered_utc=%s force=%s",
                segment.sequence,
                path,
                ended_at.isoformat(),
                segment.started_at_utc.isoformat(),
                segment.ended_at_utc.isoformat(),
                datetime.now(UTC).isoformat(),
                force,
            )
            self._on_segment(segment)
            registered.append(segment)
        return tuple(registered)

    def concatenate_to_mp4(self, segments: Sequence[Path], output_path: Path) -> None:
        """Concatenate selected compatible fMP4 segments without re-encoding."""

        if not segments:
            raise FfmpegIngressError("An event requires at least one media segment.")
        output_path.parent.mkdir(parents=True, exist_ok=True)
        list_path: Path | None = None
        try:
            with tempfile.NamedTemporaryFile(
                mode="w",
                encoding="utf-8",
                suffix=".txt",
                prefix="foresight-concat-",
                delete=False,
            ) as handle:
                list_path = Path(handle.name)
                for path in segments:
                    escaped = str(path.resolve()).replace("'", "'\\''")
                    handle.write(f"file '{escaped}'{os.linesep}")
            command = (
                self._executable,
                "-hide_banner",
                "-loglevel",
                "warning",
                "-f",
                "concat",
                "-safe",
                "0",
                "-i",
                str(list_path),
                "-c",
                "copy",
                "-movflags",
                "+faststart",
                "-y",
                str(output_path),
            )
            result = subprocess.run(command, check=False, capture_output=True, text=True)
            if result.returncode != 0:
                raise FfmpegIngressError(
                    f"FFmpeg could not promote event media: {result.stderr.strip()}"
                )
        finally:
            if list_path is not None:
                list_path.unlink(missing_ok=True)

    def _monitor_segments(self) -> None:
        while not self._stop_monitor.wait(0.5):
            self.scan_completed_segments()
