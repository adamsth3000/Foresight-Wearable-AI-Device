"""Replaceable voice-input adapters for Foresight Lab."""

from .input import (
    FasterWhisperVoiceInputAdapter,
    VoiceInputAdapter,
    VoiceInputUnavailableError,
)
from .wake import (
    OpenWakeWordInputAdapter,
    WakeEvent,
    WakeInputAdapter,
    WakeInputUnavailableError,
)

__all__ = [
    "FasterWhisperVoiceInputAdapter",
    "VoiceInputAdapter",
    "VoiceInputUnavailableError",
    "OpenWakeWordInputAdapter",
    "WakeEvent",
    "WakeInputAdapter",
    "WakeInputUnavailableError",
]
