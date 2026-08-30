from __future__ import annotations

import hashlib
import io
import json
from datetime import UTC, datetime, timedelta
from pathlib import Path
from uuid import uuid4

import pytest

from foresight_device.capture.phone_media import (
    PhoneMediaIngestError,
    PhoneMediaIngestService,
    resolve_authoritative_event_media,
)


def test_valid_streamed_upload_promotes_phone_media_and_preserves_network_event(
    tmp_path: Path,
) -> None:
    event_id, events_root, event_dir = _event(tmp_path)
    body = b"phone-local-mp4"
    service = _service(events_root)

    result = service.ingest(event_id, _headers(body), io.BytesIO(body))

    assert result.state == "synced"
    assert result.path.read_bytes() == body
    assert (event_dir / "event.mp4").read_bytes() == b"network-event"
    manifest = json.loads((event_dir / "manifest.json").read_text())
    assert manifest["network_capture"]["path"] == "event.mp4"
    assert manifest["phone_local"]["validated"] is True
    assert manifest["authoritative_media"]["source"] == "phone_local"
    assert resolve_authoritative_event_media(events_root, event_id) == result.path


@pytest.mark.parametrize("event_id", ["not-a-uuid", "../" + str(uuid4())])
def test_rejects_invalid_event_id(tmp_path: Path, event_id: str) -> None:
    body = b"x"
    with pytest.raises(PhoneMediaIngestError, match="event ID"):
        _service(tmp_path / "events").ingest(event_id, _headers(body), io.BytesIO(body))


def test_rejects_nonexistent_event(tmp_path: Path) -> None:
    body = b"x"
    with pytest.raises(PhoneMediaIngestError, match="does not exist"):
        _service(tmp_path / "events").ingest(str(uuid4()), _headers(body), io.BytesIO(body))


@pytest.mark.parametrize("body", [b"", b"short"])
def test_rejects_zero_or_truncated_body_without_promotion(tmp_path: Path, body: bytes) -> None:
    event_id, events_root, event_dir = _event(tmp_path)
    headers = _headers(b"declared-media")
    with pytest.raises(PhoneMediaIngestError, match="length"):
        _service(events_root).ingest(event_id, headers, io.BytesIO(body))
    assert not (event_dir / "phone_media" / "authoritative.mp4").exists()
    assert not (event_dir / "phone_media" / "incoming.partial").exists()


def test_rejects_size_sha_and_probe_failures(tmp_path: Path) -> None:
    event_id, events_root, event_dir = _event(tmp_path)
    body = b"real-media"
    bad_length = _headers(body) | {"Content-Length": str(len(body) + 1)}
    with pytest.raises(PhoneMediaIngestError, match="Content-Length"):
        _service(events_root).ingest(event_id, bad_length, io.BytesIO(body))
    bad_sha = _headers(body) | {"X-Foresight-Media-Sha256": "0" * 64}
    with pytest.raises(PhoneMediaIngestError, match="SHA-256"):
        _service(events_root).ingest(event_id, bad_sha, io.BytesIO(body))
    with pytest.raises(PhoneMediaIngestError, match="ffprobe"):
        PhoneMediaIngestService(events_root, probe_media=lambda _path: {}).ingest(
            event_id, _headers(body), io.BytesIO(body)
        )
    assert not (event_dir / "phone_media" / "authoritative.mp4").exists()


def test_rejects_oversize_duplicate_conflict_and_keeps_authoritative_file(tmp_path: Path) -> None:
    event_id, events_root, event_dir = _event(tmp_path)
    body = b"first"
    service = _service(events_root)
    first = service.ingest(event_id, _headers(body), io.BytesIO(body))
    duplicate = service.ingest(event_id, _headers(body), io.BytesIO(body))
    assert duplicate.idempotent is True
    with pytest.raises(PhoneMediaIngestError) as conflict:
        service.ingest(event_id, _headers(b"second"), io.BytesIO(b"second"))
    assert conflict.value.status_code == 409
    assert first.path.read_bytes() == body
    small = PhoneMediaIngestService(events_root, max_media_bytes=2, probe_media=_probe)
    other_id, _, _ = _event(tmp_path)
    with pytest.raises(PhoneMediaIngestError, match="allowed range"):
        small.ingest(other_id, _headers(body), io.BytesIO(body))
    assert (event_dir / "event.mp4").read_bytes() == b"network-event"


def test_authoritative_resolver_falls_back_for_legacy_or_invalid_phone_provenance(
    tmp_path: Path,
) -> None:
    event_id, events_root, event_dir = _event(tmp_path)
    assert resolve_authoritative_event_media(events_root, event_id) == event_dir / "event.mp4"
    manifest = json.loads((event_dir / "manifest.json").read_text())
    manifest["authoritative_media"] = {
        "source": "phone_local",
        "path": "phone_media/authoritative.mp4",
        "sha256": "x",
    }
    manifest["phone_local"] = {
        "validated": False,
        "path": "phone_media/authoritative.mp4",
        "sha256": "x",
    }
    (event_dir / "manifest.json").write_text(json.dumps(manifest))
    assert resolve_authoritative_event_media(events_root, event_id) == event_dir / "event.mp4"


def _event(root: Path) -> tuple[str, Path, Path]:
    event_id = str(uuid4())
    events_root = root / "events"
    event_dir = events_root / event_id
    event_dir.mkdir(parents=True)
    (event_dir / "event.mp4").write_bytes(b"network-event")
    (event_dir / "manifest.json").write_text(
        json.dumps(
            {"event_id": event_id, "media": {"filename": "event.mp4", "sha256": "network-sha"}}
        )
    )
    return event_id, events_root, event_dir


def _headers(body: bytes) -> dict[str, str]:
    now = datetime(2026, 1, 1, tzinfo=UTC)
    return {
        "Content-Length": str(len(body)),
        "X-Foresight-Source-Session-Id": "phone-session",
        "X-Foresight-Recording-Id": "recording-1",
        "X-Foresight-Media-Length": str(len(body)),
        "X-Foresight-Media-Sha256": hashlib.sha256(body).hexdigest(),
        "X-Foresight-Observed-Start-Utc": now.isoformat(),
        "X-Foresight-Observed-End-Utc": (now + timedelta(seconds=2)).isoformat(),
        "X-Foresight-Start-Offset-Ms": "0",
        "X-Foresight-End-Offset-Ms": "2000",
        "X-Foresight-Output-Duration-Ms": "2000",
        "X-Foresight-Audio-Present": "true",
    }


def _probe(_path: Path) -> dict[str, object]:
    return {
        "format": {"format_name": "mov,mp4,m4a,3gp,3g2,mj2", "duration": "2.0"},
        "streams": [{"codec_type": "video"}, {"codec_type": "audio"}],
    }


def _service(events_root: Path) -> PhoneMediaIngestService:
    return PhoneMediaIngestService(events_root, probe_media=_probe)
