"""Metadata and input helpers for isolated field-evaluation recordings."""
# ruff: noqa: E501

from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from common import TRAINING_ROOT

FIELD_EVALUATION_ROOT = TRAINING_ROOT / "data" / "field_evaluation"
FIELD_CATEGORIES = ("positive", "ordinary_speech", "ambient", "tv_background", "noise")
SOURCE_DEVICES = ("laptop_mic", "gopro_mic", "phone_mic", "future_wearable_mic")


def metadata_path(category_dir: Path) -> Path:
    return category_dir / "metadata.jsonl"


def metadata_paths(root: Path = FIELD_EVALUATION_ROOT) -> list[Path]:
    """Return existing category metadata files in a stable order for provenance."""

    return [path for category in FIELD_CATEGORIES if (path := metadata_path(root / category)).is_file()]


def append_metadata(category_dir: Path, record: dict[str, object]) -> None:
    """Append one immutable local recording-decision record."""

    category_dir.mkdir(parents=True, exist_ok=True)
    with metadata_path(category_dir).open("a", encoding="utf-8") as output:
        output.write(json.dumps(record, sort_keys=True) + "\n")


def read_accepted_records(root: Path = FIELD_EVALUATION_ROOT) -> list[dict[str, object]]:
    """Return accepted, category-contained field WAVs in deterministic order only."""

    records: list[dict[str, object]] = []
    for category in FIELD_CATEGORIES:
        category_dir = root / category
        path = metadata_path(category_dir)
        if not path.is_file():
            continue
        for line in path.read_text(encoding="utf-8").splitlines():
            record: dict[str, Any] = json.loads(line)
            if record.get("status") != "accepted":
                continue
            recording_path = Path(str(record.get("path", "")))
            if (
                record.get("category") != category
                or not recording_path.is_file()
                or category_dir not in recording_path.parents
            ):
                raise RuntimeError(f"Invalid accepted field-evaluation record in {path}.")
            records.append({**record, "path": recording_path})
    return sorted(records, key=lambda record: (str(record["category"]), str(record["path"])))


def field_evaluation_inputs(root: Path = FIELD_EVALUATION_ROOT) -> list[dict[str, object]]:
    """Return only accepted field recordings, never training or legacy evaluation data."""

    records = read_accepted_records(root)
    return [
        {
            **record,
            "positive": record["category"] == "positive",
        }
        for record in records
    ]


def require_field_evaluation_inputs(inputs: list[dict[str, object]]) -> None:
    if not any(record["positive"] for record in inputs):
        raise RuntimeError("Field evaluation requires at least one accepted positive recording.")
    if not any(not record["positive"] for record in inputs):
        raise RuntimeError("Field evaluation requires at least one accepted negative recording.")


def fixed_window_audio(records: list[dict[str, object]]) -> object:
    """Extract each metadata-declared two-second field window without speech trimming."""

    import numpy as np
    import soundfile as sf

    sample_rate = 16_000
    window_samples = sample_rate * 2
    clips = np.zeros((len(records), window_samples), dtype=np.int16)
    for index, record in enumerate(records):
        samples, source_rate = sf.read(Path(str(record["path"])), dtype="float32", always_2d=True)
        if source_rate != sample_rate:
            raise RuntimeError(f"{record['path']} must be {sample_rate} Hz, not {source_rate} Hz.")
        start = round(float(record.get("analysis_start_seconds", 0.0)) * sample_rate)
        mono = np.clip(np.mean(samples, axis=1), -1.0, 1.0)
        window = mono[start : start + window_samples]
        clips[index, : len(window)] = (window * 32767).astype(np.int16)
    return clips
