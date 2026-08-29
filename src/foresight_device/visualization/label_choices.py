"""Pure extraction of editable relabel choices from normalized event observations."""

from __future__ import annotations

from collections.abc import Iterable

from foresight_device.perception.models import VisualObservation


def known_label_choices(observations: Iterable[VisualObservation]) -> tuple[str, ...]:
    """Return deterministic, non-empty event labels without changing their display casing."""

    choices: dict[str, str] = {}
    for observation in observations:
        label = observation.label.strip()
        if label:
            choices.setdefault(label.casefold(), label)
    return tuple(sorted(choices.values(), key=str.casefold))
