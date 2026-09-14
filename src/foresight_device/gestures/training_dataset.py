"""Read-only gesture-data audit and deterministic future-training extraction."""

from __future__ import annotations

import argparse
import json
import os
from datetime import UTC, datetime
from pathlib import Path
from statistics import mean, median

from foresight_device.annotation.gesture_annotations import (
    GestureAnnotation,
    GestureAnnotationStore,
)
from foresight_device.body_perception.artifact import (
    BodyArtifact,
    load_body_artifact,
    sha256,
    verify_body_media,
)
from foresight_device.perception.event_media import ResolvedEventMedia, resolve_event_media
from foresight_device.visualization.ffmpeg_renderer import FfmpegOverlayRenderer, VideoRenderError


class GestureTrainingDatasetError(RuntimeError):
    """Training extraction inputs are not safe to combine."""


def audit_gesture_dataset(store: GestureAnnotationStore, body: BodyArtifact) -> dict[str, object]:
    """Summarize valid human annotations without writing a derived artifact."""

    annotations = store.load()
    tracks = store.load_tracks()
    records = _records(store, body, annotations)
    return {
        "event_id": store.event_id,
        "confirmed_gesture_tracks": len(tracks),
        "unlabeled_gesture_tracks": len(tracks) - len(annotations),
        "confirmed_gesture_samples": len(annotations),
        "landmark_training_eligible": sum(
            bool(record["landmark_training_eligible"]) for record in records
        ),
        "landmark_training_ineligible": sum(
            not bool(record["landmark_training_eligible"]) for record in records
        ),
        "supporting_tracks": _bucket_counts(records, "supporting_track_count"),
        "body_observations": _bucket_counts(records, "observation_count"),
        "observation_count": _statistics([record["observation_count"] for record in records]),
        "duration_seconds": _statistics([record["duration_seconds"] for record in records]),
        "by_gesture": _by_gesture(records),
        "by_human_handedness": _counts(records, "human_handedness"),
        "classes_with_fewer_than_two_samples": sorted(
            label
            for label, summary in _by_gesture(records).items()
            if _integer(summary["samples"]) < 2
        ),
    }


def build_gesture_training_dataset(
    store: GestureAnnotationStore,
    body: BodyArtifact,
    *,
    annotation_path: Path,
    body_path: Path,
    media: ResolvedEventMedia,
) -> dict[str, object]:
    """Create a deterministic, provenance-bound representation for later feature work."""

    annotations = store.load()
    annotation_sha256 = sha256(annotation_path)
    body_sha256 = sha256(body_path)
    records = _records(
        store,
        body,
        annotations,
        source_body_sha256=body_sha256,
        source_media_sha256=media.sha256,
    )
    eligible = sum(bool(record["landmark_training_eligible"]) for record in records)
    return {
        "schema_version": 1,
        "event_id": store.event_id,
        "source_gesture_annotations": {
            "filename": annotation_path.name,
            "sha256": annotation_sha256,
        },
        "source_body_perception": {"filename": body_path.name, "sha256": body_sha256},
        "source_media": {
            "filename": media.relative_path,
            "sha256": media.sha256,
            "source": media.source,
        },
        "extraction": {"algorithm": "gesture_training_dataset_v1"},
        "sample_counts": {
            "total": len(records),
            "landmark_training_eligible": eligible,
            "landmark_training_ineligible": len(records) - eligible,
        },
        "samples": records,
        "created_at_utc": datetime.now(UTC).isoformat(),
    }


def write_gesture_training_dataset(path: Path, dataset: dict[str, object]) -> None:
    """Atomically persist a derived dataset; source artifacts are never modified."""

    temporary = path.with_suffix(path.suffix + ".tmp")
    path.parent.mkdir(parents=True, exist_ok=True)
    with temporary.open("w", encoding="utf-8") as handle:
        json.dump(dataset, handle, indent=2, sort_keys=True)
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)


def _records(
    store: GestureAnnotationStore,
    body: BodyArtifact,
    annotations: tuple[GestureAnnotation, ...],
    *,
    source_body_sha256: str | None = None,
    source_media_sha256: str | None = None,
) -> list[dict[str, object]]:
    observation_track_ids = {
        observation_id: track.hand_track_id
        for track in body.tracks
        for observation_id in track.observation_ids
    }
    records: list[dict[str, object]] = []
    for annotation in sorted(
        annotations, key=lambda item: (item.start_timestamp_seconds, item.annotation_id)
    ):
        observations = store.observations_for(annotation)
        duration = annotation.end_timestamp_seconds - annotation.start_timestamp_seconds
        records.append(
            {
                "gesture_sample_id": annotation.annotation_id,
                "gesture_track_id": annotation.gesture_track_id or annotation.annotation_id,
                "event_id": body.event_id,
                "label": annotation.gesture_label.value,
                "human_handedness": (
                    annotation.human_handedness.value
                    if annotation.human_handedness is not None
                    else "UNASSIGNED"
                ),
                "start_timestamp_seconds": annotation.start_timestamp_seconds,
                "end_timestamp_seconds": annotation.end_timestamp_seconds,
                "duration_seconds": duration,
                "supporting_hand_track_ids": list(annotation.hand_track_ids),
                "supporting_track_count": len(annotation.hand_track_ids),
                "human_labeled": True,
                "human_handedness_valid": annotation.human_handedness is not None,
                "landmark_training_eligible": bool(observations)
                and annotation.human_handedness is not None
                and duration > 0,
                "has_supporting_tracks": bool(annotation.hand_track_ids),
                "has_body_observations": bool(observations),
                "multi_track_evidence": len(annotation.hand_track_ids) > 1,
                "duration_valid": duration > 0,
                "provenance_valid": True,
                "source_annotation_id": annotation.annotation_id,
                **(
                    {"source_body_perception": {"sha256": source_body_sha256}}
                    if source_body_sha256 is not None
                    else {}
                ),
                **(
                    {"source_media": {"sha256": source_media_sha256}}
                    if source_media_sha256 is not None
                    else {}
                ),
                "observation_count": len(observations),
                "observations": [
                    {
                        "hand_observation_id": observation.hand_observation_id,
                        "media_timestamp_seconds": observation.media_timestamp_seconds,
                        "relative_timestamp_seconds": (
                            observation.media_timestamp_seconds - annotation.start_timestamp_seconds
                        ),
                        "hand_track_id": observation_track_ids.get(observation.hand_observation_id),
                        "mediapipe_handedness": observation.handedness.value,
                        "confidence": observation.confidence,
                        "landmarks": [
                            {
                                "name": landmark.name,
                                "x": landmark.x,
                                "y": landmark.y,
                                "z": landmark.z,
                            }
                            for landmark in observation.landmarks
                        ],
                    }
                    for observation in sorted(
                        observations,
                        key=lambda item: (item.media_timestamp_seconds, item.hand_observation_id),
                    )
                ],
            }
        )
    return records


def _statistics(values: list[object]) -> dict[str, float | None]:
    numbers = [_number(value) for value in values]
    if not numbers:
        return {"min": None, "median": None, "mean": None, "max": None}
    return {
        "min": min(numbers),
        "median": median(numbers),
        "mean": mean(numbers),
        "max": max(numbers),
    }


def _counts(records: list[dict[str, object]], key: str) -> dict[str, int]:
    counts: dict[str, int] = {}
    for record in records:
        value = str(record[key])
        counts[value] = counts.get(value, 0) + 1
    return dict(sorted(counts.items()))


def _bucket_counts(records: list[dict[str, object]], key: str) -> dict[str, int]:
    values = [_integer(record[key]) for record in records]
    return {
        "zero": sum(value == 0 for value in values),
        "one": sum(value == 1 for value in values),
        "multiple": sum(value > 1 for value in values),
    }


def _by_gesture(records: list[dict[str, object]]) -> dict[str, dict[str, object]]:
    output: dict[str, dict[str, object]] = {}
    for label in sorted({str(record["label"]) for record in records}):
        group = [record for record in records if record["label"] == label]
        output[label] = {
            "samples": len(group),
            "RIGHT": sum(record["human_handedness"] == "RIGHT" for record in group),
            "LEFT": sum(record["human_handedness"] == "LEFT" for record in group),
            "BOTH": sum(record["human_handedness"] == "BOTH" for record in group),
            "with_landmark_evidence": sum(
                bool(record["landmark_training_eligible"]) for record in group
            ),
            "without_landmark_evidence": sum(
                not bool(record["landmark_training_eligible"]) for record in group
            ),
            "observation_count": sum(_integer(record["observation_count"]) for record in group),
            "mean_duration_seconds": mean(_number(record["duration_seconds"]) for record in group),
        }
    return output


def _number(value: object) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise GestureTrainingDatasetError("derived training record has invalid numeric data")
    return float(value)


def _integer(value: object) -> int:
    return int(_number(value))


def _store_for_event(
    event_dir: Path, event_id: str
) -> tuple[GestureAnnotationStore, BodyArtifact, Path, ResolvedEventMedia]:
    body_path = event_dir / "event_body_perception.json"
    annotation_path = event_dir / "event_gesture_annotations.json"
    if not annotation_path.is_file():
        raise GestureTrainingDatasetError("event has no human gesture annotations")
    body = load_body_artifact(body_path, event_id=event_id)
    media = resolve_event_media(event_dir)
    verify_body_media(body, media)
    duration = _media_duration_seconds(event_dir, media)
    store = GestureAnnotationStore(
        annotation_path,
        event_id=event_id,
        body_path=body_path,
        body=body,
        media=media,
        media_duration_seconds=duration,
    )
    store.load()  # Verify annotation provenance before audit or extraction.
    return store, body, annotation_path, media


def _media_duration_seconds(event_dir: Path, media: ResolvedEventMedia) -> float:
    """Probe the media when available, else use its validated local manifest summary."""

    try:
        return FfmpegOverlayRenderer().probe_dimensions(media.path).duration_seconds
    except VideoRenderError as probe_error:
        try:
            manifest = json.loads((event_dir / "manifest.json").read_text(encoding="utf-8"))
            phone_local = manifest["phone_local"]
            if (
                not isinstance(phone_local, dict)
                or phone_local.get("validated") is not True
                or phone_local.get("path") != media.relative_path
                or phone_local.get("sha256") != media.sha256
            ):
                raise GestureTrainingDatasetError(
                    "ffprobe is unavailable and the manifest cannot validate the selected media"
                ) from probe_error
            ffprobe = phone_local.get("ffprobe")
            if not isinstance(ffprobe, dict) or not isinstance(ffprobe.get("format"), dict):
                raise GestureTrainingDatasetError(
                    "ffprobe is unavailable and the manifest has no media duration"
                ) from probe_error
            duration = float(ffprobe["format"]["duration"])
        except (OSError, KeyError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise GestureTrainingDatasetError(
                "ffprobe is unavailable and the manifest has no valid media duration"
            ) from exc
        if duration <= 0:
            raise GestureTrainingDatasetError(
                "manifest media duration must be positive"
            ) from probe_error
        return duration


def _format_audit(audit: dict[str, object]) -> str:
    """Render the stable audit data as a compact terminal report."""

    supporting_tracks = _audit_mapping(audit, "supporting_tracks")
    body_observations = _audit_mapping(audit, "body_observations")
    observation_count = _audit_mapping(audit, "observation_count")
    duration_seconds = _audit_mapping(audit, "duration_seconds")
    handedness = _audit_mapping(audit, "by_human_handedness")
    lines = [
        "GESTURE DATASET AUDIT",
        "",
        f"Event: {audit['event_id']}",
        f"Confirmed Gesture Tracks: {audit['confirmed_gesture_tracks']}",
        f"Confirmed Gesture Samples: {audit['confirmed_gesture_samples']}",
        f"Unlabeled Tracks: {audit['unlabeled_gesture_tracks']}",
        "",
        f"Landmark eligible: {audit['landmark_training_eligible']}",
        f"No landmark evidence: {audit['landmark_training_ineligible']}",
        "Evidence tracks (zero/one/multiple): "
        f"{supporting_tracks['zero']}/{supporting_tracks['one']}/{supporting_tracks['multiple']}",
        "Body observations (zero/one/multiple): "
        f"{body_observations['zero']}/{body_observations['one']}/{body_observations['multiple']}",
        "Observation count (min/median/mean/max): "
        f"{observation_count['min']}/{observation_count['median']}/"
        f"{observation_count['mean']}/{observation_count['max']}",
        "Duration seconds (min/median/mean/max): "
        f"{duration_seconds['min']}/{duration_seconds['median']}/"
        f"{duration_seconds['mean']}/{duration_seconds['max']}",
        "Human handedness (RIGHT/LEFT/BOTH/UNASSIGNED): "
        f"{handedness.get('RIGHT', 0)}/{handedness.get('LEFT', 0)}/"
        f"{handedness.get('BOTH', 0)}/{handedness.get('UNASSIGNED', 0)}",
        "",
        "BY GESTURE",
    ]
    by_gesture = audit["by_gesture"]
    if not isinstance(by_gesture, dict):
        raise GestureTrainingDatasetError("audit gesture summary is invalid")
    for label, summary in by_gesture.items():
        if not isinstance(summary, dict):
            raise GestureTrainingDatasetError("audit gesture details are invalid")
        lines.extend(
            (
                "",
                str(label),
                f"  samples: {summary['samples']}",
                f"  RIGHT/LEFT/BOTH: {summary['RIGHT']}/{summary['LEFT']}/{summary['BOTH']}",
                f"  landmark evidence/no evidence: "
                f"{summary['with_landmark_evidence']}/{summary['without_landmark_evidence']}",
                f"  observations: {summary['observation_count']}",
                f"  mean duration: {float(summary['mean_duration_seconds']):.3f}s",
            )
        )
    sparse = audit["classes_with_fewer_than_two_samples"]
    if isinstance(sparse, list) and sparse:
        lines.extend(("", f"Classes with fewer than two samples: {', '.join(map(str, sparse))}"))
    return "\n".join(lines)


def _audit_mapping(audit: dict[str, object], key: str) -> dict[str, object]:
    value = audit[key]
    if not isinstance(value, dict):
        raise GestureTrainingDatasetError(f"audit {key} is invalid")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description="Audit and extract human gesture training data.")
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--data-root", type=Path, default=Path("data/capture"))
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--audit", action="store_true")
    action.add_argument("--write", action="store_true")
    options = parser.parse_args()
    event_dir = options.data_root / "events" / options.event_id
    try:
        store, body, annotation_path, media = _store_for_event(event_dir, options.event_id)
        if options.audit:
            print(_format_audit(audit_gesture_dataset(store, body)))
        else:
            dataset = build_gesture_training_dataset(
                store,
                body,
                annotation_path=annotation_path,
                body_path=event_dir / "event_body_perception.json",
                media=media,
            )
            output = event_dir / "event_gesture_training_dataset.json"
            write_gesture_training_dataset(output, dataset)
            print(f"Gesture training dataset written: {output}")
    except (GestureTrainingDatasetError, OSError, RuntimeError, ValueError) as exc:
        parser.error(str(exc))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
