import asyncio
import pytest
from app.config import settings
from app.fixed_work import FixedModelRequest, model_snapshot, execute_model
from app.llm import llm


def test_pinned_model_rejects_configuration_drift(monkeypatch):
    monkeypatch.setattr(settings, 'llm_provider', 'deepseek')
    snapshot = model_snapshot()
    monkeypatch.setattr(settings, 'deepseek_chat_model', 'different-model')
    with pytest.raises(ValueError, match='配置已变化'):
        asyncio.run(execute_model(FixedModelRequest(snapshot=snapshot, prompt='固定提示词', inputs={'a': 'text'})))


def test_fixed_prompt_keeps_instruction_and_replaces_only_input(monkeypatch):
    monkeypatch.setattr(settings, 'llm_provider', 'deepseek')
    calls = []
    async def complete(system, user):
        calls.append((system, user))
        return 'result'
    monkeypatch.setattr(llm, 'complete', complete)
    snapshot = model_snapshot()
    for data in ['old', 'new', 'new']:
        result = asyncio.run(execute_model(FixedModelRequest(snapshot=snapshot, prompt='fixed system', inputs={'source': data})))
        assert result['content'] == 'result'
    assert all(call[0] == 'fixed system' for call in calls)
    assert calls[1] == calls[2]
    assert snapshot['tools'] == []


def test_cli_is_explicitly_pinned_to_isolated_text_executor(monkeypatch):
    monkeypatch.setattr(settings, 'llm_provider', 'codex-cli')
    monkeypatch.setattr(llm, '_codex_cli_model', lambda: 'fixed-model')
    snapshot = model_snapshot()
    assert snapshot['tools'] == []
    assert snapshot['model'] == 'fixed-model'
    assert snapshot['executor'] == 'isolated-text-no-tools-v1'
    monkeypatch.setattr(llm, '_codex_cli_model', lambda: '')
    with pytest.raises(ValueError, match='明确'):
        model_snapshot()


def test_empty_output_fails_without_fallback(monkeypatch):
    monkeypatch.setattr(settings, 'llm_provider', 'deepseek')
    async def empty(*args):
        return ''
    monkeypatch.setattr(llm, 'complete', empty)
    with pytest.raises(ValueError, match='未返回文本'):
        asyncio.run(execute_model(FixedModelRequest(snapshot=model_snapshot(), prompt='fixed', inputs={'a': 'b'})))


def test_same_sql_recomputes_changed_and_unchanged_data_through_http():
    import json
    from fastapi.testclient import TestClient
    from app.main import app
    client = TestClient(app)
    sql = 'select sum(amount) as total from source'
    def run(content):
        return client.post('/v1/data-transforms/sample', files=[('files', ('data.csv', content, 'text/csv'))],
                           data={'metadata': json.dumps([{'alias': 'source', 'name': 'data.csv', 'source_kind': 'FILE'}]), 'script': sql})
    assert run(b'amount\n10\n20\n').json()['rows'] == [{'total': 30}]
    assert run(b'amount\n10\n40\n').json()['rows'] == [{'total': 50}]
    assert run(b'amount\n10\n40\n').json()['rows'] == [{'total': 50}]
    assert run(b'wrong_column\n10\n').status_code >= 400
