"""Optional wake-input adapters for the Foresight Lab."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from datetime import UTC, datetime
from importlib import import_module
from pathlib import Path
from typing import Any, Protocol


class WakeInputUnavailableError(RuntimeError):
    """Raised when the optional Lab wake-input stack cannot be used."""


@dataclass(frozen=True, slots=True)
class WakeEvent:
    """A minimal signal that a wake adapter detected its configured phrase."""

    source: str
    timestamp: datetime = field(default_factory=lambda: datetime.now(UTC))


class WakeInputAdapter(Protocol):
    """Wait for one wake event without interpreting a command."""

    def wait_for_wake(self) -> WakeEvent:
        """Block until the configured wake phrase is detected."""

    def close(self) -> None:
        """Release any wake-detection resources."""


class OpenWakeWordInputAdapter:
    """Lab-only local wake adapter using a developer-provided ONNX model."""

    def __init__(
        self,
        model_path: str | Path | None = None,
        threshold: float = 0.5,
    ) -> None:
        configured_path = model_path or os.environ.get("FORESIGHT_WAKE_MODEL_PATH")
        self._model_path = Path(configured_path) if configured_path else None
        self._threshold = threshold
        self._model: Any | None = None

    def wait_for_wake(self) -> WakeEvent:
        """Own the microphone until the configured wake phrase is detected."""

        model = self._get_model()
        numpy = self._load_module("numpy")
        sounddevice = self._load_module("sounddevice")
        try:
            with sounddevice.RawInputStream(
                samplerate=16_000,
                blocksize=1_280,
                dtype="int16",
                channels=1,
            ) as stream:
                while True:
                    pcm, overflowed = stream.read(1_280)
                    if overflowed:
                        continue
                    samples = numpy.frombuffer(pcm, dtype=numpy.int16)
                    scores = model.predict(samples)
                    if any(float(score) >= self._threshold for score in scores.values()):
                        return WakeEvent(source="openwakeword")
        except KeyboardInterrupt:
            raise
        except Exception as exc:
            raise WakeInputUnavailableError(f"Wake listening failed: {exc}") from exc

    def close(self) -> None:
        """Release the optional inference model when hands-free mode stops."""

        self._model = None

    def _get_model(self) -> Any:
        if self._model is not None:
            return self._model
        if self._model_path is None:
            raise WakeInputUnavailableError("FORESIGHT_WAKE_MODEL_PATH is not configured.")
        if not self._model_path.is_file():
            raise WakeInputUnavailableError(
                f"Wake model file is unavailable: {self._model_path}"
            )

        openwakeword_model = self._load_module("openwakeword.model")
        try:
            self._model = openwakeword_model.Model(
                wakeword_models=[str(self._model_path)],
                inference_framework="onnx",
            )
        except Exception as exc:
            raise WakeInputUnavailableError(f"Wake initialization failed: {exc}") from exc
        return self._model

    @staticmethod
    def _load_module(name: str) -> Any:
        try:
            return import_module(name)
        except ImportError as exc:
            raise WakeInputUnavailableError(
                "Wake input dependencies are unavailable. Install the project's wake extra."
            ) from exc
