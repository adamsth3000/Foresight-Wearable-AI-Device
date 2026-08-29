"""Detector boundary for source-neutral visual perception."""

from __future__ import annotations

from collections.abc import Sequence
from typing import Protocol

from .models import DetectorDetection, SampledFrame


class Detector(Protocol):
    """Detect prompt-grounded objects in one decoded frame."""

    @property
    def backend_identity(self) -> str:
        """Return the stable backend identity written into observations."""

    @property
    def model_identity(self) -> str:
        """Return the model/checkpoint identity written into observations."""

    def detect(self, frame: SampledFrame, prompts: Sequence[str]) -> Sequence[DetectorDetection]:
        """Return normalized detections for a deterministic prompt order."""


class DetectorUnavailableError(RuntimeError):
    """Raised when an optional detector backend is not configured locally."""
