"""Constrained intent interpretation for the Foresight Lab simulator."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Protocol

from .intents import IntentType
from .models import UserInteraction


@dataclass(frozen=True, slots=True)
class IntentMatch:
    """The normalized result returned by an intent interpreter."""

    intent: IntentType
    metadata: dict[str, str] = field(default_factory=dict)
    matched_by: str | None = None


class IntentInterpreter(Protocol):
    """Boundary that permits future semantic interpretation implementations."""

    def interpret(self, interaction: UserInteraction) -> IntentMatch:
        """Interpret one normalized user interaction."""


class DeterministicIntentInterpreter:
    """Explicit phrase rules used by the v0.3 simulator only."""

    def interpret(self, interaction: UserInteraction) -> IntentMatch:
        """Resolve the small supported intent set without external services."""

        normalized = " ".join(interaction.content.strip().lower().split())

        if normalized in {"yes", "yeah", "yep", "confirm"}:
            return IntentMatch(IntentType.CONFIRM_YES, matched_by="confirmation_rule")
        if normalized in {"no", "nope", "cancel"}:
            return IntentMatch(IntentType.CONFIRM_NO, matched_by="confirmation_rule")
        if "going on an adventure" in normalized or normalized in {
            "adventure time",
            "adventure time.",
        }:
            return IntentMatch(IntentType.START_ADVENTURE, matched_by="adventure_rule")
        if normalized in {
            "take a note",
            "take a note.",
            "i want to make a note",
            "i want to make a note.",
        }:
            return IntentMatch(IntentType.TAKE_NOTE, matched_by="note_rule")
        if normalized in {
            "add something to my shopping list",
            "add something to my shopping list.",
            "i need to add something to the grocery list",
            "i need to add something to the grocery list.",
        }:
            return IntentMatch(IntentType.ADD_SHOPPING_ITEM, matched_by="shopping_rule")
        return IntentMatch(IntentType.UNKNOWN)
