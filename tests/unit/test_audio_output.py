from foresight_device.cli import handle_command
from foresight_device.interaction import InteractionService
from foresight_device.output import AudioCue, AudioOutputUnavailableError


class RecordingCueOutput:
    def __init__(self) -> None:
        self.cues: list[AudioCue] = []

    def play_cue(self, cue: AudioCue) -> None:
        self.cues.append(cue)


class RecordingSpeechOutput:
    def __init__(self) -> None:
        self.messages: list[str] = []

    def speak(self, text: str) -> None:
        self.messages.append(text)


class FailingCueOutput:
    def play_cue(self, cue: AudioCue) -> None:
        raise AudioOutputUnavailableError("Speaker is unavailable.")


class FailingSpeechOutput:
    def speak(self, text: str) -> None:
        raise AudioOutputUnavailableError("Text-to-speech is unavailable.")


def test_wake_requests_audio_cue_and_preserves_terminal_beep() -> None:
    cue_output = RecordingCueOutput()

    result = handle_command("Hey Foresight", InteractionService(), cue_output=cue_output)

    assert cue_output.cues == [AudioCue.WAKE_ACKNOWLEDGEMENT]
    assert result.lines == ("[BEEP]", "Foresight: Listening...")


def test_assistant_message_is_forwarded_to_speech_output() -> None:
    speech_output = RecordingSpeechOutput()

    result = handle_command("Take a note", InteractionService(), speech_output=speech_output)

    assert "Foresight: What would you like me to note?" in result.lines
    assert speech_output.messages == ["What would you like me to note?"]


def test_status_diagnostics_are_not_forwarded_to_speech_output() -> None:
    speech_output = RecordingSpeechOutput()

    handle_command("status", InteractionService(), speech_output=speech_output)

    assert speech_output.messages == []


def test_cue_output_failure_preserves_terminal_response() -> None:
    result = handle_command("Hey Foresight", InteractionService(), cue_output=FailingCueOutput())

    assert "[BEEP]" in result.lines
    assert "Foresight: Listening..." in result.lines
    assert "Audio output unavailable: Speaker is unavailable." in result.lines


def test_speech_output_failure_preserves_terminal_response() -> None:
    result = handle_command(
        "Take a note",
        InteractionService(),
        speech_output=FailingSpeechOutput(),
    )

    assert "Foresight: What would you like me to note?" in result.lines
    assert "Speech output unavailable: Text-to-speech is unavailable." in result.lines
