"""FFmpeg-backed RTSP ingress that writes closed stream-copy segments locally."""

from __future__ import annotations

import os
import shutil
import subprocess
import tempfile
import threading
import time
from collections import deque
from collections.abc import Callable, Sequence
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any

from foresight_device.core.logging import get_logger

from .media_source import MediaSource
from .models import MediaSegment

LOGGER = get_logger(__name__)


class FfmpegIngressError(RuntimeError):
    """Raised when FFmpeg ingress cannot start or promote local media."""


class FfmpegRtspIngress:
    """Stream-copy an RTSP/TCP source into closed fMP4 rolling-buffer segments.

    The ingress object represents a logical capture worker. Its FFmpeg child may
    disappear while an RTSP publisher is absent or reconnecting; the worker stays
    alive and starts a replacement child until explicit shutdown.
    """

    def __init__(
        self,
        source: MediaSource,
        buffer_dir: Path,
        on_segment: Callable[[MediaSegment], None],
        *,
        executable: str = "ffmpeg",
        segment_seconds: float = 2.0,
        reconnect_delays_seconds: tuple[float, ...] = (1.0, 2.0, 4.0, 8.0),
        process_factory: Callable[..., Any] = subprocess.Popen,
    ) -> None:
        if segment_seconds <= 0:
            raise ValueError("segment_seconds must be positive")
        if not reconnect_delays_seconds or any(delay <= 0 for delay in reconnect_delays_seconds):
            raise ValueError("reconnect_delays_seconds must contain positive delays")
        self._source = source
        self._buffer_dir = buffer_dir
        self._on_segment = on_segment
        self._executable = executable
        self._segment_seconds = segment_seconds
        self._reconnect_delays_seconds = reconnect_delays_seconds
        self._process_factory = process_factory
        self._lock = threading.RLock()
        self._process: Any | None = None
        self._monitor: threading.Thread | None = None
        self._stderr_reader: threading.Thread | None = None
        self._stop_monitor = threading.Event()
        self._worker_running = False
        self._reconnecting = False
        self._reconnect_attempt = 0
        self._next_launch_at: float | None = None
        self._process_started_at: float | None = None
        self._last_failure_message: str | None = None
        self._stderr_tail: deque[str] = deque(maxlen=20)
        self._seen_paths: set[Path] = set()
        self._next_sequence = 0
        self._next_segment_file_number = 0

    @property
    def command(self) -> tuple[str, ...]:
        """Return the next exact stream-copy command without invoking a shell."""

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
            "-segment_start_number",
            str(self._next_segment_file_number),
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
        """Report whether the logical capture worker remains active."""

        with self._lock:
            return self._worker_running

    @property
    def ffmpeg_running(self) -> bool:
        """Report whether the current FFmpeg subprocess remains alive."""

        with self._lock:
            return self._process is not None and self._process.poll() is None

    @property
    def reconnecting(self) -> bool:
        """Report whether the worker is waiting to replace an exited subprocess."""

        with self._lock:
            return self._reconnecting

    @property
    def reconnect_attempt(self) -> int:
        """Return the current consecutive reconnect attempt number."""

        with self._lock:
            return self._reconnect_attempt

    @property
    def failure_message(self) -> str | None:
        """Describe the latest child-process failure without stopping the worker."""

        with self._lock:
            return self._last_failure_message

    def start(self) -> None:
        """Start the logical ingress worker and launch its first FFmpeg child."""

        with self._lock:
            if self._worker_running:
                return
            if not Path(self._executable).is_file() and shutil.which(self._executable) is None:
                raise FfmpegIngressError(f"FFmpeg executable was not found: {self._executable}")
            self._buffer_dir.mkdir(parents=True, exist_ok=True)
            self._next_segment_file_number = self._next_available_segment_number()
            self._stop_monitor.clear()
            self._worker_running = True
            self._reconnecting = False
            self._reconnect_attempt = 0
            self._next_launch_at = time.monotonic()
            self._last_failure_message = None
            LOGGER.info(
                "capture ingress worker starting session_buffer_dir=%s next_segment_file_number=%d",
                self._buffer_dir,
                self._next_segment_file_number,
            )
            self._monitor = threading.Thread(
                target=self._monitor_segments,
                name="foresight-ffmpeg-ingress",
                daemon=True,
            )
            self._monitor.start()

    def stop(self) -> None:
        """Stop the logical worker and its child without scheduling a replacement."""

        with self._lock:
            if not self._worker_running and self._process is None:
                self.scan_completed_segments(force=True)
                return
            LOGGER.info("capture ingress explicit shutdown requested")
            self._worker_running = False
            self._reconnecting = False
            self._next_launch_at = None
            self._stop_monitor.set()
            process = self._process
        self._terminate_process(process)
        if self._monitor is not None and self._monitor is not threading.current_thread():
            self._monitor.join(timeout=3)
        if (
            self._stderr_reader is not None
            and self._stderr_reader is not threading.current_thread()
        ):
            self._stderr_reader.join(timeout=1)
        with self._lock:
            self._process = None
            self._process_started_at = None
        self.scan_completed_segments(force=True)
        LOGGER.info("capture ingress explicit shutdown complete")

    def cleanup_temporary_media(self) -> str | None:
        """Remove this stopped session's rolling media without touching event artifacts."""

        if self.is_running:
            message = "Temporary capture cleanup skipped because ingress worker is still running."
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
                message = f"Temporary capture cleanup failed for {self._buffer_dir}: {exc}"
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
        while not self._stop_monitor.wait(0.1):
            with self._lock:
                if not self._worker_running:
                    return
                process = self._process
                next_launch_at = self._next_launch_at
            if process is None:
                if next_launch_at is not None and time.monotonic() >= next_launch_at:
                    self._launch_process()
                continue
            if process.poll() is None:
                self._mark_process_healthy_if_stable()
                self.scan_completed_segments()
                continue
            self._handle_unexpected_exit(process)

    def _launch_process(self) -> None:
        with self._lock:
            if not self._worker_running or self._process is not None:
                return
            self._next_segment_file_number = self._next_available_segment_number()
            command = self.command
            attempt = self._reconnect_attempt
            self._stderr_tail.clear()
            try:
                process = self._process_factory(
                    command,
                    stdin=subprocess.DEVNULL,
                    stdout=subprocess.DEVNULL,
                    stderr=subprocess.PIPE,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                )
            except OSError as exc:
                self._last_failure_message = f"Unable to start FFmpeg: {exc}"
                LOGGER.warning("capture FFmpeg process start failed: %s", exc)
                self._schedule_reconnect_locked("FFmpeg start failed")
                return
            self._process = process
            self._process_started_at = time.monotonic()
            self._next_launch_at = None
            self._reconnecting = False
            LOGGER.info(
                "capture FFmpeg process started pid=%s reconnect_attempt=%d "
                "segment_start_number=%d",
                getattr(process, "pid", None),
                attempt,
                self._next_segment_file_number,
            )
            self._stderr_reader = threading.Thread(
                target=self._read_stderr,
                args=(process,),
                name="foresight-ffmpeg-stderr",
                daemon=True,
            )
            self._stderr_reader.start()

    def _read_stderr(self, process: Any) -> None:
        stderr = getattr(process, "stderr", None)
        if stderr is None:
            return
        for line in stderr:
            with self._lock:
                if process is self._process:
                    self._stderr_tail.append(line.strip())

    def _mark_process_healthy_if_stable(self) -> None:
        with self._lock:
            if self._process_started_at is None:
                return
            if time.monotonic() - self._process_started_at < PROCESS_STABLE_SECONDS:
                return
            self._process_started_at = None
            if self._reconnect_attempt:
                LOGGER.info(
                    "capture FFmpeg process restart is healthy; "
                    "resetting reconnect backoff after attempt=%d",
                    self._reconnect_attempt,
                )
            self._reconnect_attempt = 0
            self._reconnecting = False
            self._last_failure_message = None

    def _handle_unexpected_exit(self, process: Any) -> None:
        with self._lock:
            active_process = self._process
            if (
                active_process is None
                or process is not active_process
                or not self._worker_running
            ):
                return
            return_code = active_process.returncode
            stderr_tail = " | ".join(line for line in self._stderr_tail if line)
            self._last_failure_message = (
                f"FFmpeg exited unexpectedly with code {return_code}."
                + (f" stderr: {stderr_tail}" if stderr_tail else "")
            )
            LOGGER.warning(
                "capture FFmpeg process exited unexpectedly pid=%s code=%s stderr_tail=%s",
                getattr(process, "pid", None),
                return_code,
                stderr_tail or "<none>",
            )
            self._process = None
            self._process_started_at = None
            self._schedule_reconnect_locked("FFmpeg exited unexpectedly")
        # A crashed FFmpeg may have closed a final usable fMP4 segment. Register
        # it if present; a source outage remains a real gap, never fabricated media.
        self.scan_completed_segments(force=True)

    def _schedule_reconnect_locked(self, reason: str) -> None:
        if not self._worker_running or self._stop_monitor.is_set():
            return
        self._reconnect_attempt += 1
        delay = self._reconnect_delay_for_attempt(self._reconnect_attempt)
        self._reconnecting = True
        self._next_launch_at = time.monotonic() + delay
        LOGGER.info(
            "capture FFmpeg reconnect scheduled attempt=%d delay_seconds=%s reason=%s",
            self._reconnect_attempt,
            delay,
            reason,
        )

    def _reconnect_delay_for_attempt(self, attempt: int) -> float:
        if attempt <= 0:
            raise ValueError("attempt must be positive")
        delay_index = min(attempt - 1, len(self._reconnect_delays_seconds) - 1)
        return self._reconnect_delays_seconds[delay_index]

    def _next_available_segment_number(self) -> int:
        highest = -1
        for path in self._buffer_dir.glob("segment-*.mp4"):
            try:
                highest = max(highest, int(path.stem.removeprefix("segment-")))
            except ValueError:
                continue
        return highest + 1

    @staticmethod
    def _terminate_process(process: Any | None) -> None:
        if process is None or process.poll() is not None:
            return
        try:
            process.terminate()
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


PROCESS_STABLE_SECONDS = 1.0
