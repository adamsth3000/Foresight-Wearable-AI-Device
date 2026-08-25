"""Small interaction service for Foresight Lab v0.1."""

from __future__ import annotations

from dataclasses import dataclass, field

from foresight_device.sessions.models import SessionEvent, SessionRecord
from foresight_device.sessions.service import SessionService

from .intents import IntentType, resolve_intent
from .models import AssistantResponse, UserInteraction


@dataclass(frozen=True, slots=True)
class InteractionOutcome:
    """Resolved result of processing a single normalized interaction."""

    intent: IntentType
    assistant_response: AssistantResponse | None = None
    session: SessionRecord | None = None
    session_events: tuple[SessionEvent, ...] = field(default_factory=tuple)


class InteractionService:
    """Process normalized interactions against the session lifecycle."""

    def __init__(self, session_service: SessionService | None = None) -> None:
        self._sessions = session_service or SessionService()

    @property
    def sessions(self) -> SessionService:
        """Expose the backing session service."""

        return self._sessions

    def process(self, interaction: UserInteraction) -> InteractionOutcome:
        """Resolve a single normalized interaction into session actions."""

        intent = resolve_intent(interaction.content)

        if intent is IntentType.START_ADVENTURE:
            session, events = self._sessions.propose_adventure_session(interaction)
            response = AssistantResponse(
                message="Would you like me to record this event?",
                confirmation_required=True,
            )
            return InteractionOutcome(
                intent=intent,
                assistant_response=response,
                session=session,
                session_events=tuple(events),
            )

        if intent is IntentType.CONFIRM_YES:
            session, events = self._sessions.confirm_pending_session(interaction)
            response = AssistantResponse(
                message="Adventure recording started.",
                metadata={"planned_confirmation_cue": "AUDIBLE_BEEP"},
            )
            return InteractionOutcome(
                intent=intent,
                assistant_response=response,
                session=session,
                session_events=tuple(events),
            )

        if intent is IntentType.CONFIRM_NO:
            session, events = self._sessions.cancel_pending_session(interaction)
            response = AssistantResponse(message="Okay, I won't record this event.")
            return InteractionOutcome(
                intent=intent,
                assistant_response=response,
                session=session,
                session_events=tuple(events),
            )

        return InteractionOutcome(intent=intent)
