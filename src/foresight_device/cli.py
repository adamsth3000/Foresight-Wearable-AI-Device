"""Minimal terminal interface for Foresight Lab v0.2."""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TextIO

from foresight_device.interaction import (
    AssistantResponse,
    InteractionModality,
    InteractionService,
    InteractionSource,
    UserInteraction,
)
from foresight_device.sessions import SessionRecord
from foresight_device.sessions.service import SessionStateError
from foresight_device.voice import VoiceInputAdapter, VoiceInputUnavailableError


@dataclass(frozen=True, slots=True)
class CommandResult:
    """Result of handling a single CLI command."""

    lines: tuple[str, ...] = field(default_factory=tuple)
    should_exit: bool = False


def build_text_interaction(content: str) -> UserInteraction:
    """Convert terminal text into the normalized interaction model."""

    return UserInteraction(
        content=content,
        modality=InteractionModality.TEXT,
        source=InteractionSource.SIMULATED,
    )


def build_voice_interaction(transcript: str) -> UserInteraction:
    """Convert a microphone transcript into the normalized interaction model."""

    return UserInteraction(
        content=transcript,
        modality=InteractionModality.VOICE,
        source=InteractionSource.MICROPHONE,
    )


def render_assistant_response(response: AssistantResponse) -> tuple[str, ...]:
    """Render a normalized assistant response for terminal output."""

    lines = []
    if response.metadata.get("simulated_acknowledgement_cue") == "BEEP":
        lines.append("[BEEP]")
    lines.append(f"Foresight: {response.message}")
    cue = response.metadata.get("planned_confirmation_cue")
    if cue:
        lines.append(f"(Planned confirmation cue: {cue})")
    return tuple(lines)


def render_session_status(session: SessionRecord | None) -> tuple[str, ...]:
    """Render a compact session status summary."""

    if session is None:
        return ("No active or pending session.",)

    return (
        f"Session Type: {session.session_type.value}",
        f"Status: {session.status.value}",
        f"Session ID: {session.session_id}",
        f"Event Count: {len(session.event_log)}",
    )


def render_status(service: InteractionService) -> tuple[str, ...]:
    """Render session and interaction state for the terminal simulator."""

    return render_session_status(service.sessions.current_session) + (
        f"Assistant State: {service.assistant_state.value}",
        f"Pending Context: {service.pending_context.value}",
    )


def handle_command(
    command: str,
    service: InteractionService,
    voice_input: VoiceInputAdapter | None = None,
) -> CommandResult:
    """Handle a single terminal command."""

    normalized = command.strip()
    lowered = normalized.lower()

    if lowered in {"exit", "quit"}:
        return CommandResult(lines=("Exiting Foresight Lab.",), should_exit=True)

    if lowered == "status":
        return CommandResult(lines=render_status(service))

    if lowered == "voice":
        return _handle_voice_command(service, voice_input)

    if not normalized:
        return CommandResult()

    return _process_interaction(build_text_interaction(normalized), service)


def _handle_voice_command(
    service: InteractionService,
    voice_input: VoiceInputAdapter | None,
) -> CommandResult:
    """Capture one Lab-only voice utterance and send its transcript to the core."""

    if voice_input is None:
        return CommandResult(
            lines=("Voice input unavailable: install the project's optional voice dependencies.",)
        )

    try:
        transcript = voice_input.listen_once()
    except VoiceInputUnavailableError as exc:
        return CommandResult(lines=(f"Voice input unavailable: {exc}",))

    if transcript is None or not transcript.strip():
        return CommandResult(lines=("No usable speech detected.",))

    return _process_interaction(build_voice_interaction(transcript), service)


def _process_interaction(
    interaction: UserInteraction,
    service: InteractionService,
) -> CommandResult:
    """Run a normalized interaction through the established core flow."""

    try:
        outcome = service.process(interaction)
    except SessionStateError as exc:
        return CommandResult(lines=(f"Error: {exc}",))

    if outcome.assistant_response is None:
        return CommandResult(lines=("Foresight: No response available.",))

    return CommandResult(lines=render_assistant_response(outcome.assistant_response))


def run_cli(
    input_stream: TextIO,
    output_stream: TextIO,
    service: InteractionService | None = None,
    voice_input: VoiceInputAdapter | None = None,
) -> int:
    """Run the minimal interactive Foresight terminal loop."""

    interaction_service = service or InteractionService()
    output_stream.write("Foresight Lab v0.4\n")
    output_stream.write("Type a message, 'voice', 'status', or 'exit'.\n")

    while True:
        output_stream.write("> ")
        output_stream.flush()

        try:
            raw_command = input_stream.readline()
        except KeyboardInterrupt:
            output_stream.write("\nExiting Foresight Lab.\n")
            output_stream.flush()
            return 0

        if raw_command == "":
            output_stream.write("\nExiting Foresight Lab.\n")
            output_stream.flush()
            return 0

        if raw_command.strip().lower() == "voice" and voice_input is not None:
            output_stream.write("Listening for one utterance...\n")
            output_stream.flush()

        result = handle_command(raw_command, interaction_service, voice_input)
        for line in result.lines:
            output_stream.write(f"{line}\n")
        output_stream.flush()

        if result.should_exit:
            return 0
