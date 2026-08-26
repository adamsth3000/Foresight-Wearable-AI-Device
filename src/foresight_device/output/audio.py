"""Optional audio-output adapters for Foresight Lab."""

from __future__ import annotations

from enum import StrEnum
from importlib import import_module
from typing import Any, Protocol


class AudioCue(StrEnum):
    """Audio cues implemented by the current Lab milestone."""

    WAKE_ACKNOWLEDGEMENT = "wake_acknowledgement"


class AudioOutputUnavailableError(RuntimeError):
    """Raised when an optional audio-output capability cannot be used."""


class AudioCueOutput(Protocol):
    """Emit a short non-speech cue."""

    def play_cue(self, cue: AudioCue) -> None:
        """Play one supported cue."""


class SpeechOutput(Protocol):
    """Speak a user-facing assistant message."""

    def speak(self, text: str) -> None:
        """Speak one assistant message."""


class WindowsLabAudioOutput:
    """Windows cue and optional SAPI5 speech adapter for the Lab."""

    def play_cue(self, cue: AudioCue) -> None:
        """Play the short Windows acknowledgement tone synchronously."""

        if cue is not AudioCue.WAKE_ACKNOWLEDGEMENT:
            raise AudioOutputUnavailableError(f"Unsupported audio cue: {cue.value}")

        winsound = self._load_module("winsound", "Windows cue output is unavailable.")
        try:
            winsound.MessageBeep(winsound.MB_OK)
        except RuntimeError as exc:
            raise AudioOutputUnavailableError(f"Windows cue output failed: {exc}") from exc

    def speak(self, text: str) -> None:
        """Speak text with the optional pyttsx3 SAPI5 implementation."""

        pyttsx3 = self._load_module(
            "pyttsx3",
            "Text-to-speech is unavailable. Install the project's optional audio extra.",
        )
        try:
            engine = pyttsx3.init(driverName="sapi5")
            engine.say(text)
            engine.runAndWait()
        except Exception as exc:
            raise AudioOutputUnavailableError(f"Text-to-speech failed: {exc}") from exc

    @staticmethod
    def _load_module(name: str, message: str) -> Any:
        try:
            return import_module(name)
        except ImportError as exc:
            raise AudioOutputUnavailableError(message) from exc
