"""Constrained intent types for the Foresight Lab simulator."""

from __future__ import annotations

from enum import StrEnum


class IntentType(StrEnum):
    """Supported intents for the first interaction milestone."""

    START_ADVENTURE = "start_adventure"
    TAKE_NOTE = "take_note"
    ADD_SHOPPING_ITEM = "add_shopping_item"
    CONFIRM_YES = "confirm_yes"
    CONFIRM_NO = "confirm_no"
    UNKNOWN = "unknown"


def resolve_intent(content: str) -> IntentType:
    """Compatibility helper backed by the default deterministic interpreter."""

    from .interpreter import DeterministicIntentInterpreter
    from .models import InteractionModality, UserInteraction

    return DeterministicIntentInterpreter().interpret(
        UserInteraction(content=content, modality=InteractionModality.TEXT)
    ).intent
