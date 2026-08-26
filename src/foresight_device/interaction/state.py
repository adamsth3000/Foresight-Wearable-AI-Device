"""Small assistant state models for the Foresight Lab simulator."""

from enum import StrEnum


class AssistantState(StrEnum):
    """High-level state for the simulated assistant."""

    IDLE = "idle"
    LISTENING_FOR_COMMAND = "listening_for_command"


class PendingInteractionContext(StrEnum):
    """The follow-up input currently expected from the user."""

    NONE = "none"
    AWAITING_ADVENTURE_CONFIRMATION = "awaiting_adventure_confirmation"
    AWAITING_NOTE_CONTENT = "awaiting_note_content"
    AWAITING_SHOPPING_ITEM = "awaiting_shopping_item"
