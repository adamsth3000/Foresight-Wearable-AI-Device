"""Small LAN-only event control boundary owned by the laptop capture runtime."""

from __future__ import annotations

from dataclasses import dataclass

from .event_service import EventService, EventStateError


@dataclass(frozen=True, slots=True)
class EventControlService:
    """Maps untrusted transport requests to authoritative event transitions."""

    events: EventService

    def start(self) -> dict[str, object]:
        event_id = self.events.start_bounded()
        return self._state("recording_bounded_event", event_id)

    def end(self) -> dict[str, object]:
        event_id = self.events.end_bounded()
        return self._state("finalizing", event_id)

    def quick(self) -> dict[str, object]:
        event_id = self.events.trigger()
        return self._state("quick_event_pending", event_id)

    def status(self) -> dict[str, object]:
        active = self.events.bounded_event_id
        return self._state(self.events.bounded_event_state, active)

    def _state(self, state: str, event_id: str | None) -> dict[str, object]:
        return {"state": state, "event_id": event_id, "pending_events": self.events.pending_count}


__all__ = ["EventControlService", "EventStateError"]
