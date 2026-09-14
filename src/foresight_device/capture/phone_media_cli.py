"""Command-line import for a manually USB-recovered phone FIELD event."""

from __future__ import annotations

import argparse
import json
from collections.abc import Mapping
from pathlib import Path
from typing import Any

from .phone_media import (
    PhoneMediaIngestError,
    PhoneMediaUsbRecoveryMetadata,
    PhoneMediaUsbRecoveryService,
)


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate an offline USB-recovered phone FIELD event."
    )
    subcommands = parser.add_subparsers(dest="command", required=True)
    import_usb = subcommands.add_parser(
        "import-usb", help="Write validated USB-recovery provenance for staged authoritative.mp4."
    )
    import_usb.add_argument("--event-id", required=True)
    import_usb.add_argument("--data-root", type=Path, default=Path("data/capture"))
    import_usb.add_argument(
        "--media-path",
        type=Path,
        help="Must be the staged events/<event_id>/phone_media/authoritative.mp4 path.",
    )
    import_usb.add_argument(
        "--metadata-json",
        type=Path,
        required=True,
        help="JSON export containing the phone-ledger fields required for USB validation.",
    )
    import_usb.add_argument("--ffprobe", default="ffprobe")
    options = parser.parse_args()
    if options.command != "import-usb":  # pragma: no cover - argparse enforces the only command.
        parser.error("an import command is required")
    media_path = options.media_path or (
        options.data_root / "events" / options.event_id / "phone_media" / "authoritative.mp4"
    )
    try:
        metadata = PhoneMediaUsbRecoveryMetadata.from_json(
            _read_metadata_json(options.metadata_json)
        )
        result = PhoneMediaUsbRecoveryService(
            options.data_root / "events", ffprobe_executable=options.ffprobe
        ).import_usb(options.event_id, media_path, metadata)
    except PhoneMediaIngestError as exc:
        parser.error(str(exc))
    print(
        f"USB recovery {'already validated' if result.idempotent else 'complete'}: "
        f"event_id={result.event_id} path={result.path} sha256={result.sha256} "
        f"bytes={result.byte_size}"
    )
    return 0


def _read_metadata_json(path: Path) -> Mapping[str, Any]:
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise PhoneMediaIngestError("USB recovery metadata is invalid") from exc
    if not isinstance(payload, Mapping):
        raise PhoneMediaIngestError("USB recovery metadata must be a JSON object")
    return payload


if __name__ == "__main__":
    raise SystemExit(main())
