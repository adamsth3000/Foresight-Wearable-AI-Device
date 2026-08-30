"""Central offline resolution and provenance for one promoted event's media."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from pathlib import Path

from foresight_device.capture.phone_media import resolve_authoritative_event_media


class EventMediaResolutionError(RuntimeError):
    """The selected event media is missing or cannot be safely described."""


class ArtifactMediaStaleError(RuntimeError):
    """A derived artifact was produced from different event media."""


@dataclass(frozen=True, slots=True)
class ResolvedEventMedia:
    """The sole media choice for one offline operation on one event."""

    event_id: str
    path: Path
    relative_path: str
    sha256: str
    source: str


@dataclass(frozen=True, slots=True)
class ArtifactMediaProvenance:
    """Media provenance retained by a derived offline artifact.

    ``relative_path`` and ``source`` are optional so historical artifacts that contain only a
    matching SHA-256 for legacy ``event.mp4`` remain usable.
    """

    sha256: str
    relative_path: str | None = None
    source: str | None = None


def resolve_event_media(event_dir: Path) -> ResolvedEventMedia:
    """Delegate authoritative selection once and expose safe event-relative provenance."""

    event_id = event_dir.name
    try:
        path = resolve_authoritative_event_media(event_dir.parent, event_id)
    except ValueError as exc:
        raise EventMediaResolutionError(f"event media could not be resolved: {exc}") from exc
    if not path.is_file():
        raise EventMediaResolutionError(f"event media was not found: {path}")
    try:
        relative_path = path.resolve().relative_to(event_dir.resolve()).as_posix()
    except ValueError as exc:
        raise EventMediaResolutionError(
            "resolved event media is outside its event directory"
        ) from exc
    source = (
        "phone_local" if relative_path == "phone_media/authoritative.mp4" else "network_capture"
    )
    if source == "network_capture" and relative_path != "event.mp4":
        raise EventMediaResolutionError("resolved event media has an unsupported relative path")
    return ResolvedEventMedia(event_id, path, relative_path, sha256(path), source)


def artifact_media_matches(
    resolved_media: ResolvedEventMedia, artifact_provenance: ArtifactMediaProvenance
) -> bool:
    """Return whether an artifact is safe to combine with the resolved media.

    SHA-256 is always required. Newer artifacts additionally bind the event-relative path and
    source; absent optional fields denote a legacy artifact and retain SHA-only compatibility.
    """

    return (
        resolved_media.sha256 == artifact_provenance.sha256
        and (
            artifact_provenance.relative_path is None
            or resolved_media.relative_path == artifact_provenance.relative_path
        )
        and (
            artifact_provenance.source is None
            or resolved_media.source == artifact_provenance.source
        )
    )


def require_artifact_media_match(
    resolved_media: ResolvedEventMedia, artifact_provenance: ArtifactMediaProvenance
) -> None:
    """Raise a clear error instead of mixing evidence from different media sources."""

    if not artifact_media_matches(resolved_media, artifact_provenance):
        raise ArtifactMediaStaleError(
            "derived artifact media provenance is stale for the selected "
            f"{resolved_media.source} media; rerun offline processing"
        )


def sha256(path: Path) -> str:
    """Hash a selected local media file without relying on manifest claims."""

    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()
