"""Wire models for the text-only hosted conversation boundary."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


SCHEMA_VERSION = 1
MAX_UTTERANCE_CHARACTERS = 1_000
MAX_HISTORY_TURNS = 6
MAX_HISTORY_CHARACTERS = 2_400
MAX_RESPONSE_CHARACTERS = 700


class ConversationRequestError(ValueError):
    """A client request cannot safely be sent to the model."""


@dataclass(frozen=True)
class ConversationTurn:
    role: str
    text: str

    @classmethod
    def from_mapping(cls, value: object) -> "ConversationTurn":
        if not isinstance(value, dict):
            raise ConversationRequestError("history entries must be objects")
        role = value.get("role")
        text = value.get("text")
        if role not in {"user", "assistant"} or not isinstance(text, str) or not text.strip():
            raise ConversationRequestError("history entries require a role and text")
        return cls(role=role, text=text.strip()[:MAX_HISTORY_CHARACTERS])


@dataclass(frozen=True)
class ConversationRequest:
    utterance: str
    context: dict[str, Any]
    history: tuple[ConversationTurn, ...]

    @classmethod
    def from_mapping(cls, value: object) -> "ConversationRequest":
        if not isinstance(value, dict) or value.get("schema_version") != SCHEMA_VERSION:
            raise ConversationRequestError("unsupported conversation schema")
        utterance = value.get("utterance")
        context = value.get("context")
        history = value.get("history")
        if not isinstance(utterance, str) or not utterance.strip():
            raise ConversationRequestError("utterance is required")
        if not isinstance(context, dict):
            raise ConversationRequestError("context must be an object")
        if not isinstance(history, list):
            raise ConversationRequestError("history must be an array")
        turns = tuple(ConversationTurn.from_mapping(turn) for turn in history[-MAX_HISTORY_TURNS:])
        while sum(len(turn.text) for turn in turns) > MAX_HISTORY_CHARACTERS:
            turns = turns[1:]
        return cls(utterance=utterance.strip()[:MAX_UTTERANCE_CHARACTERS], context=context, history=turns)
