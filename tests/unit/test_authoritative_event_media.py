"""Offline consumers must share one validated event-media choice."""

from __future__ import annotations

import hashlib
import json
import sys
from collections.abc import Iterator
from pathlib import Path

import pytest

from foresight_device.body_perception.artifact import (
    ArtifactProvenanceError,
    load_body_artifact,
    verify_body_media,
)
from foresight_device.body_perception.pipeline import process as process_body
from foresight_device.body_perception.provider import EmptyBodyProvider
from foresight_device.perception.event_media import (
    ArtifactMediaProvenance,
    artifact_media_matches,
    resolve_event_media,
)
from foresight_device.perception.event_processor import EventPerceptionProcessor
from foresight_device.perception.models import SampledFrame
from foresight_device.visualization import __main__ as render_main
from foresight_device.visualization.editor_main import main as editor_main
from foresight_device.visualization.perception_loader import (
    PerceptionArtifactError,
    load_perception,
)

EVENT_ID = "c4426cce-1ac2-47d6-9e9f-0c8d5c9e0543"


class RecordingSampler:
    def __init__(self) -> None:
        self.paths: list[Path] = []

    def sample(self, media_path: Path, interval_seconds: float) -> Iterator[SampledFrame]:
        self.paths.append(media_path)
        del interval_seconds
        yield SampledFrame(0, 0.0, 1280, 720, b"frame")


class EmptyDetector:
    backend_identity = "empty"
    model_identity = "none"

    def detect(self, frame: SampledFrame, prompts: tuple[str, ...]) -> tuple[object, ...]:
        del frame, prompts
        return ()


def event_dir(tmp_path: Path, *, phone_media: bool) -> Path:
    event_dir = tmp_path / "events" / EVENT_ID
    event_dir.mkdir(parents=True)
    network = event_dir / "event.mp4"
    network.write_bytes(b"network media")
    manifest: dict[str, object] = {
        "event_id": EVENT_ID,
        "media": {"filename": "event.mp4", "sha256": digest(network)},
    }
    if phone_media:
        phone = event_dir / "phone_media" / "authoritative.mp4"
        phone.parent.mkdir()
        phone.write_bytes(b"phone media")
        manifest["phone_local"] = {
            "validated": True,
            "path": "phone_media/authoritative.mp4",
            "sha256": digest(phone),
        }
        manifest["authoritative_media"] = {
            "source": "phone_local",
            "path": "phone_media/authoritative.mp4",
            "sha256": digest(phone),
        }
    (event_dir / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
    return event_dir


def write_perception(event_dir: Path, media: Path) -> None:
    resolved = resolve_event_media(event_dir)
    (event_dir / "event_perception.json").write_text(
        json.dumps(
            {
                "event_id": EVENT_ID,
                "media": {
                    "filename": media.relative_to(event_dir).as_posix(),
                    "sha256": digest(media),
                    "source": resolved.source if media == resolved.path else "network_capture",
                },
                "observations": [],
            }
        ),
        encoding="utf-8",
    )


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


@pytest.mark.unit
def test_resolver_prefers_validated_phone_media_without_mutating_either_file(
    tmp_path: Path,
) -> None:
    directory = event_dir(tmp_path, phone_media=True)
    network = directory / "event.mp4"
    phone = directory / "phone_media" / "authoritative.mp4"
    before = (network.read_bytes(), phone.read_bytes())

    media = resolve_event_media(directory)

    assert (media.path, media.relative_path, media.source, media.sha256) == (
        phone,
        "phone_media/authoritative.mp4",
        "phone_local",
        digest(phone),
    )
    assert (network.read_bytes(), phone.read_bytes()) == before


@pytest.mark.unit
def test_resolver_keeps_legacy_network_fallback_and_sha_only_artifacts_usable(
    tmp_path: Path,
) -> None:
    directory = event_dir(tmp_path, phone_media=False)
    media = resolve_event_media(directory)

    assert media.path == directory / "event.mp4"
    assert media.source == "network_capture"
    assert artifact_media_matches(media, ArtifactMediaProvenance(media.sha256))


@pytest.mark.unit
def test_perception_and_body_sampling_use_the_same_authoritative_phone_media(
    tmp_path: Path,
) -> None:
    directory = event_dir(tmp_path, phone_media=True)
    expected = directory / "phone_media" / "authoritative.mp4"
    perception_sampler = RecordingSampler()
    body_sampler = RecordingSampler()

    result = EventPerceptionProcessor(perception_sampler, EmptyDetector()).process(
        directory, prompts=("person",)
    )
    process_body(directory, body_sampler, EmptyBodyProvider(), interval=0.2)
    perception = json.loads(result.output_path.read_text(encoding="utf-8"))
    body = json.loads((directory / "event_body_perception.json").read_text(encoding="utf-8"))

    assert perception_sampler.paths == [expected]
    assert body_sampler.paths == [expected]
    assert perception["media"]["source"] == "phone_local"
    assert body["source_media"] == {
        "filename": "phone_media/authoritative.mp4",
        "sha256": digest(expected),
        "source": "phone_local",
    }
    verify_body_media(
        load_body_artifact(directory / "event_body_perception.json", event_id=EVENT_ID),
        resolve_event_media(directory),
    )


@pytest.mark.unit
def test_stale_perception_and_body_artifacts_are_rejected_for_selected_phone_media(
    tmp_path: Path,
) -> None:
    directory = event_dir(tmp_path, phone_media=True)
    network = directory / "event.mp4"
    write_perception(directory, network)
    body_path, *_ = process_body(directory, RecordingSampler(), EmptyBodyProvider(), interval=0.2)
    body_payload = json.loads(body_path.read_text(encoding="utf-8"))
    body_payload["source_media_sha256"] = digest(network)
    body_payload["source_media"]["filename"] = "event.mp4"
    body_payload["source_media"]["sha256"] = digest(network)
    body_payload["source_media"]["source"] = "network_capture"
    body_path.write_text(json.dumps(body_payload), encoding="utf-8")
    selected = resolve_event_media(directory)

    with pytest.raises(PerceptionArtifactError, match="stale"):
        load_perception(directory / "event_perception.json", resolved_media=selected)
    with pytest.raises(ArtifactProvenanceError, match="stale"):
        verify_body_media(load_body_artifact(body_path, event_id=EVENT_ID), selected)


@pytest.mark.unit
def test_editor_and_overlay_entry_points_select_authoritative_phone_media(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    directory = event_dir(tmp_path, phone_media=True)
    phone = directory / "phone_media" / "authoritative.mp4"
    write_perception(directory, phone)
    editor_paths: list[Path] = []
    render_paths: list[Path] = []
    monkeypatch.setattr(
        "foresight_device.visualization.editor_main.launch_editor",
        lambda _controller, media_path, **_kwargs: editor_paths.append(media_path),
    )

    class Renderer:
        def __init__(self, **_kwargs: object) -> None:
            pass

        def render(self, media_path: Path, _output: Path, _timeline: object) -> None:
            render_paths.append(media_path)

    monkeypatch.setattr(render_main, "FfmpegOverlayRenderer", Renderer)
    monkeypatch.setattr(
        sys, "argv", ["editor", "--event-id", EVENT_ID, "--data-root", str(tmp_path)]
    )
    assert editor_main() == 0
    monkeypatch.setattr(
        sys, "argv", ["render", "--event-id", EVENT_ID, "--data-root", str(tmp_path)]
    )
    assert render_main.main() == 0

    assert editor_paths == [phone]
    assert render_paths == [phone]
