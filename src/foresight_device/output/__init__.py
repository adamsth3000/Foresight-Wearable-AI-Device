"""Replaceable output adapters for Foresight Lab."""

from .audio import (
    AudioCue,
    AudioCueOutput,
    AudioOutputUnavailableError,
    SpeechOutput,
    WindowsLabAudioOutput,
)

__all__ = [
    "AudioCue",
    "AudioCueOutput",
    "AudioOutputUnavailableError",
    "SpeechOutput",
    "WindowsLabAudioOutput",
]
