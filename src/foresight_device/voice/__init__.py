"""Replaceable voice-input adapters for Foresight Lab."""

from .input import (
    FasterWhisperVoiceInputAdapter,
    VoiceInputAdapter,
    VoiceInputUnavailableError,
)

__all__ = [
    "FasterWhisperVoiceInputAdapter",
    "VoiceInputAdapter",
    "VoiceInputUnavailableError",
]
