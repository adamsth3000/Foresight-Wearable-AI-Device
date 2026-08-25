"""Minimal intent set for Foresight Lab v0.1."""

from __future__ import annotations

from enum import StrEnum


class IntentType(StrEnum):
    """Supported intents for the first interaction milestone."""

    START_ADVENTURE = "start_adventure"
    CONFIRM_YES = "confirm_yes"
    CONFIRM_NO = "confirm_no"
    UNKNOWN = "unknown"


def resolve_intent(content: str) -> IntentType:
    """Resolve a small, explicit intent set from simulated text."""

    normalized = " ".join(content.strip().lower().split())

    if normalized in {"yes", "yeah", "yep", "confirm"}:
        return IntentType.CONFIRM_YES

    if normalized in {"no", "nope", "cancel"}:
        return IntentType.CONFIRM_NO

    if "going on an adventure" in normalized:
        return IntentType.START_ADVENTURE

    return IntentType.UNKNOWN
