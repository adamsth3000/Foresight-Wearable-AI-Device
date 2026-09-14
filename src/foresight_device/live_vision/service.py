"""Small local HTTP boundary around the existing source-neutral detector adapter."""

from __future__ import annotations

import json
import logging
from collections.abc import Sequence
from dataclasses import dataclass
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from io import BytesIO
from typing import Any

from foresight_device.perception.__main__ import DEFAULT_PROMPTS
from foresight_device.perception.detector import Detector
from foresight_device.perception.grounding_dino import GroundingDinoDetector
from foresight_device.perception.models import SampledFrame

LOGGER = logging.getLogger(__name__)
LIVE_VISION_SCHEMA_VERSION = 1
LIVE_VISION_PATH = "/v1/detect"
MAX_JPEG_BYTES = 8 * 1024 * 1024


class LiveVisionRequestError(ValueError):
    """A request cannot safely become a detector input."""


@dataclass(frozen=True, slots=True)
class LiveVisionRequest:
    runtime_generation: int
    frame_id: int
    capture_elapsed_realtime_nanos: int
    source_width: int
    source_height: int
    jpeg_bytes: bytes

    def __post_init__(self) -> None:
        if self.runtime_generation <= 0 or self.frame_id <= 0:
            raise LiveVisionRequestError("runtime generation and frame ID must be positive")
        if self.capture_elapsed_realtime_nanos < 0:
            raise LiveVisionRequestError("capture timestamp must be monotonic")
        if self.source_width <= 0 or self.source_height <= 0:
            raise LiveVisionRequestError("source dimensions must be positive")
        if not self.jpeg_bytes or len(self.jpeg_bytes) > MAX_JPEG_BYTES:
            raise LiveVisionRequestError("JPEG payload is missing or exceeds the local service limit")


class LiveVisionService:
    """Runs newest submitted stills through a detector without event-pipeline coupling."""

    def __init__(self, detector: Detector, prompts: Sequence[str] = DEFAULT_PROMPTS) -> None:
        self._detector = detector
        self._prompts = tuple(prompts)

    def detect(self, request: LiveVisionRequest) -> dict[str, object]:
        frame = _decode_jpeg(request)
        detections = self._detector.detect(frame, self._prompts)
        return {
            "schema_version": LIVE_VISION_SCHEMA_VERSION,
            "runtime_generation": request.runtime_generation,
            "frame_id": request.frame_id,
            "capture_elapsed_realtime_nanos": request.capture_elapsed_realtime_nanos,
            "source": {"width": frame.width, "height": frame.height},
            "detector": {
                "backend": self._detector.backend_identity,
                "model": self._detector.model_identity,
            },
            "detections": [
                {
                    "label": item.label,
                    "confidence": item.confidence,
                    "bounding_box": {
                        "x_min": item.bounding_box.x_min,
                        "y_min": item.bounding_box.y_min,
                        "x_max": item.bounding_box.x_max,
                        "y_max": item.bounding_box.y_max,
                    },
                    "prompt": item.prompt,
                }
                for item in detections
            ],
        }


def serve(*, host: str, port: int, detector: Detector | None = None) -> None:
    """Serve the explicit local-only protocol; detector weights load lazily on the first frame."""

    service = LiveVisionService(detector or GroundingDinoDetector())

    class Handler(_LiveVisionRequestHandler):
        vision_service = service

    server = ThreadingHTTPServer((host, port), Handler)
    LOGGER.info("Live Vision service listening on http://%s:%d%s", host, port, LIVE_VISION_PATH)
    try:
        server.serve_forever()
    finally:
        server.server_close()


class _LiveVisionRequestHandler(BaseHTTPRequestHandler):
    vision_service: LiveVisionService

    def do_POST(self) -> None:  # noqa: N802 - required BaseHTTPRequestHandler API
        if self.path != LIVE_VISION_PATH:
            self._write_json(HTTPStatus.NOT_FOUND, {"error": "unknown live Vision endpoint"})
            return
        try:
            request = _request_from_headers(self.headers, self.rfile.read(_content_length(self.headers)))
            self._write_json(HTTPStatus.OK, self.vision_service.detect(request))
        except LiveVisionRequestError as error:
            self._write_json(HTTPStatus.BAD_REQUEST, {"error": str(error)})
        except Exception as error:  # Keep detector failures at the isolated local boundary.
            LOGGER.exception("Live Vision detector request failed")
            self._write_json(HTTPStatus.INTERNAL_SERVER_ERROR, {"error": type(error).__name__})

    def log_message(self, format: str, *args: object) -> None:
        LOGGER.info("Live Vision HTTP: " + format, *args)

    def _write_json(self, status: HTTPStatus, payload: dict[str, object]) -> None:
        body = json.dumps(payload, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def _content_length(headers: Any) -> int:
    try:
        length = int(headers.get("Content-Length", "0"))
    except ValueError as exc:
        raise LiveVisionRequestError("Content-Length must be an integer") from exc
    if not 0 < length <= MAX_JPEG_BYTES:
        raise LiveVisionRequestError("Content-Length must identify a bounded JPEG payload")
    return length


def _request_from_headers(headers: Any, jpeg_bytes: bytes) -> LiveVisionRequest:
    try:
        version = int(headers["X-Foresight-Vision-Schema-Version"])
        request = LiveVisionRequest(
            runtime_generation=int(headers["X-Foresight-Vision-Runtime-Generation"]),
            frame_id=int(headers["X-Foresight-Vision-Frame-Id"]),
            capture_elapsed_realtime_nanos=int(
                headers["X-Foresight-Vision-Capture-Elapsed-Realtime-Nanos"]
            ),
            source_width=int(headers["X-Foresight-Vision-Source-Width"]),
            source_height=int(headers["X-Foresight-Vision-Source-Height"]),
            jpeg_bytes=jpeg_bytes,
        )
    except (KeyError, TypeError, ValueError) as exc:
        raise LiveVisionRequestError("required Foresight Vision headers are invalid") from exc
    if version != LIVE_VISION_SCHEMA_VERSION:
        raise LiveVisionRequestError(f"unsupported schema version: {version}")
    return request


def _decode_jpeg(request: LiveVisionRequest) -> SampledFrame:
    try:
        image_module = __import__("PIL.Image", fromlist=["open"])
        image = image_module.open(BytesIO(request.jpeg_bytes)).convert("RGB")
    except Exception as exc:
        raise LiveVisionRequestError("request body is not a decodable JPEG image") from exc
    if image.width != request.source_width or image.height != request.source_height:
        raise LiveVisionRequestError("declared source dimensions do not match the JPEG image")
    png = BytesIO()
    image.save(png, format="PNG")
    return SampledFrame(
        frame_index=request.frame_id,
        media_timestamp_seconds=request.capture_elapsed_realtime_nanos / 1_000_000_000,
        width=image.width,
        height=image.height,
        png_bytes=png.getvalue(),
    )
