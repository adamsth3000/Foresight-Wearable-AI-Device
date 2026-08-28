from __future__ import annotations

import io
import sys
import time
from pathlib import Path

from foresight_device.capture import ConfiguredMediaSource, FfmpegRtspIngress, MediaSourceDescriptor


class _FakeProcess:
    def __init__(self, pid: int) -> None:
        self.pid = pid
        self.returncode: int | None = None
        self.stderr = io.StringIO()
        self.terminated = False

    def poll(self) -> int | None:
        return self.returncode

    def terminate(self) -> None:
        self.terminated = True
        self.returncode = -15

    def kill(self) -> None:
        self.returncode = -9

    def wait(self, timeout: float | None = None) -> int:
        del timeout
        return self.returncode if self.returncode is not None else 0

    def fail(self, code: int = 1, stderr: str = "404 stream not found") -> None:
        self.stderr.write(stderr + "\n")
        self.stderr.seek(0)
        self.returncode = code


class _ProcessFactory:
    def __init__(self) -> None:
        self.commands: list[tuple[str, ...]] = []
        self.processes: list[_FakeProcess] = []

    def __call__(self, command: tuple[str, ...], **_kwargs: object) -> _FakeProcess:
        process = _FakeProcess(pid=len(self.processes) + 1)
        self.commands.append(command)
        self.processes.append(process)
        return process


def _source() -> ConfiguredMediaSource:
    return ConfiguredMediaSource(
        MediaSourceDescriptor(
            source_id="phone",
            capture_session_id="session-1",
            transport="rtsp",
            uri="rtsp://example.test:8555/phone",
            video_source="camera",
            audio_source="microphone",
            session_started_utc=time_to_utc(),
            session_started_monotonic_ns=1,
        )
    )


def time_to_utc():
    from datetime import UTC, datetime

    return datetime(2026, 8, 28, tzinfo=UTC)


def _ingress(tmp_path: Path, factory: _ProcessFactory) -> FfmpegRtspIngress:
    return FfmpegRtspIngress(
        _source(),
        tmp_path / "buffer" / "session-1",
        lambda _segment: None,
        executable=sys.executable,
        reconnect_delays_seconds=(0.01, 0.02, 0.04),
        process_factory=factory,
    )


def _wait_for(predicate, timeout: float = 2.0) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(0.01)
    raise AssertionError("condition was not met before timeout")


def _segment_start_number(command: tuple[str, ...]) -> int:
    return int(command[command.index("-segment_start_number") + 1])


def test_logical_worker_starts_ffmpeg_child(tmp_path: Path) -> None:
    factory = _ProcessFactory()
    ingress = _ingress(tmp_path, factory)

    ingress.start()
    _wait_for(lambda: len(factory.processes) == 1)

    assert ingress.is_running
    assert ingress.ffmpeg_running
    assert not ingress.reconnecting
    assert _segment_start_number(factory.commands[0]) == 0

    ingress.stop()

    assert not ingress.is_running
    assert factory.processes[0].terminated


def test_initial_rtsp_failure_keeps_logical_worker_and_restarts(tmp_path: Path) -> None:
    factory = _ProcessFactory()
    ingress = _ingress(tmp_path, factory)

    ingress.start()
    _wait_for(lambda: len(factory.processes) == 1)
    factory.processes[0].fail()
    _wait_for(lambda: len(factory.processes) == 2)

    assert ingress.is_running
    assert ingress.ffmpeg_running
    assert ingress.reconnect_attempt == 1
    ingress.stop()


def test_restart_uses_next_segment_number_without_overwriting_prior_media(tmp_path: Path) -> None:
    factory = _ProcessFactory()
    ingress = _ingress(tmp_path, factory)

    ingress.start()
    _wait_for(lambda: len(factory.processes) == 1)
    session_dir = tmp_path / "buffer" / "session-1"
    first_segment = session_dir / "segment-000000000.mp4"
    first_segment.write_bytes(b"closed")
    factory.processes[0].fail()
    _wait_for(lambda: len(factory.processes) == 2)

    assert _segment_start_number(factory.commands[0]) == 0
    assert _segment_start_number(factory.commands[1]) == 1
    assert first_segment.read_bytes() == b"closed"
    ingress.stop()


def test_reconnect_backoff_progresses_and_caps(tmp_path: Path) -> None:
    ingress = _ingress(tmp_path, _ProcessFactory())

    assert ingress._reconnect_delay_for_attempt(1) == 0.01
    assert ingress._reconnect_delay_for_attempt(2) == 0.02
    assert ingress._reconnect_delay_for_attempt(3) == 0.04
    assert ingress._reconnect_delay_for_attempt(99) == 0.04


def test_stop_during_reconnect_prevents_another_process_launch(tmp_path: Path) -> None:
    factory = _ProcessFactory()
    ingress = _ingress(tmp_path, factory)

    ingress.start()
    _wait_for(lambda: len(factory.processes) == 1)
    factory.processes[0].fail()
    _wait_for(lambda: ingress.reconnecting)
    ingress.stop()
    time.sleep(0.1)

    assert not ingress.is_running
    assert len(factory.processes) == 1


def test_healthy_restart_resets_backoff(tmp_path: Path) -> None:
    factory = _ProcessFactory()
    ingress = _ingress(tmp_path, factory)

    ingress.start()
    _wait_for(lambda: len(factory.processes) == 1)
    factory.processes[0].fail()
    _wait_for(lambda: len(factory.processes) == 2)
    _wait_for(lambda: ingress.reconnect_attempt == 0, timeout=2.0)

    assert ingress.ffmpeg_running
    assert not ingress.reconnecting
    ingress.stop()
