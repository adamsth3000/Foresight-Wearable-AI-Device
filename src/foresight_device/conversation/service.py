"""Text-only Gemini service. It retains neither requests nor responses."""

from __future__ import annotations

import os
import json
from dataclasses import dataclass
from typing import Protocol

from foresight_device.conversation.models import (
    MAX_RESPONSE_CHARACTERS,
    SCHEMA_VERSION,
    ConversationRequest,
)

GEMINI_MODEL_ID = "gemini-3.8-flash"
CONVERSATION_POLICY_VERSION = "foresight-conversation-policy-v1"
SYSTEM_POLICY = """You are Foresight, a wearable AI assistant.
Use Foresight current context as authoritative observation data.
Do not claim that you see, read, know the location of, or remember anything absent from supplied context.
Distinguish observed facts from general knowledge and reasonable inference.
For this, that, here, or what I am looking at, use the selected target when supplied; otherwise use current visual observations.
Answer concisely for speech. Do not execute device-control actions."""


class GeminiUpstreamError(RuntimeError):
    """The upstream model service did not return a usable text response."""


class GeminiClient(Protocol):
    def generate(self, *, system_instruction: str, prompt: str) -> str: ...


class GoogleGenAiClient:
    """Lazy import keeps backend validation and tests independent of installed credentials."""

    def __init__(self, api_key: str) -> None:
        self._api_key = api_key

    def generate(self, *, system_instruction: str, prompt: str) -> str:
        try:
            from google import genai
        except ImportError as error:  # pragma: no cover - deployment configuration
            raise GeminiUpstreamError("google-genai is not installed") from error
        client = genai.Client(api_key=self._api_key)
        try:
            interaction = client.interactions.create(
                model=GEMINI_MODEL_ID,
                input=prompt,
                system_instruction=system_instruction,
                store=False,
                generation_config={"max_output_tokens": 160, "thinking_level": "low"},
            )
            text = str(interaction.output_text or "").strip()
        except Exception as error:  # The HTTP boundary maps all provider specifics below.
            raise GeminiUpstreamError("Gemini request failed") from error
        if not text:
            raise GeminiUpstreamError("Gemini returned no text")
        return text


@dataclass
class ConversationService:
    gemini: GeminiClient

    @classmethod
    def from_environment(cls) -> "ConversationService":
        key = os.environ.get("GEMINI_API_KEY", "").strip()
        if not key:
            raise RuntimeError("GEMINI_API_KEY is required")
        return cls(gemini=GoogleGenAiClient(key))

    def respond(self, request: ConversationRequest) -> dict[str, object]:
        text = self.gemini.generate(system_instruction=SYSTEM_POLICY, prompt=self._prompt(request))
        bounded = " ".join(text.split())[:MAX_RESPONSE_CHARACTERS].strip()
        if not bounded:
            raise GeminiUpstreamError("Gemini returned no usable text")
        return {
            "schema_version": SCHEMA_VERSION,
            "spoken_text": bounded,
            "display_text": bounded,
            "model_identity": GEMINI_MODEL_ID,
        }

    def _prompt(self, request: ConversationRequest) -> str:
        history = "\n".join(f"{turn.role.upper()}: {turn.text}" for turn in request.history)
        return (
            f"FORESIGHT_POLICY_VERSION: {CONVERSATION_POLICY_VERSION}\n"
            f"FORESIGHT_CURRENT_CONTEXT_JSON: {json.dumps(request.context, separators=(',', ':'), sort_keys=True)}\n"
            f"CONVERSATION_HISTORY:\n{history or 'none'}\n"
            f"USER: {request.utterance}"
        )
