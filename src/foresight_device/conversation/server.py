"""Minimal standard-library HTTP host for the private-network MVP backend."""

from __future__ import annotations

import json
import logging
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, TimeoutError
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Type

from foresight_device.conversation.models import ConversationRequest, ConversationRequestError
from foresight_device.conversation.service import ConversationService, GeminiUpstreamError

LOGGER = logging.getLogger("foresight.conversation")


def handler_for(
    service: ConversationService,
    upstream_timeout_seconds: float = 25.0,
) -> Type[BaseHTTPRequestHandler]:
    class ConversationHandler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:  # noqa: N802 - stdlib handler naming
            request_id = uuid.uuid4().hex[:12]
            started = time.monotonic()
            if self.path != "/v1/conversation":
                self._send(HTTPStatus.NOT_FOUND, {"schema_version": 1, "error": {"type": "not_found"}})
                return
            try:
                content_length = int(self.headers.get("Content-Length", "0"))
                if content_length <= 0 or content_length > 32_000:
                    raise ConversationRequestError("invalid request size")
                raw = self.rfile.read(content_length)
                request = ConversationRequest.from_mapping(json.loads(raw.decode("utf-8")))
                executor = ThreadPoolExecutor(max_workers=1)
                try:
                    response = executor.submit(service.respond, request).result(timeout=upstream_timeout_seconds)
                finally:
                    executor.shutdown(wait=False, cancel_futures=True)
            except (UnicodeDecodeError, json.JSONDecodeError, ConversationRequestError):
                self._send(HTTPStatus.BAD_REQUEST, {"schema_version": 1, "error": {"type": "invalid_request"}})
                LOGGER.info("conversation request_id=%s model=%s status=400", request_id, "none")
                return
            except GeminiUpstreamError:
                self._send(HTTPStatus.SERVICE_UNAVAILABLE, {"schema_version": 1, "error": {"type": "upstream_unavailable"}})
                LOGGER.warning("conversation request_id=%s model=%s status=503", request_id, "gemini-3.8-flash")
                return
            except TimeoutError:
                self._send(HTTPStatus.GATEWAY_TIMEOUT, {"schema_version": 1, "error": {"type": "upstream_timeout"}})
                LOGGER.warning("conversation request_id=%s model=%s status=504", request_id, "gemini-3.8-flash")
                return
            elapsed_ms = round((time.monotonic() - started) * 1_000)
            self._send(HTTPStatus.OK, response)
            LOGGER.info("conversation request_id=%s model=%s latency_ms=%s status=200", request_id, "gemini-3.8-flash", elapsed_ms)

        def _send(self, status: HTTPStatus, value: dict[str, object]) -> None:
            body = json.dumps(value, separators=(",", ":")).encode("utf-8")
            self.send_response(status.value)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, _format: str, *_args: object) -> None:
            # Request contents, conversation text, and credentials never reach access logs.
            return

    return ConversationHandler


def run(host: str = "0.0.0.0", port: int = 8768) -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    service = ConversationService.from_environment()
    server = ThreadingHTTPServer((host, port), handler_for(service))
    LOGGER.info("Foresight conversation service listening host=%s port=%s model=%s", host, port, "gemini-3.8-flash")
    server.serve_forever()
