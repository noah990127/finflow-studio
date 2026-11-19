import json

import pytest
from pydantic import ValidationError
from fastapi.testclient import TestClient

from app.browser_runner.engine import RunRequest, Script, validate_request, validate_csv, site_config
from app.browser_runner.app import app


def script():
    return {"version": 1, "site": "expense-demo", "steps": [
        {"action": "fill", "target": {"by": "label", "name": "开始日期"}, "value": {"$param": "startDate"}},
        {"action": "fill", "target": {"by": "label", "name": "结束日期"}, "value": {"$param": "endDate"}},
        {"action": "click", "target": {"by": "role", "role": "button", "name": "查询"}},
        {"action": "assert_text", "target": {"by": "role", "role": "status", "name": "查询结果"}, "value": {"$param": "startDate"}},
        {"action": "assert_text", "target": {"by": "role", "role": "status", "name": "查询结果"}, "value": {"$param": "endDate"}},
        {"action": "download", "target": {"by": "role", "role": "button", "name": "导出CSV"}},
    ]}


CONFIG = {"url": "http://127.0.0.1:8766/", "fields": ["开始日期", "结束日期"],
          "buttons": ["查询", "导出CSV"], "columns": ["date", "department", "amount"], "dateColumn": "date"}
PARAMETERS = {"startDate": "2026-10-01", "endDate": "2026-10-31"}


def test_parameters_do_not_replace_script():
    request = RunRequest(script=script(), parameters=PARAMETERS)
    validate_request(request, CONFIG)
    with pytest.raises(ValueError, match="缺少执行参数"):
        validate_request(RunRequest(script=script(), parameters={}), CONFIG)


@pytest.mark.parametrize("action", ["evaluate", "shell", "goto", "upload"])
def test_arbitrary_execution_rejected(action):
    value = script(); value["steps"][0]["action"] = action
    with pytest.raises(ValidationError):
        Script.model_validate(value)


def test_destructive_buttons_rejected():
    value = script(); value["steps"][2]["target"]["name"] = "删除"
    with pytest.raises(ValueError, match="授权"):
        validate_request(RunRequest(script=value, parameters=PARAMETERS), CONFIG)


def test_assertions_and_download_are_required():
    value = script(); value["steps"] = [value["steps"][-1]]
    with pytest.raises(ValueError): Script.model_validate(value)
    value = script(); value["steps"].append(value["steps"][-1])
    with pytest.raises(ValueError): Script.model_validate(value)


@pytest.mark.parametrize("data", [b"<html>login</html>", b"date,department,amount\n", b"date,department,amount\n2026-11-01,sales,1\n"])
def test_wrong_or_empty_export_fails(data):
    with pytest.raises(ValueError): validate_csv(data, CONFIG, PARAMETERS)


def test_csv_validated_against_input_dates():
    assert validate_csv(b"date,department,amount\n2026-10-01,sales,1\n", CONFIG, PARAMETERS) == 1


def test_unconfigured_site_and_missing_auth_fail(monkeypatch):
    monkeypatch.setenv("BROWSER_SITES_JSON", "{}")
    with pytest.raises(ValueError, match="未获"): site_config("unknown")
    monkeypatch.setenv("BROWSER_RUNNER_TOKEN", "x" * 32)
    assert TestClient(app).post("/inspect", json={"site": "expense-demo"}).status_code == 403


@pytest.mark.asyncio
async def test_browser_use_requires_explicit_isolation(monkeypatch):
    from app.browser_runner.exploration import propose
    monkeypatch.delenv("BROWSER_USE_SANDBOXED", raising=False)
    with pytest.raises(ValueError, match="隔离"):
        await propose("expense-demo", "export report", PARAMETERS)


@pytest.mark.asyncio
async def test_real_browser_replays_changed_dates(monkeypatch):
    import os
    if not os.environ.get("BROWSER_E2E"):
        pytest.skip("BROWSER_E2E enables local portal browser integration")
    import base64
    from app.browser_runner.engine import execute, inspect_site
    monkeypatch.setenv("BROWSER_SITES_JSON", json.dumps({"expense-demo": CONFIG}))
    snapshot = await inspect_site("expense-demo")
    assert "查询" in snapshot["snapshot"]
    first = await execute(RunRequest(script=script(), parameters=PARAMETERS))
    second = await execute(RunRequest(script=script(), parameters={"startDate": "2026-11-01", "endDate": "2026-11-30"}))
    assert first["audit"]["scriptHash"] == second["audit"]["scriptHash"]
    assert first["audit"]["rowCount"] == second["audit"]["rowCount"] == 3
    assert b"2026-10-03" in base64.b64decode(first["contentBase64"])
    assert b"2026-11-02" in base64.b64decode(second["contentBase64"])
    assert first["contentBase64"] != second["contentBase64"]
