from foresight_device.interaction import (
    InteractionModality,
    InteractionService,
    InteractionSource,
    IntentType,
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
