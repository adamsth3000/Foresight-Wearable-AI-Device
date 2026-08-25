"""Session lifecycle service for Foresight Lab v0.1."""

from __future__ import annotations

from datetime import UTC, datetime

from foresight_device.interaction.models import UserInteraction

from .models import SessionEvent, SessionEventType, SessionRecord, SessionStatus, SessionType


class SessionStateError(RuntimeError):
    """Raised when a session transition is invalid for the current state."""


class SessionService:
    """Manage a single pending or active adventure session."""

    def __init__(self) -> None:
        self._current_session: SessionRecord | None = None

    @property
    def current_session(self) -> SessionRecord | None:
        """Return the current pending or active session."""

        return self._current_session

    def propose_adventure_session(
        self,
        interaction: UserInteraction,
    ) -> tuple[SessionRecord, list[SessionEvent]]:
        """Create the single pending adventure session."""

        if self._current_session is not None and self._current_session.status in {
            SessionStatus.PENDING_CONFIRMATION,
            SessionStatus.ACTIVE,
        }:
            raise SessionStateError("An adventure session is already pending or active.")

        session = SessionRecord(
            session_type=SessionType.ADVENTURE,
            status=SessionStatus.PENDING_CONFIRMATION,
            title="Adventure Recording",
        )
        event = SessionEvent(
            event_type=SessionEventType.SESSION_PROPOSED,
            session_id=session.session_id,
            payload={
                "trigger_interaction_id": interaction.interaction_id,
                "trigger_modality": interaction.modality.value,
                "intent": "START_ADVENTURE",
            },
        )
        session = session.append_events(event)
        self._current_session = session
        return session, [event]

    def confirm_pending_session(
        self,
        interaction: UserInteraction,
    ) -> tuple[SessionRecord, list[SessionEvent]]:
        """Confirm the pending session and mark it active."""

        session = self._require_status(SessionStatus.PENDING_CONFIRMATION)
        now = datetime.now(UTC)
        confirmed = SessionEvent(
            event_type=SessionEventType.SESSION_CONFIRMED,
            session_id=session.session_id,
            payload={
                "trigger_interaction_id": interaction.interaction_id,
                "trigger_modality": interaction.modality.value,
                "intent": "CONFIRM_YES",
            },
            timestamp=now,
        )
        started = SessionEvent(
            event_type=SessionEventType.SESSION_STARTED,
            session_id=session.session_id,
            payload={"status": SessionStatus.ACTIVE.value},
            timestamp=now,
        )
        updated = session.append_events(confirmed, started)
        updated = SessionRecord(
            session_type=updated.session_type,
            status=SessionStatus.ACTIVE,
            session_id=updated.session_id,
            title=updated.title,
            created_at=updated.created_at,
            started_at=now,
            ended_at=updated.ended_at,
            event_log=updated.event_log,
        )
        self._current_session = updated
        return updated, [confirmed, started]

    def cancel_pending_session(
        self,
        interaction: UserInteraction,
    ) -> tuple[SessionRecord, list[SessionEvent]]:
        """Cancel the pending session."""

        session = self._require_status(SessionStatus.PENDING_CONFIRMATION)
        event = SessionEvent(
            event_type=SessionEventType.SESSION_CANCELLED,
            session_id=session.session_id,
            payload={
                "trigger_interaction_id": interaction.interaction_id,
                "trigger_modality": interaction.modality.value,
                "intent": "CONFIRM_NO",
            },
        )
        updated = session.append_events(event)
        updated = SessionRecord(
            session_type=updated.session_type,
            status=SessionStatus.CANCELLED,
            session_id=updated.session_id,
            title=updated.title,
            created_at=updated.created_at,
            started_at=updated.started_at,
            ended_at=datetime.now(UTC),
            event_log=updated.event_log,
        )
        self._current_session = None
        return updated, [event]

    def end_active_session(self) -> tuple[SessionRecord, list[SessionEvent]]:
        """End the active session."""

        session = self._require_status(SessionStatus.ACTIVE)
        now = datetime.now(UTC)
        event = SessionEvent(
            event_type=SessionEventType.SESSION_ENDED,
            session_id=session.session_id,
            payload={"status": SessionStatus.ENDED.value},
            timestamp=now,
        )
        updated = session.append_events(event)
        updated = SessionRecord(
            session_type=updated.session_type,
            status=SessionStatus.ENDED,
            session_id=updated.session_id,
            title=updated.title,
            created_at=updated.created_at,
            started_at=updated.started_at,
            ended_at=now,
            event_log=updated.event_log,
        )
        self._current_session = None
        return updated, [event]

    def record_event(
        self,
        event_type: SessionEventType,
        payload: dict[str, str] | None = None,
    ) -> SessionEvent:
        """Attach a generic normalized event to the active session."""

        session = self._require_status(SessionStatus.ACTIVE)
        if event_type not in {
            SessionEventType.INTERACTION_EVENT_RECORDED,
            SessionEventType.LOCATION_EVENT_RECORDED,
            SessionEventType.MEDIA_EVENT_RECORDED,
        }:
            raise SessionStateError(f"Unsupported generic event type: {event_type}")

        event = SessionEvent(
            event_type=event_type,
            session_id=session.session_id,
            payload=payload or {},
        )
        updated = session.append_events(event)
        self._current_session = updated
        return event

    def _require_status(self, status: SessionStatus) -> SessionRecord:
        session = self._current_session
        if session is None or session.status is not status:
            raise SessionStateError(f"Expected session with status {status.value}.")
        return session
