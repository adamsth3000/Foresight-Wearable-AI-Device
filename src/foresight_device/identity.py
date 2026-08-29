"""Stable actor identities shared by human and future machine interaction models."""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum


class ActorRole(StrEnum):
    WEARER = "wearer"


@dataclass(frozen=True, slots=True)
class ActorIdentity:
    """A semantic actor identity, never a detector-local person index."""

    actor_id: str
    actor_role: ActorRole

    def __post_init__(self) -> None:
        if not self.actor_id:
            raise ValueError("actor_id cannot be empty")


SELF_ACTOR = ActorIdentity(actor_id="self", actor_role=ActorRole.WEARER)
