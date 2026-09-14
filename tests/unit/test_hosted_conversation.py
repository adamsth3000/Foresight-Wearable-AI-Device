from __future__ import annotations

import json
import threading
import time
from http.client import HTTPConnection
from http.server import ThreadingHTTPServer

import pytest

from foresight_device.conversation.models import ConversationRequest, ConversationRequestError
from foresight_device.conversation.service import (
    GEMINI_MODEL_ID,
    SYSTEM_POLICY,
    ConversationService,
    GeminiUpstreamError,
)
from foresight_device.conversation.server import handler_for


class FakeGemini:
    def __init__(self, response: str = "A concise answer.") -> None:
        self.response = response
        self.system_instruction = ""
        self.prompt = ""

    def generate(self, *, system_instruction: str, prompt: str) -> str:
        self.system_instruction = system_instruction
        self.prompt = prompt
        return self.response


def request_payload() -> dict[str, object]:
    return {
        "schema_version": 1,
        "utterance": "What do you see?",
        "context": {"capture_active": True, "observed_objects": [{"label": "chair", "confidence": 0.9}]},
        "history": [{"role": "user", "text": "Earlier question"}],
    }


def test_valid_request_injects_shared_policy_and_context() -> None:
    gemini = FakeGemini()
    response = ConversationService(gemini).respond(ConversationRequest.from_mapping(request_payload()))

    assert response["model_identity"] == GEMINI_MODEL_ID
    assert response["spoken_text"] == "A concise answer."
    assert gemini.system_instruction == SYSTEM_POLICY
    assert "chair" in gemini.prompt
    assert "What do you see?" in gemini.prompt


def test_malformed_schema_is_rejected() -> None:
    with pytest.raises(ConversationRequestError):
        ConversationRequest.from_mapping({"schema_version": 2})


def test_history_and_response_are_bounded() -> None:
    payload = request_payload()
    payload["history"] = [{"role": "user", "text": "x" * 500} for _ in range(12)]
    response = ConversationService(FakeGemini("word " * 300)).respond(ConversationRequest.from_mapping(payload))

    assert len(response["spoken_text"]) <= 700


def test_upstream_failure_is_not_a_device_action() -> None:
    class FailingGemini:
        def generate(self, *, system_instruction: str, prompt: str) -> str:
            raise GeminiUpstreamError("unavailable")

    with pytest.raises(GeminiUpstreamError):
        ConversationService(FailingGemini()).respond(ConversationRequest.from_mapping(request_payload()))


def test_http_boundary_rejects_bad_schema_and_maps_upstream_timeout() -> None:
    class SlowGemini:
        def generate(self, *, system_instruction: str, prompt: str) -> str:
            time.sleep(0.02)
            return "late"

    service = ConversationService(SlowGemini())
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler_for(service, upstream_timeout_seconds=0.001))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        status, _ = post(server, {"schema_version": 2})
        assert status == 400
        status, body = post(server, request_payload())
        assert status == 504
        assert body["error"]["type"] == "upstream_timeout"
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=1)


def post(server: ThreadingHTTPServer, payload: dict[str, object]) -> tuple[int, dict[str, object]]:
    connection = HTTPConnection("127.0.0.1", server.server_port, timeout=1)
    connection.request("POST", "/v1/conversation", body=json.dumps(payload), headers={"Content-Type": "application/json"})
    response = connection.getresponse()
    body = json.loads(response.read().decode("utf-8"))
    connection.close()
    return response.status, body
