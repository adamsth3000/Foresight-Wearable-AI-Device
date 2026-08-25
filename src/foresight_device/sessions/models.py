"""Session lifecycle models for Foresight Lab v0.1."""

from __future__ import annotations

from dataclasses import dataclass, field, replace
from datetime import UTC, datetime
from enum import StrEnum
from uuid import uuid4


class SessionType(StrEnum):
    """Supported session types for the first milestone."""

    ADVENTURE = "adventure"


class SessionStatus(StrEnum):
    """Supported session lifecycle states."""

    PENDING_CONFIRMATION = "pending_confirmation"
    ACTIVE = "active"
    ENDED = "ended"
    CANCELLED = "cancelled"


class SessionEventType(StrEnum):
    """Normalized session event kinds."""

    SESSION_PROPOSED = "session_proposed"
    SESSION_CONFIRMED = "session_confirmed"
    SESSION_STARTED = "session_started"
    SESSION_CANCELLED = "session_cancelled"
    SESSION_ENDED = "session_ended"
    INTERACTION_EVENT_RECORDED = "interaction_event_recorded"
    LOCATION_EVENT_RECORDED = "location_event_recorded"
    MEDIA_EVENT_RECORDED = "media_event_recorded"


@dataclass(frozen=True, slots=True)
class SessionEvent:
    """A normalized event attached to a session lifecycle."""

    event_type: SessionEventType
    session_id: str
    payload: dict[str, str] = field(default_factory=dict)
    event_id: str = field(default_factory=lambda: str(uuid4()))
    timestamp: datetime = field(default_factory=lambda: datetime.now(UTC))


@dataclass(frozen=True, slots=True)
class SessionRecord:
    """State for a single Foresight session."""

    session_type: SessionType
    status: SessionStatus
    session_id: str = field(default_factory=lambda: str(uuid4()))
    title: str = "Adventure"
    created_at: datetime = field(default_factory=lambda: datetime.now(UTC))
    started_at: datetime | None = None
    ended_at: datetime | None = None
    event_log: tuple[SessionEvent, ...] = field(default_factory=tuple)

    def append_events(self, *events: SessionEvent) -> SessionRecord:
        """Return a new record with events appended."""

        return replace(self, event_log=self.event_log + tuple(events))
