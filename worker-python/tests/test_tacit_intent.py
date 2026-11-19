import json

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.tacit import intent_api


@pytest.fixture
def client():
    app = FastAPI()
    app.include_router(intent_api.router)
    return TestClient(app)


@pytest.mark.parametrize("mode,task,question", [
    ("execute", "build_ontology", None), ("discuss", "generate_flow", None),
    ("clarify", None, "要绘图还是提出能力问题？"),
])
def test_classifier_receives_only_current_directive(client, monkeypatch, mode, task, question):
    result = dict(mode=mode, task_intent=task, reason="当前用户指令", question=question)

    async def model(system, directive, override):
        assert override is None
        assert "不继承上轮" in system
        assert directive == "帮我处理本体候选"
        return json.dumps(result, ensure_ascii=False)

    monkeypatch.setattr(intent_api, "complete_text", model)
    response = client.post("/tacit/intent", json={"directive": "帮我处理本体候选"})
    assert response.status_code == 200 and response.json() == result
    assert client.post("/tacit/intent", json={"directive": "继续", "history": ["提交候选"]}).status_code == 422


@pytest.mark.parametrize("raw", [
    None, "not json",
    '{"mode":"execute","task_intent":"admin","reason":"x","question":null}',
    '{"mode":"execute","task_intent":"discuss","reason":"x","question":null}',
    '{"mode":"clarify","task_intent":"generate_flow","reason":"x","question":"why?"}',
    '{"mode":"clarify","task_intent":null,"reason":"x","question":" "}',
    '{"mode":"discuss","task_intent":"discuss","reason":"x","question":"why?"}',
    '{"mode":"execute","task_intent":"generate_flow","reason":"x","question":null,"tool":"tacit.generate_flow"}',
])
def test_invalid_model_output_returns_no_decision(client, monkeypatch, raw):
    async def model(*args):
        return raw
    monkeypatch.setattr(intent_api, "complete_text", model)
    assert client.post("/tacit/intent", json={"directive": "请绘制业务流程图"}).status_code == 503


def test_admin_chat_completions_provider_routes_without_opencode_binary(client, tmp_path, monkeypatch):
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from threading import Thread
    from app.config import settings
    seen = {}
    decision = dict(mode="execute", task_intent="generate_flow", reason="请求绘图", question=None)

    class Endpoint(BaseHTTPRequestHandler):
        def do_POST(self):
            seen["path"] = self.path
            seen["auth"] = self.headers["Authorization"]
            seen["request"] = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
            body = json.dumps({"id": "fixture-turn", "object": "chat.completion", "created": 1, "model": "fixture",
                               "choices": [{"index": 0, "message": {"role": "assistant", "content": json.dumps(decision)},
                                            "finish_reason": "stop"}]}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, *args):
            pass

    with ThreadingHTTPServer(("127.0.0.1", 0), Endpoint) as endpoint:
        thread = Thread(target=endpoint.serve_forever, daemon=True)
        thread.start()
        try:
            for name in ("all_proxy","http_proxy","https_proxy","ALL_PROXY","HTTP_PROXY","HTTPS_PROXY"):
                monkeypatch.delenv(name, raising=False)
            config = tmp_path / "provider.json"
            config.write_text(json.dumps({"provider": {"company": {"npm": "@ai-sdk/openai-compatible", "options": {
                "baseURL": f"http://127.0.0.1:{endpoint.server_port}/v1", "apiKey": "fixture-key"}}}}))
            monkeypatch.setattr(settings, "llm_provider", "offline")
            monkeypatch.setattr(settings, "agent_runtime", "opencode")
            monkeypatch.setattr(settings, "opencode_model", "company/fixture")
            monkeypatch.setattr(settings, "opencode_provider_config", str(config))
            monkeypatch.setattr(settings, "opencode_cli_path", "/does-not-exist")
            response = client.post("/tacit/intent", json={"directive": "生成业务流程图"})
            assert response.status_code == 200, response.text
            assert response.json() == decision
            assert seen["path"] == "/v1/chat/completions"
            assert seen["auth"] == "Bearer fixture-key"
            assert [m["role"] for m in seen["request"]["messages"]] == ["system", "user"]
            assert seen["request"]["messages"][1]["content"] == "生成业务流程图"
            assert "tools" not in seen["request"]
        finally:
            endpoint.shutdown()
            thread.join()
