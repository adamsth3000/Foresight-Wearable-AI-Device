import pytest

from foresight_device.interaction import InteractionModality, UserInteraction
from foresight_device.sessions import SessionEventType, SessionService, SessionStatus
from foresight_device.sessions.service import SessionStateError


def test_pending_session_can_be_cancelled() -> None:
    service = SessionService()
    service.propose_adventure_session(
        UserInteraction(
            content="Hey Foresight, I'm going on an adventure.",
            modality=InteractionModality.VOICE,
        )
    )

    session, events = service.cancel_pending_session(
        UserInteraction(
            content="No",
            modality=InteractionModality.VOICE,
        )
    )

    assert session.status is SessionStatus.CANCELLED
    assert [event.event_type for event in events] == [SessionEventType.SESSION_CANCELLED]
    assert service.current_session is None


def test_only_one_pending_or_active_session_is_allowed() -> None:
    service = SessionService()
    service.propose_adventure_session(
        UserInteraction(
            content="Hey Foresight, I'm going on an adventure.",
            modality=InteractionModality.VOICE,
        )
    )

    with pytest.raises(SessionStateError):
        service.propose_adventure_session(
            UserInteraction(
                content="Hey Foresight, I'm going on an adventure.",
                modality=InteractionModality.VOICE,
            )
        )


def test_active_session_can_record_future_facing_normalized_events() -> None:
    service = SessionService()
    service.propose_adventure_session(
        UserInteraction(
            content="Hey Foresight, I'm going on an adventure.",
            modality=InteractionModality.VOICE,
        )
    )
    service.confirm_pending_session(
        UserInteraction(
            content="Yes",
            modality=InteractionModality.VOICE,
        )
    )

    location_event = service.record_event(
        SessionEventType.LOCATION_EVENT_RECORDED,
        payload={"provider": "simulated"},
    )
    media_event = service.record_event(
        SessionEventType.MEDIA_EVENT_RECORDED,
        payload={"provider": "simulated"},
    )

    assert location_event.event_type is SessionEventType.LOCATION_EVENT_RECORDED
    assert media_event.event_type is SessionEventType.MEDIA_EVENT_RECORDED
    assert service.current_session is not None
    assert service.current_session.status is SessionStatus.ACTIVE


def test_active_session_can_end() -> None:
    service = SessionService()
    service.propose_adventure_session(
        UserInteraction(
            content="Hey Foresight, I'm going on an adventure.",
            modality=InteractionModality.VOICE,
        )
    )
    service.confirm_pending_session(
        UserInteraction(
            content="Yes",
            modality=InteractionModality.VOICE,
        )
    )

    session, events = service.end_active_session()

    assert session.status is SessionStatus.ENDED
    assert [event.event_type for event in events] == [SessionEventType.SESSION_ENDED]
    assert service.current_session is None
