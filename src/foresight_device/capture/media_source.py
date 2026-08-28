"""Boundary between source-specific transports and capture policy."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

from .models import MediaSourceDescriptor


class MediaSource(Protocol):
    """A live source described without requiring downstream device knowledge."""

    @property
    def descriptor(self) -> MediaSourceDescriptor:
        """Return the source and session metadata for capture artifacts."""


@dataclass(frozen=True, slots=True)
class ConfiguredMediaSource:
    """A configured source used by the current RTSP ingress adapter."""

    descriptor: MediaSourceDescriptor
