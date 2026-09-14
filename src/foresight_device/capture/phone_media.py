"""Private staged ingest and USB recovery for verified Android-local event media."""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, BinaryIO
from uuid import UUID

from foresight_device.core.logging import get_logger

LOGGER = get_logger(__name__)
_SHA256 = re.compile(r"^[0-9a-f]{64}$")


class PhoneMediaIngestError(ValueError):
    """A phone-media upload failed validation before authoritative promotion."""

    def __init__(self, message: str, status_code: int = 400) -> None:
        super().__init__(message)
        self.status_code = status_code


@dataclass(frozen=True, slots=True)
class PhoneMediaUploadMetadata:
    event_id: str
    source_session_id: str
    recording_id: str
    byte_size: int
    sha256: str
    observed_start_utc: str
    observed_end_utc: str
    start_offset_ms: int
    end_offset_ms: int
    output_duration_ms: int
    audio_present: bool
    event_origin: str = "laptop_control"
    event_authority: str = "LAPTOP"
    termination_reason: str | None = None
    capture_generation: int | None = None
    source_recording_sha256: str | None = None


@dataclass(frozen=True, slots=True)
class PhoneMediaIngestResult:
    event_id: str
    state: str
    path: Path
    sha256: str
    byte_size: int
    idempotent: bool = False


@dataclass(frozen=True, slots=True)
class PhoneMediaUsbRecoveryMetadata:
    """Phone-ledger provenance required to validate an offline USB recovery."""

    recording_id: str
    source_recording_sha256: str
    output_byte_size: int
    output_sha256: str
    requested_start_offset_ms: int
    requested_end_offset_ms: int
    actual_start_offset_ms: int
    actual_end_offset_ms: int
    output_duration_ms: int
    audio_present: bool
    source_session_id: str | None = None
    capture_generation: int | None = None

    @classmethod
    def from_json(cls, payload: Mapping[str, Any]) -> PhoneMediaUsbRecoveryMetadata:
        """Read explicitly exported phone provenance without inventing missing fields."""

        source_sha256 = _recovery_string(payload, "source_recording_sha256").lower()
        output_sha256 = _recovery_string(payload, "output_sha256").lower()
        if not _SHA256.fullmatch(source_sha256) or not _SHA256.fullmatch(output_sha256):
            raise PhoneMediaIngestError("USB recovery SHA-256 values must be lowercase hexadecimal")
        requested_start = _recovery_integer(payload, "requested_start_offset_ms", minimum=0)
        requested_end = _recovery_integer(
            payload, "requested_end_offset_ms", minimum=requested_start
        )
        actual_start = _recovery_integer(payload, "actual_start_offset_ms", minimum=0)
        actual_end = _recovery_integer(payload, "actual_end_offset_ms", minimum=actual_start)
        capture_generation = payload.get("capture_generation")
        if capture_generation is not None and (
            isinstance(capture_generation, bool)
            or not isinstance(capture_generation, int)
            or capture_generation < 0
        ):
            raise PhoneMediaIngestError(
                "USB recovery capture_generation must be a non-negative integer"
            )
        source_session_id = payload.get("source_session_id")
        if source_session_id is not None and (
            not isinstance(source_session_id, str) or not source_session_id.strip()
        ):
            raise PhoneMediaIngestError("USB recovery source_session_id must be a non-empty string")
        audio_present = payload.get("audio_present")
        if not isinstance(audio_present, bool):
            raise PhoneMediaIngestError("USB recovery audio_present must be a boolean")
        return cls(
            recording_id=_recovery_string(payload, "recording_id"),
            source_recording_sha256=source_sha256,
            output_byte_size=_recovery_integer(payload, "output_byte_size", minimum=1),
            output_sha256=output_sha256,
            requested_start_offset_ms=requested_start,
            requested_end_offset_ms=requested_end,
            actual_start_offset_ms=actual_start,
            actual_end_offset_ms=actual_end,
            output_duration_ms=_recovery_integer(payload, "output_duration_ms", minimum=1),
            audio_present=audio_present,
            source_session_id=(
                source_session_id.strip() if isinstance(source_session_id, str) else None
            ),
            capture_generation=capture_generation,
        )


ProbeMedia = Callable[[Path], Mapping[str, Any]]


class PhoneMediaIngestService:
    """Accept one verified phone MP4 into a laptop event directory.

    The upload is a raw ``application/octet-stream`` request. Metadata is deliberately kept in
    explicit headers so both peers can stream the media rather than buffering a multipart body.
    """

    def __init__(
        self,
        events_root: Path,
        *,
        max_media_bytes: int = 512 * 1024 * 1024,
        ffprobe_executable: str = "ffprobe",
        probe_media: ProbeMedia | None = None,
    ) -> None:
        if max_media_bytes <= 0:
            raise ValueError("max_media_bytes must be positive")
        self._events_root = events_root
        self._max_media_bytes = max_media_bytes
        self._ffprobe_executable = ffprobe_executable
        self._probe_media = probe_media or self._ffprobe

    def ingest(
        self,
        event_id: str,
        headers: Mapping[str, str],
        body: BinaryIO,
    ) -> PhoneMediaIngestResult:
        metadata = self._parse_metadata(event_id, headers)
        event_dir = self._event_dir(metadata.event_id)
        manifest_path = event_dir / "manifest.json"
        new_phone_field_event = (
            metadata.event_origin == "phone_field" and not manifest_path.is_file()
        )
        if new_phone_field_event:
            if event_dir.exists() and not self._is_empty_field_staging_directory(event_dir):
                raise PhoneMediaIngestError("phone-field event directory is incomplete", 409)
            event_dir.mkdir(parents=True, exist_ok=True)
        elif not manifest_path.is_file():
            raise PhoneMediaIngestError("event does not exist", 404)
        content_length = self._content_length(headers)
        if content_length != metadata.byte_size:
            raise PhoneMediaIngestError("Content-Length does not match X-Foresight-Media-Length")
        media_dir = event_dir / "phone_media"
        final_path = media_dir / "authoritative.mp4"
        metadata_path = media_dir / "metadata.json"
        existing = self._read_existing(metadata_path)
        if existing is not None and str(existing.get("sha256", "")) != metadata.sha256:
            raise PhoneMediaIngestError("phone media already exists with a different SHA-256", 409)

        media_dir.mkdir(parents=True, exist_ok=True)
        partial_path = media_dir / "incoming.partial"
        try:
            actual_size, actual_sha = self._stream_to_partial(
                body,
                partial_path,
                metadata.byte_size,
            )
            if actual_size != metadata.byte_size:
                raise PhoneMediaIngestError(
                    "uploaded body length does not match declared media length"
                )
            if actual_sha != metadata.sha256:
                raise PhoneMediaIngestError("uploaded body SHA-256 does not match declared SHA-256")
            if existing is not None:
                if not final_path.is_file():
                    raise PhoneMediaIngestError(
                        "existing phone-media metadata has no media file", 409
                    )
                LOGGER.info(
                    "phone media upload idempotent event_id=%s sha256=%s",
                    metadata.event_id,
                    actual_sha,
                )
                return PhoneMediaIngestResult(
                    metadata.event_id, "synced", final_path, actual_sha, actual_size, True
                )
            probe = self._probe_media(partial_path)
            self._validate_probe(probe, metadata)
            self._promote(partial_path, final_path)
            persisted = self._metadata_payload(metadata, final_path, probe)
            self._write_json_atomically(metadata_path, persisted)
            if new_phone_field_event:
                self._write_json_atomically(
                    manifest_path,
                    self._phone_field_manifest(metadata, persisted),
                )
            else:
                self._update_manifest(manifest_path, persisted)
            LOGGER.info(
                "phone media promoted event_id=%s sha256=%s bytes=%d",
                metadata.event_id,
                actual_sha,
                actual_size,
            )
            return PhoneMediaIngestResult(
                metadata.event_id, "synced", final_path, actual_sha, actual_size
            )
        finally:
            if partial_path.exists():
                partial_path.unlink()

    def _event_dir(self, event_id: str) -> Path:
        _validate_event_id(event_id)
        candidate = self._events_root / event_id
        # event_id is a UUID, but retain a resolved-root guard as a defensive invariant.
        if candidate.resolve().parent != self._events_root.resolve():
            raise PhoneMediaIngestError("event path escapes the event directory")
        return candidate

    @staticmethod
    def _is_empty_field_staging_directory(event_dir: Path) -> bool:
        """Allow a retry after a rejected upload without accepting arbitrary existing data."""
        entries = list(event_dir.iterdir())
        return not entries or (
            len(entries) == 1
            and entries[0].name == "phone_media"
            and entries[0].is_dir()
            and not any(entries[0].iterdir())
        )

    def _parse_metadata(
        self, event_id: str, headers: Mapping[str, str]
    ) -> PhoneMediaUploadMetadata:
        _validate_event_id(event_id)
        sha256 = _header(headers, "X-Foresight-Media-Sha256").lower()
        if not _SHA256.fullmatch(sha256):
            raise PhoneMediaIngestError(
                "X-Foresight-Media-Sha256 must be lowercase hexadecimal SHA-256"
            )
        start = _header(headers, "X-Foresight-Observed-Start-Utc")
        end = _header(headers, "X-Foresight-Observed-End-Utc")
        _parse_utc(start, "X-Foresight-Observed-Start-Utc")
        _parse_utc(end, "X-Foresight-Observed-End-Utc")
        start_offset = _header_int(headers, "X-Foresight-Start-Offset-Ms", minimum=0)
        end_offset = _header_int(headers, "X-Foresight-End-Offset-Ms", minimum=start_offset)
        event_origin = _optional_header(headers, "X-Foresight-Event-Origin") or "laptop_control"
        if event_origin not in {"laptop_control", "phone_field"}:
            raise PhoneMediaIngestError("X-Foresight-Event-Origin is invalid")
        authority = _optional_header(headers, "X-Foresight-Event-Authority") or "LAPTOP"
        if authority not in {"LAPTOP", "PHONE_FIELD"}:
            raise PhoneMediaIngestError("X-Foresight-Event-Authority is invalid")
        if (event_origin == "phone_field") != (authority == "PHONE_FIELD"):
            raise PhoneMediaIngestError("event origin and authority disagree")
        termination_reason = _optional_header(headers, "X-Foresight-Event-Termination-Reason")
        if (
            termination_reason is not None
            and termination_reason not in {"USER_END", "CAPTURE_STOP"}
        ):
            raise PhoneMediaIngestError("X-Foresight-Event-Termination-Reason is invalid")
        capture_generation = (
            _header_int(headers, "X-Foresight-Capture-Generation", minimum=0)
            if _optional_header(headers, "X-Foresight-Capture-Generation") is not None
            else None
        )
        source_recording_sha256 = _optional_header(headers, "X-Foresight-Source-Recording-Sha256")
        if (
            source_recording_sha256 is not None
            and not _SHA256.fullmatch(source_recording_sha256.lower())
        ):
            raise PhoneMediaIngestError("X-Foresight-Source-Recording-Sha256 must be SHA-256")
        return PhoneMediaUploadMetadata(
            event_id=event_id,
            source_session_id=_header(headers, "X-Foresight-Source-Session-Id"),
            recording_id=_header(headers, "X-Foresight-Recording-Id"),
            byte_size=_header_int(
                headers, "X-Foresight-Media-Length", minimum=1, maximum=self._max_media_bytes
            ),
            sha256=sha256,
            observed_start_utc=start,
            observed_end_utc=end,
            start_offset_ms=start_offset,
            end_offset_ms=end_offset,
            output_duration_ms=_header_int(headers, "X-Foresight-Output-Duration-Ms", minimum=1),
            audio_present=_header(headers, "X-Foresight-Audio-Present").lower() == "true",
            event_origin=event_origin,
            event_authority=authority,
            termination_reason=termination_reason,
            capture_generation=capture_generation,
            source_recording_sha256=source_recording_sha256.lower()
            if source_recording_sha256 is not None
            else None,
        )

    @staticmethod
    def _content_length(headers: Mapping[str, str]) -> int:
        return _header_int(headers, "Content-Length", minimum=1)

    @staticmethod
    def _stream_to_partial(
        body: BinaryIO,
        destination: Path,
        expected_size: int,
    ) -> tuple[int, str]:
        digest = hashlib.sha256()
        count = 0
        with destination.open("wb") as output:
            while count < expected_size:
                chunk = body.read(min(64 * 1024, expected_size - count))
                if not chunk:
                    break
                count += len(chunk)
                digest.update(chunk)
                output.write(chunk)
            output.flush()
            os.fsync(output.fileno())
        return count, digest.hexdigest()

    def _validate_probe(self, probe: Mapping[str, Any], metadata: PhoneMediaUploadMetadata) -> None:
        streams = probe.get("streams")
        format_data = probe.get("format")
        if not isinstance(streams, list) or not isinstance(format_data, Mapping):
            raise PhoneMediaIngestError("ffprobe returned an invalid media description")
        if not any(
            isinstance(stream, Mapping) and stream.get("codec_type") == "video"
            for stream in streams
        ):
            raise PhoneMediaIngestError("uploaded MP4 has no video stream")
        if metadata.audio_present and not any(
            isinstance(stream, Mapping) and stream.get("codec_type") == "audio"
            for stream in streams
        ):
            raise PhoneMediaIngestError("uploaded MP4 is missing declared audio stream")
        try:
            duration_seconds = float(format_data["duration"])
        except (KeyError, TypeError, ValueError) as exc:
            raise PhoneMediaIngestError("uploaded MP4 has no usable duration") from exc
        if duration_seconds <= 0:
            raise PhoneMediaIngestError("uploaded MP4 duration must be positive")
        expected_seconds = metadata.output_duration_ms / 1_000
        tolerance = max(5.0, expected_seconds * 0.25)
        if abs(duration_seconds - expected_seconds) > tolerance:
            raise PhoneMediaIngestError(
                "uploaded MP4 duration is implausible for its extraction metadata"
            )

    def _ffprobe(self, path: Path) -> Mapping[str, Any]:
        try:
            completed = subprocess.run(
                [
                    self._ffprobe_executable,
                    "-v",
                    "error",
                    "-show_entries",
                    "format=format_name,duration:stream=codec_type,codec_name",
                    "-of",
                    "json",
                    str(path),
                ],
                check=True,
                capture_output=True,
                text=True,
                timeout=30,
            )
            decoded = json.loads(completed.stdout)
        except (
            OSError,
            subprocess.CalledProcessError,
            subprocess.TimeoutExpired,
            json.JSONDecodeError,
        ) as exc:
            raise PhoneMediaIngestError("ffprobe could not validate uploaded MP4") from exc
        if not isinstance(decoded, Mapping):
            raise PhoneMediaIngestError("ffprobe returned a non-object response")
        return decoded

    @staticmethod
    def _promote(partial_path: Path, final_path: Path) -> None:
        if final_path.exists():
            raise PhoneMediaIngestError("phone-media target already exists", 409)
        os.replace(partial_path, final_path)

    @staticmethod
    def _read_existing(path: Path) -> Mapping[str, Any] | None:
        if not path.is_file():
            return None
        try:
            decoded = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise PhoneMediaIngestError("existing phone-media metadata is invalid", 409) from exc
        if not isinstance(decoded, Mapping) or not decoded.get("validated"):
            raise PhoneMediaIngestError("existing phone-media metadata is unverified", 409)
        return decoded

    @staticmethod
    def _metadata_payload(
        metadata: PhoneMediaUploadMetadata, final_path: Path, probe: Mapping[str, Any]
    ) -> dict[str, Any]:
        return {
            "schema_version": 1,
            "validated": True,
            "event_id": metadata.event_id,
            "path": f"phone_media/{final_path.name}",
            "sha256": metadata.sha256,
            "byte_size": metadata.byte_size,
            "source_session_id": metadata.source_session_id,
            "recording_id": metadata.recording_id,
            "event_origin": metadata.event_origin,
            "event_authority": metadata.event_authority,
            "termination_reason": metadata.termination_reason,
            "capture_generation": metadata.capture_generation,
            "source_recording_sha256": metadata.source_recording_sha256,
            "extraction": {
                "observed_start_utc": metadata.observed_start_utc,
                "observed_end_utc": metadata.observed_end_utc,
                "start_offset_ms": metadata.start_offset_ms,
                "end_offset_ms": metadata.end_offset_ms,
                "output_duration_ms": metadata.output_duration_ms,
                "audio_present": metadata.audio_present,
            },
            "ffprobe": dict(probe),
            "validated_at_utc": datetime.now(UTC).isoformat(),
        }

    @staticmethod
    def _phone_field_manifest(
        metadata: PhoneMediaUploadMetadata, phone_local: Mapping[str, Any]
    ) -> dict[str, Any]:
        """Create a phone-authoritative event without fabricating network provenance."""
        return {
            "schema_version": 3,
            "event_id": metadata.event_id,
            "event_origin": "phone_field",
            "event_authority": "phone_local",
            "phone_field": {
                "source_session_id": metadata.source_session_id,
                "recording_id": metadata.recording_id,
                "capture_generation": metadata.capture_generation,
                "source_recording_sha256": metadata.source_recording_sha256,
                "observed_start_utc": metadata.observed_start_utc,
                "observed_end_utc": metadata.observed_end_utc,
                "start_offset_ms": metadata.start_offset_ms,
                "end_offset_ms": metadata.end_offset_ms,
                "termination_reason": metadata.termination_reason,
            },
            "phone_local": dict(phone_local),
            "authoritative_media": {
                "source": "phone_local",
                "path": phone_local["path"],
                "sha256": phone_local["sha256"],
            },
        }

    @staticmethod
    def _write_json_atomically(path: Path, payload: Mapping[str, Any]) -> None:
        temporary = path.with_suffix(path.suffix + ".tmp")
        with temporary.open("w", encoding="utf-8") as handle:
            json.dump(payload, handle, indent=2, sort_keys=True)
            handle.write("\n")
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)

    def _update_manifest(self, path: Path, phone_local: Mapping[str, Any]) -> None:
        try:
            manifest = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise PhoneMediaIngestError("event manifest is invalid", 409) from exc
        if not isinstance(manifest, dict):
            raise PhoneMediaIngestError("event manifest is not an object", 409)
        network = manifest.get("media")
        if not isinstance(network, Mapping):
            raise PhoneMediaIngestError("event manifest has no network capture provenance", 409)
        manifest["network_capture"] = {
            "path": str(network.get("filename", "event.mp4")),
            "sha256": network.get("sha256"),
        }
        manifest["phone_local"] = dict(phone_local)
        manifest["authoritative_media"] = {
            "source": "phone_local",
            "path": phone_local["path"],
            "sha256": phone_local["sha256"],
        }
        self._write_json_atomically(path, manifest)


class PhoneMediaUsbRecoveryService:
    """Validate an already-copied phone FIELD MP4 and record USB-only provenance.

    USB recovery deliberately operates on the event's staged authoritative path. It never moves,
    rewrites, or uploads media, and it never represents this offline copy as an R3.3 transfer.
    """

    def __init__(
        self,
        events_root: Path,
        *,
        ffprobe_executable: str = "ffprobe",
        probe_media: ProbeMedia | None = None,
    ) -> None:
        self._events_root = events_root
        self._probe_media = probe_media or PhoneMediaIngestService(
            events_root, ffprobe_executable=ffprobe_executable
        )._ffprobe

    def import_usb(
        self,
        event_id: str,
        media_path: Path,
        metadata: PhoneMediaUsbRecoveryMetadata,
    ) -> PhoneMediaIngestResult:
        """Write validated phone-field provenance for a recovered local MP4."""

        _validate_event_id(event_id)
        event_dir = self._events_root / event_id
        expected_path = event_dir / "phone_media" / "authoritative.mp4"
        if media_path.resolve() != expected_path.resolve():
            raise PhoneMediaIngestError(
                "USB recovery media must already be staged at phone_media/authoritative.mp4"
            )
        if not media_path.is_file():
            raise PhoneMediaIngestError("USB recovery media file does not exist", 404)

        actual_size = media_path.stat().st_size
        actual_sha256 = _sha256_file(media_path)
        if actual_size != metadata.output_byte_size:
            raise PhoneMediaIngestError(
                "USB recovery media byte size does not match phone metadata"
            )
        if actual_sha256 != metadata.output_sha256:
            raise PhoneMediaIngestError("USB recovery media SHA-256 does not match phone metadata")

        probe = self._probe_media(media_path)
        self._validate_probe(probe, metadata)
        phone_local = self._phone_local_payload(event_id, metadata, probe)
        manifest = self._manifest_payload(event_id, metadata, phone_local)
        manifest_path = event_dir / "manifest.json"
        metadata_path = expected_path.parent / "metadata.json"
        if manifest_path.is_file():
            existing = _read_json_object(manifest_path, "existing event manifest")
            if not self._is_idempotent(existing, manifest):
                raise PhoneMediaIngestError(
                    "existing event manifest conflicts with USB recovery", 409
                )
            LOGGER.info("USB recovery idempotent event_id=%s sha256=%s", event_id, actual_sha256)
            return PhoneMediaIngestResult(
                event_id, "recovered", expected_path, actual_sha256, actual_size, True
            )
        if metadata_path.is_file():
            existing_metadata = _read_json_object(metadata_path, "existing phone-media metadata")
            if not self._phone_metadata_matches(existing_metadata, phone_local):
                raise PhoneMediaIngestError(
                    "existing phone-media metadata conflicts with USB recovery", 409
                )

        PhoneMediaIngestService._write_json_atomically(metadata_path, phone_local)
        PhoneMediaIngestService._write_json_atomically(manifest_path, manifest)
        LOGGER.info(
            "USB recovery imported event_id=%s sha256=%s bytes=%d",
            event_id,
            actual_sha256,
            actual_size,
        )
        return PhoneMediaIngestResult(
            event_id, "recovered", expected_path, actual_sha256, actual_size
        )

    @staticmethod
    def _validate_probe(
        probe: Mapping[str, Any], metadata: PhoneMediaUsbRecoveryMetadata
    ) -> None:
        streams = probe.get("streams")
        format_data = probe.get("format")
        if not isinstance(streams, list) or not isinstance(format_data, Mapping):
            raise PhoneMediaIngestError("ffprobe returned an invalid media description")
        has_video = any(
            isinstance(stream, Mapping) and stream.get("codec_type") == "video"
            for stream in streams
        )
        has_audio = any(
            isinstance(stream, Mapping) and stream.get("codec_type") == "audio"
            for stream in streams
        )
        if not has_video:
            raise PhoneMediaIngestError("USB recovery MP4 has no video stream")
        if has_audio != metadata.audio_present:
            raise PhoneMediaIngestError("USB recovery audio presence does not match phone metadata")
        try:
            duration_seconds = float(format_data["duration"])
        except (KeyError, TypeError, ValueError) as exc:
            raise PhoneMediaIngestError("USB recovery MP4 has no usable duration") from exc
        if duration_seconds <= 0:
            raise PhoneMediaIngestError("USB recovery MP4 duration must be positive")
        expected_seconds = metadata.output_duration_ms / 1_000
        tolerance = max(5.0, expected_seconds * 0.25)
        if abs(duration_seconds - expected_seconds) > tolerance:
            raise PhoneMediaIngestError(
                "USB recovery MP4 duration is implausible for its extraction metadata"
            )

    @staticmethod
    def _phone_local_payload(
        event_id: str,
        metadata: PhoneMediaUsbRecoveryMetadata,
        probe: Mapping[str, Any],
    ) -> dict[str, Any]:
        extraction = {
            "requested_start_offset_ms": metadata.requested_start_offset_ms,
            "requested_end_offset_ms": metadata.requested_end_offset_ms,
            "actual_start_offset_ms": metadata.actual_start_offset_ms,
            "actual_end_offset_ms": metadata.actual_end_offset_ms,
            "output_duration_ms": metadata.output_duration_ms,
            "audio_present": metadata.audio_present,
        }
        return {
            "schema_version": 1,
            "validated": True,
            "event_id": event_id,
            "path": "phone_media/authoritative.mp4",
            "sha256": metadata.output_sha256,
            "byte_size": metadata.output_byte_size,
            "recording_id": metadata.recording_id,
            "source_recording_sha256": metadata.source_recording_sha256,
            "source_session_id": metadata.source_session_id,
            "capture_generation": metadata.capture_generation,
            "event_origin": "phone_field",
            "event_authority": "phone_local",
            "extraction": extraction,
            "ffprobe": dict(probe),
            "validated_at_utc": datetime.now(UTC).isoformat(),
        }

    @staticmethod
    def _manifest_payload(
        event_id: str,
        metadata: PhoneMediaUsbRecoveryMetadata,
        phone_local: Mapping[str, Any],
    ) -> dict[str, Any]:
        return {
            "schema_version": 3,
            "event_id": event_id,
            "event_origin": "phone_field",
            "event_authority": "phone_local",
            "phone_field": {
                "recording_id": metadata.recording_id,
                "source_recording_sha256": metadata.source_recording_sha256,
                "source_session_id": metadata.source_session_id,
                "capture_generation": metadata.capture_generation,
                "extraction": dict(phone_local["extraction"]),
            },
            "phone_local": dict(phone_local),
            "authoritative_media": {
                "source": "phone_local",
                "path": "phone_media/authoritative.mp4",
                "sha256": metadata.output_sha256,
            },
            "transfer": {
                "method": "usb_recovery",
                "imported_at_utc": datetime.now(UTC).isoformat(),
            },
        }

    @staticmethod
    def _is_idempotent(existing: Mapping[str, Any], expected: Mapping[str, Any]) -> bool:
        existing_transfer = existing.get("transfer")
        existing_field = existing.get("phone_field")
        existing_phone = existing.get("phone_local")
        existing_authoritative = existing.get("authoritative_media")
        expected_authoritative = expected["authoritative_media"]
        return (
            existing.get("event_id") == expected["event_id"]
            and existing.get("event_origin") == "phone_field"
            and existing.get("event_authority") == "phone_local"
            and isinstance(existing_transfer, Mapping)
            and existing_transfer.get("method") == "usb_recovery"
            and existing_field == expected["phone_field"]
            and isinstance(existing_phone, Mapping)
            and PhoneMediaUsbRecoveryService._phone_metadata_matches(
                existing_phone, expected["phone_local"]
            )
            and isinstance(existing_authoritative, Mapping)
            and existing_authoritative == expected_authoritative
        )

    @staticmethod
    def _phone_metadata_matches(existing: Mapping[str, Any], expected: Mapping[str, Any]) -> bool:
        keys = (
            "validated",
            "event_id",
            "path",
            "sha256",
            "byte_size",
            "recording_id",
            "source_recording_sha256",
            "source_session_id",
            "capture_generation",
            "event_origin",
            "event_authority",
            "extraction",
            "ffprobe",
        )
        return all(existing.get(key) == expected.get(key) for key in keys)


def resolve_authoritative_event_media(events_root: Path, event_id: str) -> Path:
    """Return validated phone-local media when present, otherwise the legacy network event."""

    _validate_event_id(event_id)
    event_dir = events_root / event_id
    manifest_path = event_dir / "manifest.json"
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return event_dir / "event.mp4"
    authoritative = manifest.get("authoritative_media") if isinstance(manifest, Mapping) else None
    phone = manifest.get("phone_local") if isinstance(manifest, Mapping) else None
    if (
        isinstance(authoritative, Mapping)
        and authoritative.get("source") == "phone_local"
        and isinstance(phone, Mapping)
        and phone.get("validated") is True
        and authoritative.get("sha256") == phone.get("sha256")
        and authoritative.get("path") == phone.get("path")
    ):
        candidate = event_dir / str(authoritative["path"])
        if (
            candidate.resolve().parent == (event_dir / "phone_media").resolve()
            and candidate.is_file()
        ):
            return candidate
    return event_dir / "event.mp4"


def _validate_event_id(event_id: str) -> None:
    try:
        UUID(event_id)
    except (ValueError, AttributeError) as exc:
        raise PhoneMediaIngestError("event ID must be a UUID") from exc


def _header(headers: Mapping[str, str], name: str) -> str:
    value = next(
        (candidate for key, candidate in headers.items() if key.lower() == name.lower()),
        None,
    )
    if not isinstance(value, str) or not value.strip():
        raise PhoneMediaIngestError(f"missing required header {name}")
    return value.strip()


def _optional_header(headers: Mapping[str, str], name: str) -> str | None:
    value = next(
        (candidate for key, candidate in headers.items() if key.lower() == name.lower()),
        None,
    )
    if value is None:
        return None
    if not isinstance(value, str) or not value.strip():
        raise PhoneMediaIngestError(f"invalid header {name}")
    return value.strip()


def _header_int(
    headers: Mapping[str, str], name: str, *, minimum: int, maximum: int | None = None
) -> int:
    try:
        value = int(_header(headers, name))
    except ValueError as exc:
        raise PhoneMediaIngestError(f"{name} must be an integer") from exc
    if value < minimum or (maximum is not None and value > maximum):
        raise PhoneMediaIngestError(f"{name} is outside the allowed range")
    return value


def _parse_utc(value: str, header_name: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise PhoneMediaIngestError(f"{header_name} must be an ISO-8601 UTC timestamp") from exc
    if parsed.tzinfo is None:
        raise PhoneMediaIngestError(f"{header_name} must include an offset")
    return parsed.astimezone(UTC)


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        while chunk := handle.read(64 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def _read_json_object(path: Path, description: str) -> Mapping[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PhoneMediaIngestError(f"{description} is invalid", 409) from exc
    if not isinstance(value, Mapping):
        raise PhoneMediaIngestError(f"{description} is not an object", 409)
    return value


def _recovery_string(payload: Mapping[str, Any], name: str) -> str:
    value = payload.get(name)
    if not isinstance(value, str) or not value.strip():
        raise PhoneMediaIngestError(f"USB recovery {name} must be a non-empty string")
    return value.strip()


def _recovery_integer(payload: Mapping[str, Any], name: str, *, minimum: int) -> int:
    value = payload.get(name)
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise PhoneMediaIngestError(f"USB recovery {name} must be an integer >= {minimum}")
    return value


__all__ = [
    "PhoneMediaIngestError",
    "PhoneMediaIngestResult",
    "PhoneMediaIngestService",
    "PhoneMediaUploadMetadata",
    "PhoneMediaUsbRecoveryMetadata",
    "PhoneMediaUsbRecoveryService",
    "resolve_authoritative_event_media",
]
