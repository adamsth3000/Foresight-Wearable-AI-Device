from foresight_device.cli import (
    build_text_interaction,
    build_voice_interaction,
    handle_command,
    render_assistant_response,
    render_session_status,
    render_status,
)
from foresight_device.interaction import (
    AssistantResponse,
    InteractionModality,
    InteractionService,
    InteractionSource,
)
from foresight_device.voice import VoiceInputUnavailableError


class FakeVoiceInput:
    def __init__(self, transcript: str | None) -> None:
        self._transcript = transcript

    def listen_once(self) -> str | None:
        return self._transcript


class UnavailableVoiceInput:
    def listen_once(self) -> str | None:
        raise VoiceInputUnavailableError("Microphone is unavailable.")


def test_build_text_interaction_uses_text_and_simulated_source() -> None:
    interaction = build_text_interaction("Hello, Foresight")

    assert interaction.content == "Hello, Foresight"
    assert interaction.modality.value == "text"
    assert interaction.source is InteractionSource.SIMULATED


def test_build_voice_interaction_uses_voice_and_microphone_source() -> None:
    interaction = build_voice_interaction("Hey Foresight")

    assert interaction.content == "Hey Foresight"
    assert interaction.modality is InteractionModality.VOICE
    assert interaction.source is InteractionSource.MICROPHONE


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


def test_wake_response_renders_simulated_beep_and_listening_message() -> None:
    response = AssistantResponse(
        message="Listening...",
        metadata={"simulated_acknowledgement_cue": "BEEP"},
    )

    assert render_assistant_response(response) == ("[BEEP]", "Foresight: Listening...")


def test_status_includes_assistant_state_and_pending_context() -> None:
    service = InteractionService()
    handle_command("Hey Foresight", service)

    lines = render_status(service)

    assert "Assistant State: listening_for_command" in lines
    assert "Pending Context: none" in lines


def test_voice_transcript_reaches_existing_wake_handling() -> None:
    result = handle_command("voice", InteractionService(), FakeVoiceInput("Hey Foresight"))

    assert result.lines == ("[BEEP]", "Foresight: Listening...")


def test_voice_command_handles_empty_transcript() -> None:
    result = handle_command("voice", InteractionService(), FakeVoiceInput("   "))

    assert result.lines == ("No usable speech detected.",)


def test_voice_command_handles_unavailable_adapter() -> None:
    result = handle_command("voice", InteractionService(), UnavailableVoiceInput())

    assert result.lines == ("Voice input unavailable: Microphone is unavailable.",)
