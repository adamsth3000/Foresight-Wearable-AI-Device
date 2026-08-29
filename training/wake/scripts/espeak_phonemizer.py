"""Minimal compatibility module for the legacy Piper generator.

The benchmark adds this directory to PYTHONPATH only for its Piper subprocess.
It avoids modifying the ignored generator checkout or installing the failing
``espeak-phonemizer`` source distribution on Windows.
"""

from __future__ import annotations

import re

from native_espeak import phonemize as native_phonemize

STRESS_PATTERN = re.compile(r"[\u02c8\u02cc]")


class Phonemizer:
    """Provide the subset of the espeak-phonemizer API used by generate_samples.py."""

    def __init__(self, default_voice: str | None = None, **_: object) -> None:
        self.default_voice = default_voice

    def phonemize(
        self,
        text: str,
        voice: str | None = None,
        no_stress: bool = False,
        **_: object,
    ) -> str:
        result = native_phonemize(text, voice or self.default_voice or "en-us")
        return STRESS_PATTERN.sub("", result) if no_stress else result
