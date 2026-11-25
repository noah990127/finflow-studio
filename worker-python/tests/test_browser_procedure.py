import copy
from types import SimpleNamespace
import pytest
from app.browser_agent.procedure import compile_procedure, bind_procedure, require_complete


def sample():
    def step(action, result=None):
        return {"model_output": {"action": [action], "memory": "private"},
                "state": {"url": "https://example.com/form?sid=secret", "tabs": [{"url": "private"}],
                          "interacted_element": [{"attributes": {"type": "text"}}], "screenshot_path": "private.png"},
                "result": [result or {}]}
    return {"history": [step({"navigate": {"url": "https://blocked.test"}}, {"error": "blocked"}),
                        step({"input": {"index": 3, "text": "old", "clear": True}}),
                        step({"click": {"index": 4}}), step({"done": {"success": True}}, {"success": True})]}


def compile(history=None):
    return compile_procedure(history or sample(), SimpleNamespace(url="https://example.com/form", goal="Fill and submit", allowed_domains=[], approval_mode="AUTO"))


def test_freeze_removes_exploration_and_old_claims():
    bundle = compile()
    assert bundle["approval_mode"] == "AUTO"
    assert len(bundle["history"]["history"]) == 2
    step = bundle["history"]["history"][0]
    assert step["result"] == []
    assert step["state"]["url"] == "https://example.com/form"
    assert not step["state"]["tabs"] and step["state"]["screenshot_path"] is None
    assert "memory" not in step["model_output"]


def test_only_inputs_change_not_actions_or_original():
    bundle = compile(); original = copy.deepcopy(bundle)
    bound = bind_procedure(bundle, {"input_1": "new"})
    assert bound["history"][0]["model_output"]["action"][0]["input"]["text"] == "new"
    assert bound["history"][1]["model_output"]["action"] == [{"click": {"index": 4}}]
    assert bundle == original


def test_tampering_or_extra_input_is_rejected():
    bundle = compile()
    with pytest.raises(ValueError): bind_procedure(bundle, {"unexpected": "value"})
    bundle["history"]["history"][1]["model_output"]["action"] = [{"click": {"index": 999}}]
    with pytest.raises(ValueError): bind_procedure(bundle, {"input_1": "new"})


def test_ambiguous_write_failure_cannot_be_frozen():
    history = sample(); history["history"][2]["result"] = [{"error": "unknown click outcome"}]
    with pytest.raises(ValueError): compile(history)


def test_password_and_tab_ids_cannot_be_frozen():
    history = sample(); history["history"][1]["state"]["interacted_element"][0]["attributes"]["type"] = "password"
    with pytest.raises(ValueError): compile(history)
    history = sample(); history["history"][1]["model_output"]["action"] = [{"switch": {"tab_id": "stale"}}]
    with pytest.raises(ValueError): compile(history)


def test_failed_work_never_becomes_a_procedure():
    history = sample(); history["history"][-1]["result"][-1]["success"] = False
    with pytest.raises(ValueError): compile(history)


def test_replay_stops_on_partial_execution_or_error():
    with pytest.raises(RuntimeError): require_complete([SimpleNamespace(error=None)], 2)
    with pytest.raises(RuntimeError): require_complete([SimpleNamespace(error="failed")], 1)
    require_complete([SimpleNamespace(error=None)], 1)
