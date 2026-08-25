from io import StringIO

from foresight_device.cli import (
    build_text_interaction,
    handle_command,
    render_assistant_response,
    render_session_status,
)
from foresight_device.interaction import AssistantResponse, InteractionService, InteractionSource


def test_build_text_interaction_uses_text_and_simulated_source() -> None:
    interaction = build_text_interaction("Hello, Foresight")

    assert interaction.content == "Hello, Foresight"
    assert interaction.modality.value == "text"
    assert interaction.source is InteractionSource.SIMULATED


def test_status_with_no_session_is_compact() -> None:
    lines = render_session_status(None)

    assert lines == ("No active or pending session.",)


def test_status_with_pending_session_shows_summary() -> None:
    service = InteractionService()
    handle_command("Hey Foresight, I'm going on an adventure.", service)

    lines = render_session_status(service.sessions.current_session)

    assert lines[0] == "Session Type: adventure"
    assert lines[1] == "Status: pending_confirmation"
    assert lines[2].startswith("Session ID: ")
    assert lines[3] == "Event Count: 1"


def test_exit_command_is_recognized() -> None:
    result = handle_command("exit", InteractionService())

    assert result.should_exit is True
    assert result.lines == ("Exiting Foresight Lab.",)


def test_assistant_response_rendering_includes_optional_planned_cue() -> None:
    response = AssistantResponse(
        message="Adventure recording started.",
        metadata={"planned_confirmation_cue": "AUDIBLE_BEEP"},
    )

    lines = render_assistant_response(response)

    assert lines == (
        "Foresight: Adventure recording started.",
        "(Planned confirmation cue: AUDIBLE_BEEP)",
    )


def test_assistant_response_rendering_omits_optional_planned_cue() -> None:
    response = AssistantResponse(message="Would you like me to record this event?")

    lines = render_assistant_response(response)

    assert lines == ("Foresight: Would you like me to record this event?",)
