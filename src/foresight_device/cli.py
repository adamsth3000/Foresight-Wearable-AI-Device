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


def handle_command(command: str, service: InteractionService) -> CommandResult:
    """Handle a single terminal command."""

    normalized = command.strip()
    lowered = normalized.lower()

    if lowered in {"exit", "quit"}:
        return CommandResult(lines=("Exiting Foresight Lab.",), should_exit=True)

    if lowered == "status":
        return CommandResult(lines=render_status(service))

    if not normalized:
        return CommandResult()

    interaction = build_text_interaction(normalized)
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
) -> int:
    """Run the minimal interactive Foresight terminal loop."""

    interaction_service = service or InteractionService()
    output_stream.write("Foresight Lab v0.3\n")
    output_stream.write("Type a message, 'status', or 'exit'.\n")

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

        result = handle_command(raw_command, interaction_service)
        for line in result.lines:
            output_stream.write(f"{line}\n")
        output_stream.flush()

        if result.should_exit:
            return 0
