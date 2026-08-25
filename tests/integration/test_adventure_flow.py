from foresight_device.interaction import InteractionModality, InteractionService, IntentType, UserInteraction
from foresight_device.sessions import SessionStatus


def test_adventure_mode_confirmation_flow() -> None:
    service = InteractionService()

    start_outcome = service.process(
        UserInteraction(
            content="Hey Foresight, I'm going on an adventure.",
            modality=InteractionModality.VOICE,
        )
    )

    assert start_outcome.intent is IntentType.START_ADVENTURE
    assert start_outcome.session is not None
    assert start_outcome.session.status is SessionStatus.PENDING_CONFIRMATION
    assert [event.event_type.value for event in start_outcome.session_events] == [
        "session_proposed"
    ]
    assert start_outcome.assistant_response is not None
    assert start_outcome.assistant_response.message == "Would you like me to record this event?"

    confirm_outcome = service.process(
        UserInteraction(
            content="Yes",
            modality=InteractionModality.VOICE,
        )
    )

    assert confirm_outcome.intent is IntentType.CONFIRM_YES
    assert confirm_outcome.session is not None
    assert confirm_outcome.session.status is SessionStatus.ACTIVE
    assert [event.event_type.value for event in confirm_outcome.session_events] == [
        "session_confirmed",
        "session_started",
    ]
    assert confirm_outcome.assistant_response is not None
    assert confirm_outcome.assistant_response.message == "Adventure recording started."
    assert confirm_outcome.assistant_response.metadata == {
        "planned_confirmation_cue": "AUDIBLE_BEEP"
    }
