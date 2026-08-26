"""Optional microphone and speech-to-text adapter for Foresight Lab."""

from __future__ import annotations

from importlib import import_module
from typing import Any, Protocol


class VoiceInputUnavailableError(RuntimeError):
    """Raised when the optional Lab voice input stack cannot be used."""


class VoiceInputAdapter(Protocol):
    """Capture and transcribe one utterance without interpreting its meaning."""

    def listen_once(self) -> str | None:
        """Return one transcript, or ``None`` when no usable speech is available."""


class FasterWhisperVoiceInputAdapter:
    """Command-triggered microphone capture backed by optional faster-whisper."""

    def __init__(
        self,
        model_name: str = "base.en",
        max_duration_seconds: float = 6.0,
        sample_rate: int = 16_000,
    ) -> None:
        self._model_name = model_name
        self._max_duration_seconds = max_duration_seconds
        self._sample_rate = sample_rate
        self._model: Any | None = None

    def listen_once(self) -> str | None:
        """Capture one fixed-duration utterance and return its plain-text transcript."""

        sounddevice = self._load_module("sounddevice")
        model = self._load_model()
        frames = int(self._sample_rate * self._max_duration_seconds)

        try:
            audio = sounddevice.rec(
                frames,
                samplerate=self._sample_rate,
                channels=1,
                dtype="float32",
            )
            sounddevice.wait()
            segments, _ = model.transcribe(audio.reshape(-1), language="en")
        except Exception as exc:
            raise VoiceInputUnavailableError(
                f"Voice capture or transcription failed: {exc}"
            ) from exc

        transcript = " ".join(segment.text.strip() for segment in segments).strip()
        return transcript or None

    def _load_model(self) -> Any:
        if self._model is None:
            faster_whisper = self._load_module("faster_whisper")
            try:
                self._model = faster_whisper.WhisperModel(
                    self._model_name,
                    device="cpu",
                    compute_type="int8",
                )
            except Exception as exc:
                raise VoiceInputUnavailableError(
                    f"Speech-to-text model is unavailable: {exc}"
                ) from exc
        return self._model

    @staticmethod
    def _load_module(name: str) -> Any:
        try:
            return import_module(name)
        except ImportError as exc:
            raise VoiceInputUnavailableError(
                "Voice input dependencies are unavailable. Install the project's voice extra."
            ) from exc
