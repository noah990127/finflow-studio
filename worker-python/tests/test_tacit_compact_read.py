import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.tacit.api import router
from app.tacit.workspace_state import new_state


@pytest.fixture
def client():
    app = FastAPI()
    app.include_router(router)
    return TestClient(app)


def envelope(action):
    state = new_state()
    state["artifacts"]["cqs"] = {"content": [{"cq_text": "材料" * 25000}], "status": "draft"}
    return {"action": action, "actor": "expert", "state": state, "catalog": [], "payload": {"section": "scope"}}


def test_read_response_preserves_legacy_shape_and_compact_returns_exact_result(client):
    payload = envelope("read_section")
    full_response = client.post("/tacit/command", json=payload)
    compact_response = client.post("/tacit/command", json={**payload, "result_only": True})
    assert full_response.status_code == compact_response.status_code == 200
    assert "cqs" in full_response.json()["state"]["artifacts"]
    assert compact_response.json() == {"state": {}, "result": full_response.json()["result"]}
    assert len(compact_response.content) < len(full_response.content) // 10


@pytest.mark.parametrize("action", ["read_source", "extract", "review_cq", "generate_cqs", "save_agent_batch"])
def test_result_only_refuses_actions_that_must_commit_full_state(client, action):
    response = client.post("/tacit/command", json={**envelope(action), "result_only": True})
    assert response.status_code == 422 and "完整状态" in response.json()["detail"]
