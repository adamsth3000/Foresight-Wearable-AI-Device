"""Replaceable hand perception provider boundary."""

from __future__ import annotations

from typing import Protocol

from foresight_device.perception.models import SampledFrame

from .models import Handedness, NormalizedLandmark


class ProviderUnavailableError(RuntimeError):
    """Raised when an optional provider is not installed locally."""


class RawHandDetection:
    """Provider output before the pipeline assigns event-local identity and time."""

    def __init__(
        self,
        confidence: float,
        handedness: Handedness,
        handedness_confidence: float | None,
        landmarks: tuple[NormalizedLandmark, ...],
    ) -> None:
        self.confidence = confidence
        self.handedness = handedness
        self.handedness_confidence = handedness_confidence
        self.landmarks = landmarks


class BodyPerceptionProvider(Protocol):
    @property
    def provider_identity(self) -> str: ...

    def detect_hands(self, frame: SampledFrame) -> tuple[RawHandDetection, ...]: ...


class EmptyBodyProvider:
    @property
    def provider_identity(self) -> str:
        return "empty"

    def detect_hands(self, frame: SampledFrame) -> tuple[RawHandDetection, ...]:
        del frame
        return ()
