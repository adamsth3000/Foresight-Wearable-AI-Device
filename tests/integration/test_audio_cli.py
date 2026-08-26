from io import StringIO

from foresight_device.cli import run_cli
from foresight_device.output import AudioCue


class RecordingAudioOutput:
    def __init__(self) -> None:
        self.cues: list[AudioCue] = []
        self.messages: list[str] = []

    def play_cue(self, cue: AudioCue) -> None:
        self.cues.append(cue)

    def speak(self, text: str) -> None:
        self.messages.append(text)


def test_cli_keeps_terminal_output_while_dispatching_audio_outputs() -> None:
    input_stream = StringIO("Hey Foresight\nTake a note\nBring a map\nexit\n")
    output_stream = StringIO()
    audio_output = RecordingAudioOutput()

    exit_code = run_cli(
        input_stream,
        output_stream,
        cue_output=audio_output,
        speech_output=audio_output,
    )
    output = output_stream.getvalue()

    assert exit_code == 0
    assert "[BEEP]" in output
    assert "Foresight: Listening..." in output
    assert "Foresight: What would you like me to note?" in output
    assert "Foresight: Note recorded." in output
    assert audio_output.cues == [AudioCue.WAKE_ACKNOWLEDGEMENT]
    assert audio_output.messages == [
        "Listening...",
        "What would you like me to note?",
        "Note recorded.",
    ]
