"""Use the native eSpeak-NG executable for the local Piper phonemization path."""

from __future__ import annotations

import argparse
import os
import shutil
import subprocess
from pathlib import Path

DEFAULT_WINDOWS_ESPEAK = Path(r"C:\Program Files\eSpeak NG\espeak-ng.exe")
ESPEAK_PATH_ENVIRONMENT_VARIABLE = "FORESIGHT_TRAINING_ESPEAK_PATH"


def find_espeak(executable: str | Path | None = None) -> Path:
    """Return an explicit, configured, or conventional eSpeak-NG executable."""

    candidates = (
        executable,
        os.environ.get(ESPEAK_PATH_ENVIRONMENT_VARIABLE),
        shutil.which("espeak-ng"),
        DEFAULT_WINDOWS_ESPEAK,
    )
    for candidate in candidates:
        if candidate is not None and Path(candidate).is_file():
            return Path(candidate)
    raise RuntimeError(
        "eSpeak-NG was not found. Set FORESIGHT_TRAINING_ESPEAK_PATH to the x64 "
        "espeak-ng.exe path used by the x64 training interpreter."
    )


def phonemize(text: str, voice: str = "en-us", executable: str | Path | None = None) -> str:
    """Return eSpeak IPA output compatible with the legacy Piper generator's input."""

    completed = subprocess.run(
        [str(find_espeak(executable)), "--quiet", "--ipa=1", "--voice", voice, text],
        check=True,
        capture_output=True,
        encoding="utf-8",
    )
    return " ".join(completed.stdout.split())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("text")
    parser.add_argument("--voice", default="en-us")
    parser.add_argument("--espeak-path", type=Path)
    args = parser.parse_args()
    print(phonemize(args.text, args.voice, args.espeak_path))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
