from io import StringIO

from foresight_device.cli import run_cli


class TranscriptVoiceInput:
    """Deterministic voice adapter used without microphone or model access."""

    def __init__(self, transcripts: list[str | None]) -> None:
        self._transcripts = transcripts

    def listen_once(self) -> str | None:
        return self._transcripts.pop(0)


def test_voice_adventure_flow_uses_existing_interaction_and_session_services() -> None:
    input_stream = StringIO("voice\nexit\n")
    output_stream = StringIO()
    voice_input = TranscriptVoiceInput(
        ["Hey Foresight", "I'm going on an adventure.", "Yes"]
    )

    exit_code = run_cli(input_stream, output_stream, voice_input=voice_input)
    output = output_stream.getvalue()

    assert exit_code == 0
    assert output.count("Listening for one utterance...") == 3
    assert 'Transcript: "Hey Foresight"' in output
    assert 'Transcript: "I\'m going on an adventure."' in output
    assert "[BEEP]" in output
    assert "Would you like me to record this event?" in output
    assert "Adventure recording started." in output


def test_voice_note_flow_uses_existing_pending_context() -> None:
    input_stream = StringIO("voice\nexit\n")
    output_stream = StringIO()
    voice_input = TranscriptVoiceInput(["Hey Foresight", "Take a note", "Bring a map"])

    exit_code = run_cli(input_stream, output_stream, voice_input=voice_input)
    output = output_stream.getvalue()

    assert exit_code == 0
    assert "What would you like me to note?" in output
    assert "Note recorded." in output


def test_voice_shopping_flow_uses_existing_pending_context() -> None:
    input_stream = StringIO("voice\nexit\n")
    output_stream = StringIO()
    voice_input = TranscriptVoiceInput(
        ["Hey Foresight", "Add something to my shopping list", "Coffee"]
    )

    exit_code = run_cli(input_stream, output_stream, voice_input=voice_input)
    output = output_stream.getvalue()

    assert exit_code == 0
    assert output.count("Listening for one utterance...") == 3
    assert "What would you like to add?" in output
    assert "Added Coffee to your shopping list." in output


def test_empty_voice_transcript_returns_to_the_cli() -> None:
    input_stream = StringIO("voice\nexit\n")
    output_stream = StringIO()

    exit_code = run_cli(
        input_stream,
        output_stream,
        voice_input=TranscriptVoiceInput([None]),
    )
    output = output_stream.getvalue()

    assert exit_code == 0
    assert output.count("Listening for one utterance...") == 1
    assert "Transcript: <none>" in output
    assert "Foresight: No usable speech detected." in output
    assert "Exiting Foresight Lab." in output


def test_text_cli_remains_available_when_a_voice_adapter_is_configured() -> None:
    input_stream = StringIO("Hey Foresight\nexit\n")
    output_stream = StringIO()

    exit_code = run_cli(input_stream, output_stream, voice_input=TranscriptVoiceInput([]))

    assert exit_code == 0
    assert "Foresight: Listening..." in output_stream.getvalue()
