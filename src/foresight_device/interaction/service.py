"""Small stateful interaction service for the Foresight Lab simulator."""

from __future__ import annotations

import re
from dataclasses import dataclass, field

from foresight_device.sessions.models import SessionEvent, SessionRecord
from foresight_device.sessions.service import SessionService

from .intents import IntentType
from .interpreter import DeterministicIntentInterpreter, IntentInterpreter
from .models import (
    AssistantResponse,
    CapturedContent,
    CapturedContentType,
    InteractionModality,
    InteractionSource,
    UserInteraction,
)
from .state import AssistantState, PendingInteractionContext


@dataclass(frozen=True, slots=True)
class InteractionOutcome:
    """Resolved result of processing one normalized interaction."""

    intent: IntentType
    assistant_response: AssistantResponse | None = None
    session: SessionRecord | None = None
    session_events: tuple[SessionEvent, ...] = field(default_factory=tuple)
    captured_content: CapturedContent | None = None


class InteractionService:
    """Process interactions while preserving small, explicit Lab state."""

    def __init__(
        self,
        session_service: SessionService | None = None,
        interpreter: IntentInterpreter | None = None,
    ) -> None:
        self._sessions = session_service or SessionService()
        self._interpreter = interpreter or DeterministicIntentInterpreter()
        self._assistant_state = AssistantState.IDLE
        self._pending_context = PendingInteractionContext.NONE
        self._captured_content: list[CapturedContent] = []

    @property
    def sessions(self) -> SessionService:
        """Expose the backing session service."""

        return self._sessions

    @property
    def assistant_state(self) -> AssistantState:
        """Return the current assistant state."""

        return self._assistant_state

    @property
    def pending_context(self) -> PendingInteractionContext:
        """Return the current follow-up interaction context."""

        return self._pending_context

    @property
    def captured_content(self) -> tuple[CapturedContent, ...]:
        """Return transient normalized captures without persisting them."""

        return tuple(self._captured_content)

    def abandon_pending_interaction(self) -> None:
        """End an incomplete Lab listening turn without leaving pending state behind."""

        if self._pending_context is PendingInteractionContext.AWAITING_ADVENTURE_CONFIRMATION:
            self._sessions.cancel_pending_session(
                UserInteraction(
                    content="",
                    modality=InteractionModality.SYSTEM,
                    source=InteractionSource.SIMULATED,
                )
            )
        self._return_to_idle()

    def process(self, interaction: UserInteraction) -> InteractionOutcome:
        """Resolve one normalized interaction into state, session, or capture actions."""

        if self._pending_context is not PendingInteractionContext.NONE:
            return self._process_pending_context(interaction)

        match = self._interpreter.interpret(interaction)
        if match.intent is not IntentType.UNKNOWN:
            return self._process_intent(interaction, match.intent)

        if self._is_wake_phrase(interaction.content):
            self._assistant_state = AssistantState.LISTENING_FOR_COMMAND
            return InteractionOutcome(
                intent=IntentType.UNKNOWN,
                assistant_response=AssistantResponse(
                    message="Listening...",
                    metadata={"simulated_acknowledgement_cue": "BEEP"},
                ),
            )

        return InteractionOutcome(intent=match.intent)

    def _process_pending_context(self, interaction: UserInteraction) -> InteractionOutcome:
        if self._pending_context is PendingInteractionContext.AWAITING_ADVENTURE_CONFIRMATION:
            intent = self._interpreter.interpret(interaction).intent
            if intent is IntentType.CONFIRM_YES:
                session, events = self._sessions.confirm_pending_session(interaction)
                self._return_to_idle()
                return InteractionOutcome(
                    intent=intent,
                    assistant_response=AssistantResponse(
                        message="Adventure recording started.",
                        metadata={"planned_confirmation_cue": "AUDIBLE_BEEP"},
                    ),
                    session=session,
                    session_events=tuple(events),
                )
            if intent is IntentType.CONFIRM_NO:
                session, events = self._sessions.cancel_pending_session(interaction)
                self._return_to_idle()
                return InteractionOutcome(
                    intent=intent,
                    assistant_response=AssistantResponse(
                        message="Okay, I won't record this event."
                    ),
                    session=session,
                    session_events=tuple(events),
                )
            return InteractionOutcome(
                intent=intent,
                assistant_response=AssistantResponse(
                    message="Would you like me to record this event?",
                    confirmation_required=True,
                ),
            )

        content_type = (
            CapturedContentType.NOTE
            if self._pending_context is PendingInteractionContext.AWAITING_NOTE_CONTENT
            else CapturedContentType.SHOPPING_ITEM
        )
        capture = CapturedContent(
            content_type=content_type,
            content=interaction.content.strip(),
            interaction_id=interaction.interaction_id,
            metadata={"source": interaction.source.value, "modality": interaction.modality.value},
        )
        self._captured_content.append(capture)
        self._return_to_idle()
        message = (
            "Note recorded."
            if content_type is CapturedContentType.NOTE
            else f"Added {capture.content} to your shopping list."
        )
        intent = (
            IntentType.TAKE_NOTE
            if content_type is CapturedContentType.NOTE
            else IntentType.ADD_SHOPPING_ITEM
        )
        return InteractionOutcome(
            intent=intent,
            assistant_response=AssistantResponse(message=message),
            captured_content=capture,
        )

    def _process_intent(
        self,
        interaction: UserInteraction,
        intent: IntentType,
    ) -> InteractionOutcome:
        if intent is IntentType.START_ADVENTURE:
            session, events = self._sessions.propose_adventure_session(interaction)
            self._assistant_state = AssistantState.LISTENING_FOR_COMMAND
            self._pending_context = PendingInteractionContext.AWAITING_ADVENTURE_CONFIRMATION
            return InteractionOutcome(
                intent=intent,
                assistant_response=AssistantResponse(
                    message="Would you like me to record this event?",
                    confirmation_required=True,
                ),
                session=session,
                session_events=tuple(events),
            )

        if intent is IntentType.TAKE_NOTE:
            self._assistant_state = AssistantState.LISTENING_FOR_COMMAND
            self._pending_context = PendingInteractionContext.AWAITING_NOTE_CONTENT
            return InteractionOutcome(
                intent=intent,
                assistant_response=AssistantResponse(message="What would you like me to note?"),
            )

        if intent is IntentType.ADD_SHOPPING_ITEM:
            self._assistant_state = AssistantState.LISTENING_FOR_COMMAND
            self._pending_context = PendingInteractionContext.AWAITING_SHOPPING_ITEM
            return InteractionOutcome(
                intent=intent,
                assistant_response=AssistantResponse(message="What would you like to add?"),
            )

        return InteractionOutcome(intent=intent)

    def _return_to_idle(self) -> None:
        self._assistant_state = AssistantState.IDLE
        self._pending_context = PendingInteractionContext.NONE

    @staticmethod
    def _is_wake_phrase(content: str) -> bool:
        """Keep deterministic wake handling outside ordinary intent interpretation."""

        normalized = re.sub(r"[^\w\s]", " ", content.casefold())
        return re.search(r"\bhey\s+foresight\b", normalized) is not None
