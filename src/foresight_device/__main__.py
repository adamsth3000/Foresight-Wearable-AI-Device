"""Module entry point for the Foresight Lab terminal simulator."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from .cli import run_capture_cli, run_cli
from .core.logging import configure_logging
from .output import WindowsLabAudioOutput
from .voice import FasterWhisperVoiceInputAdapter


def main() -> int:
    """Run the terminal simulator using standard streams."""

    if "--capture" in sys.argv[1:]:
        configure_logging()
        parser = argparse.ArgumentParser(description="Run the Phase 1B local capture loop.")
        parser.add_argument("--capture", action="store_true")
        parser.add_argument("--source-uri", required=True, help="Live source URI, such as rtsp://host:8555/path")
        parser.add_argument(
            "--ffmpeg",
            default=os.getenv("FORESIGHT_FFMPEG_EXECUTABLE", "ffmpeg"),
            help="FFmpeg executable path or command name.",
        )
        parser.add_argument(
            "--data-root",
            type=Path,
            default=Path(os.getenv("FORESIGHT_CAPTURE_DATA_DIR", "data/capture")),
            help="Local directory for rolling segments and durable events.",
        )
        options = parser.parse_args()
        return run_capture_cli(
            sys.stdin,
            sys.stdout,
            source_uri=options.source_uri,
            ffmpeg_executable=options.ffmpeg,
            data_root=options.data_root,
        )
    audio_output = WindowsLabAudioOutput()
    speech_output = (
        audio_output if os.environ.get("FORESIGHT_LAB_SPEAK_RESPONSES") == "1" else None
    )
    return run_cli(
        sys.stdin,
        sys.stdout,
        voice_input=FasterWhisperVoiceInputAdapter(),
        cue_output=audio_output,
        speech_output=speech_output,
    )


if __name__ == "__main__":
    raise SystemExit(main())
