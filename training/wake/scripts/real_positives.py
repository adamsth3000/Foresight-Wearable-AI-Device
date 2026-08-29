"""Deterministic provenance helpers for the small real-positive v2 anchor set."""
# ruff: noqa: E501

from __future__ import annotations

import json
from pathlib import Path

from prototype_data import file_sha256

SPLIT_NAMES = ("train", "validation", "held_out")


def deterministic_split(
    paths: list[Path], train_count: int, validation_count: int, held_out_count: int
) -> dict[str, list[Path]]:
    """Partition sorted recordings once, without copying or modifying any WAV."""

    ordered = sorted(paths)
    expected = train_count + validation_count + held_out_count
    if len(ordered) != expected:
        raise RuntimeError(
            f"Expected exactly {expected} real positive WAVs for the deterministic split, "
            f"found {len(ordered)}."
        )
    train_end = train_count
    validation_end = train_end + validation_count
    return {
        "train": ordered[:train_end],
        "validation": ordered[train_end:validation_end],
        "held_out": ordered[validation_end:],
    }


def write_split_manifest(path: Path, profile: str, run_id: str, split: dict[str, list[Path]]) -> None:
    """Persist the exact recording identity and assignment used by a run."""

    payload = {
        "profile": profile,
        "run_id": run_id,
        "split": {
            name: [{"path": str(item), "sha256": file_sha256(item)} for item in split[name]]
            for name in SPLIT_NAMES
        },
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def load_split(path: Path) -> dict[str, list[Path]]:
    """Load a previously reviewed real-positive assignment."""

    payload = json.loads(path.read_text(encoding="utf-8"))
    split = payload.get("split", {})
    if set(split) != set(SPLIT_NAMES):
        raise RuntimeError(f"Invalid real-positive split manifest: {path}")
    return {name: [Path(item["path"]) for item in split[name]] for name in SPLIT_NAMES}
