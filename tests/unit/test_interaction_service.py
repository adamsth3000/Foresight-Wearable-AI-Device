from foresight_device.interaction import (
    AssistantState,
    DeterministicIntentInterpreter,
    IntentMatch,
    IntentType,
    InteractionModality,
    InteractionService,
    InteractionSource,
    PendingInteractionContext,
    UserInteraction,
)
from foresight_device.sessions import SessionStatus


def test_adventure_prompt_creates_pending_session() -> None:
    service = InteractionService()

    interaction = UserInteraction(
        content="Hey Foresight, I'm going on an adventure.",
        modality=InteractionModality.VOICE,
        source=InteractionSource.SIMULATED,
    )

    outcome = service.process(interaction)

    assert outcome.intent is IntentType.START_ADVENTURE
    assert outcome.session is not None
    assert outcome.session.status is SessionStatus.PENDING_CONFIRMATION
    assert outcome.assistant_response is not None
    assert outcome.assistant_response.confirmation_required is True
    assert outcome.assistant_response.message == "Would you like me to record this event?"
    assert [event.event_type.value for event in outcome.session_events] == ["session_proposed"]


def test_yes_confirmation_starts_recording_with_planned_cue() -> None:
    service = InteractionService()
    service.process(
        UserInteraction(
            content="Hey Foresight, I'm going on an adventure.",
            modality=InteractionModality.VOICE,
        )
    )

    outcome = service.process(
        UserInteraction(
            content="Yes",
            modality=InteractionModality.VOICE,
        )
    )

    assert outcome.intent is IntentType.CONFIRM_YES
    assert outcome.session is not None
    assert outcome.session.status is SessionStatus.ACTIVE
    assert outcome.assistant_response is not None
    assert outcome.assistant_response.message == "Adventure recording started."
    assert outcome.assistant_response.metadata["planned_confirmation_cue"] == "AUDIBLE_BEEP"
    assert [event.event_type.value for event in outcome.session_events] == [
        "session_confirmed",
        "session_started",
    ]


def test_unknown_interaction_leaves_session_unchanged() -> None:
    service = InteractionService()

    outcome = service.process(
        UserInteraction(
            content="What should I do next?",
            modality=InteractionModality.TEXT,
        )
    )

    assert outcome.intent is IntentType.UNKNOWN
    assert outcome.session is None
    assert outcome.assistant_response is None
    assert outcome.session_events == ()


def test_gesture_interaction_uses_same_normalized_model() -> None:
    interaction = UserInteraction(
        content="simulate_adventure_gesture",
        modality=InteractionModality.GESTURE,
    )

    assert interaction.modality is InteractionModality.GESTURE
    assert interaction.source is InteractionSource.SIMULATED


def test_wake_phrase_acknowledges_without_using_intent_interpreter() -> None:
    service = InteractionService()

    outcome = service.process(
        UserInteraction(content="Hey Foresight", modality=InteractionModality.TEXT)
    )

    assert outcome.intent is IntentType.UNKNOWN
    assert outcome.assistant_response is not None
    assert outcome.assistant_response.message == "Listening..."
    assert outcome.assistant_response.metadata["simulated_acknowledgement_cue"] == "BEEP"
    assert service.assistant_state is AssistantState.LISTENING_FOR_COMMAND
    assert service.pending_context is PendingInteractionContext.NONE


def test_deterministic_interpreter_supports_example_phrase_variations() -> None:
    interpreter = DeterministicIntentInterpreter()

    assert interpreter.interpret(
        UserInteraction(content="Adventure time.", modality=InteractionModality.TEXT)
    ).intent is IntentType.START_ADVENTURE
    assert interpreter.interpret(
        UserInteraction(content="I want to make a note.", modality=InteractionModality.TEXT)
    ).intent is IntentType.TAKE_NOTE
    assert interpreter.interpret(
        UserInteraction(
            content="I need to add something to the grocery list.",
            modality=InteractionModality.TEXT,
        )
    ).intent is IntentType.ADD_SHOPPING_ITEM


def test_service_accepts_an_independent_intent_interpreter() -> None:
    class NoteInterpreter:
        def interpret(self, interaction: UserInteraction) -> IntentMatch:
            return IntentMatch(IntentType.TAKE_NOTE, matched_by="test")

    service = InteractionService(interpreter=NoteInterpreter())

    outcome = service.process(
        UserInteraction(content="anything", modality=InteractionModality.TEXT)
    )

    assert outcome.intent is IntentType.TAKE_NOTE
    assert service.pending_context is PendingInteractionContext.AWAITING_NOTE_CONTENT


def test_note_content_is_captured_in_memory_and_clears_context() -> None:
    service = InteractionService()
    service.process(UserInteraction(content="Take a note", modality=InteractionModality.TEXT))

    outcome = service.process(
        UserInteraction(content="Bring a map", modality=InteractionModality.TEXT)
    )

    assert outcome.assistant_response is not None
    assert outcome.assistant_response.message == "Note recorded."
    assert outcome.captured_content is not None
    assert outcome.captured_content.content == "Bring a map"
    assert outcome.captured_content.content_type.value == "note"
    assert service.captured_content == (outcome.captured_content,)
    assert service.assistant_state is AssistantState.IDLE
    assert service.pending_context is PendingInteractionContext.NONE


def test_shopping_content_is_captured_in_memory_and_clears_context() -> None:
    service = InteractionService()
    service.process(
        UserInteraction(
            content="Add something to my shopping list",
            modality=InteractionModality.TEXT,
        )
    )

    outcome = service.process(UserInteraction(content="coffee", modality=InteractionModality.TEXT))

    assert outcome.assistant_response is not None
    assert outcome.assistant_response.message == "Added coffee to your shopping list."
    assert outcome.captured_content is not None
    assert outcome.captured_content.content_type.value == "shopping_item"
    assert outcome.captured_content.content == "coffee"
