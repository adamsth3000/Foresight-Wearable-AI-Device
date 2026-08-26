"""Normalized interaction models for Foresight inputs."""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import StrEnum
from uuid import uuid4


class InteractionModality(StrEnum):
    """Supported interaction modalities for normalized inputs."""

    VOICE = "voice"
    GESTURE = "gesture"
    TEXT = "text"
    SYSTEM = "system"


class InteractionSource(StrEnum):
    """Origin of a normalized interaction."""

    SIMULATED = "simulated"
    MICROPHONE = "microphone"


class CapturedContentType(StrEnum):
    """Kinds of transient content captured by a multi-step interaction."""

    NOTE = "note"
    SHOPPING_ITEM = "shopping_item"


@dataclass(frozen=True, slots=True)
class UserInteraction:
    """A normalized interaction event before any intent resolution."""

    content: str
    modality: InteractionModality
    source: InteractionSource = InteractionSource.SIMULATED
    interaction_id: str = field(default_factory=lambda: str(uuid4()))
    timestamp: datetime = field(default_factory=lambda: datetime.now(UTC))
    metadata: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class AssistantResponse:
    """A normalized assistant response."""

    message: str
    response_id: str = field(default_factory=lambda: str(uuid4()))
    timestamp: datetime = field(default_factory=lambda: datetime.now(UTC))
    confirmation_required: bool = False
    metadata: dict[str, str] = field(default_factory=dict)


@dataclass(frozen=True, slots=True)
class CapturedContent:
    """Normalized in-memory content available to future persistence work."""

    content_type: CapturedContentType
    content: str
    interaction_id: str
    metadata: dict[str, str] = field(default_factory=dict)
