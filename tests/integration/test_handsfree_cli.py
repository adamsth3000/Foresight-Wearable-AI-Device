from collections import deque
from io import StringIO

from foresight_device.cli import build_wake_interaction, run_hands_free_cli
from foresight_device.interaction import (
    AssistantState,
    InteractionService,
    PendingInteractionContext,
)
from foresight_device.output import AudioCue
from foresight_device.voice import WakeEvent, WakeInputUnavailableError


class FakeWakeInput:
    def __init__(self, events: list[WakeEvent], order: list[str]) -> None:
        self._events = deque(events)
        self._order = order
        self.closed = False

    def wait_for_wake(self) -> WakeEvent:
        self._order.extend(("wake_started", "wake_released"))
        if not self._events:
            raise KeyboardInterrupt
        return self._events.popleft()

    def close(self) -> None:
        self.closed = True


class FailingWakeInput:
    def __init__(self) -> None:
        self.closed = False

    def wait_for_wake(self) -> WakeEvent:
        raise WakeInputUnavailableError("Microphone is unavailable.")

    def close(self) -> None:
        self.closed = True


class OrderedVoiceInput:
    def __init__(self, transcripts: list[str | None], order: list[str]) -> None:
        self._transcripts = deque(transcripts)
        self._order = order

    def listen_once(self) -> str | None:
        assert "wake_released" in self._order
        self._order.append("voice_capture")
        return self._transcripts.popleft()


class RecordingCueOutput:
    def __init__(self) -> None:
        self.cues: list[AudioCue] = []

    def play_cue(self, cue: AudioCue) -> None:
        self.cues.append(cue)


def test_wake_event_creates_the_existing_canonical_wake_interaction() -> None:
    interaction = build_wake_interaction(WakeEvent(source="fake"))

    assert interaction.content == "Hey Foresight"
    assert interaction.modality.value == "voice"
    assert interaction.source.value == "microphone"


def test_hands_free_note_flow_releases_wake_before_voice_and_resumes() -> None:
    order: list[str] = []
    wake_input = FakeWakeInput([WakeEvent(source="fake")], order)
    service = InteractionService()
    output_stream = StringIO()
    cue_output = RecordingCueOutput()

    exit_code = run_hands_free_cli(
        output_stream,
        wake_input,
        service=service,
        voice_input=OrderedVoiceInput(["Take a note", "Bring a map"], order),
        cue_output=cue_output,
    )
    output = output_stream.getvalue()

    assert exit_code == 0
    assert "[BEEP]" in output
    assert "What would you like me to note?" in output
    assert "Note recorded." in output
    assert output.count('Waiting for "Hey Foresight"...') == 2
    assert order == [
        "wake_started",
        "wake_released",
        "voice_capture",
        "voice_capture",
        "wake_started",
        "wake_released",
    ]
    assert cue_output.cues == [AudioCue.WAKE_ACKNOWLEDGEMENT]
    assert service.assistant_state is AssistantState.IDLE
    assert service.pending_context is PendingInteractionContext.NONE
    assert wake_input.closed is True


def test_hands_free_shopping_and_adventure_followups_complete() -> None:
    order: list[str] = []
    wake_input = FakeWakeInput(
        [WakeEvent(source="fake"), WakeEvent(source="fake")],
        order,
    )
    output_stream = StringIO()
    service = InteractionService()

    exit_code = run_hands_free_cli(
        output_stream,
        wake_input,
        service=service,
        voice_input=OrderedVoiceInput(
            [
                "Add something to my shopping list",
                "Coffee",
                "Adventure time",
                "Yes",
            ],
            order,
        ),
    )
    output = output_stream.getvalue()

    assert exit_code == 0
    assert "Added Coffee to your shopping list." in output
    assert "Adventure recording started." in output
    assert service.assistant_state is AssistantState.IDLE
    assert service.pending_context is PendingInteractionContext.NONE


def test_empty_or_unknown_post_wake_command_returns_to_idle() -> None:
    order: list[str] = []
    wake_input = FakeWakeInput([WakeEvent(source="fake"), WakeEvent(source="fake")], order)
    service = InteractionService()

    exit_code = run_hands_free_cli(
        StringIO(),
        wake_input,
        service=service,
        voice_input=OrderedVoiceInput([None, "Something unrelated"], order),
    )

    assert exit_code == 0
    assert service.assistant_state is AssistantState.IDLE
    assert service.pending_context is PendingInteractionContext.NONE


def test_wake_failure_exits_hands_free_mode_cleanly() -> None:
    wake_input = FailingWakeInput()
    output_stream = StringIO()

    exit_code = run_hands_free_cli(
        output_stream,
        wake_input,
        voice_input=OrderedVoiceInput([], []),
    )

    assert exit_code == 1
    assert "Wake input unavailable: Microphone is unavailable." in output_stream.getvalue()
    assert wake_input.closed is True
