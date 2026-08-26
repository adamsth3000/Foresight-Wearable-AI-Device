"""Module entry point for the Foresight Lab terminal simulator."""

from __future__ import annotations

import os
import sys

from .cli import run_cli
from .output import WindowsLabAudioOutput
from .voice import FasterWhisperVoiceInputAdapter


def main() -> int:
    """Run the terminal simulator using standard streams."""

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
