"""Module entry point for the Foresight Lab terminal simulator."""

from __future__ import annotations

import sys

from .cli import run_cli
from .voice import FasterWhisperVoiceInputAdapter


def main() -> int:
    """Run the terminal simulator using standard streams."""

    return run_cli(sys.stdin, sys.stdout, voice_input=FasterWhisperVoiceInputAdapter())


if __name__ == "__main__":
    raise SystemExit(main())
