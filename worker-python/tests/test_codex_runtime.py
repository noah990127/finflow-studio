import asyncio
import json
from pathlib import Path

import pytest

from app.agent.codex_runtime import codex_command, run_codex_stream
from app.agent.runtime import ContinuousAgentRequest
from app.config import Settings
from app.llm import llm


def request():
    return ContinuousAgentRequest(run_id='r', session_id='s', goal='核对资料', page='project-home',
        project_id='p', capabilities=[], gateway_url='http://localhost:8080', gateway_token='private-token', timeout_seconds=2)


def test_runtime_switch_and_invalid_value():
    for mode in ('codex-cli', 'opencode', 'deep-agents'):
        assert Settings(_env_file=None, agent_runtime=mode).agent_runtime == mode
    with pytest.raises(ValueError):
        Settings(_env_file=None, agent_runtime='typo')


def test_command_exposes_only_platform_mcp_and_restricts_host_tools(monkeypatch):
    monkeypatch.setattr(llm, '_codex_cli_model', lambda: 'test-model')
    command = codex_command('/codex', Path('/tmp/run'), request())
    assert '--ignore-user-config' in command
    assert command[command.index('--sandbox') + 1] == 'read-only'
    assert 'shell_tool' in command and 'plugins' in command
    assert 'mcp_servers.finflow.required=true' in command
    assert 'private-token' not in str(command)


class Input:
    def write(self, value):
        assert b'private-token' not in value
    async def drain(self):
        pass
    def close(self):
        pass


class Process:
    returncode = 0
    def __init__(self, events):
        self.stdin = Input()
        self.stdout = asyncio.StreamReader()
        self.stdout.feed_data(('\n'.join(json.dumps(e) for e in events) + '\n').encode())
        self.stdout.feed_eof()
    async def wait(self):
        return 0


@pytest.mark.asyncio
@pytest.mark.parametrize('outcome', ['completed', 'reconnect', 'incomplete', 'error', 'approval', 'timeout', 'cancel'])
async def test_stream_terminal_approval_and_cleanup(monkeypatch, outcome):
    monkeypatch.setattr(llm, '_codex_cli_path', lambda: '/codex')
    monkeypatch.setattr(llm, '_codex_cli_model', lambda: '')
    monkeypatch.setattr(llm, '_codex_cli_environment', lambda _: {'PATH': '/bin', 'SECRET': 'never-forward'})
    stopped = []
    async def stop(process):
        stopped.append(process)
    async def spawn(*args, **kwargs):
        root = Path(kwargs['cwd'])
        assert 'SECRET' not in kwargs['env']
        assert (root / 'gateway.json').stat().st_mode & 0o777 == 0o600
        if outcome == 'approval':
            (root / 'approval.json').write_text(json.dumps({'toolName': 'resource.delete', 'arguments': {'resource_id': 'f'}}))
        events = [
            {'type': 'item.completed', 'item': {'id': 'intro', 'type': 'agent_message', 'text': '正在处理'}},
            {'type': 'item.updated', 'item': {'id': 'final', 'type': 'agent_message', 'text': '已'}},
            {'type': 'item.completed', 'item': {'id': 'final', 'type': 'agent_message', 'text': '已完成'}},
        ]
        if outcome in {'completed', 'reconnect'}:
            events.append({'type': 'turn.completed'})
        if outcome == 'reconnect':
            events.insert(0, {'type': 'error', 'message': 'Reconnecting...'})
        if outcome == 'cancel':
            async def cancel():
                raise asyncio.CancelledError()
            process = Process(events)
            process.stdout.readline = cancel
            return process
        if outcome == 'error':
            events = [{'type': 'turn.failed', 'error': 'secret'}]
        return Process(events)
    monkeypatch.setattr(asyncio, 'create_subprocess_exec', spawn)
    monkeypatch.setattr('app.agent.codex_runtime.stop_process', stop)
    req = request()
    if outcome == 'timeout':
        req.timeout_seconds = 0
    if outcome in {'incomplete', 'error', 'timeout', 'cancel'}:
        with pytest.raises((RuntimeError, TimeoutError, asyncio.CancelledError)):
            _ = [event async for event in run_codex_stream(req)]
    else:
        events = [event async for event in run_codex_stream(req)]
        assert events[-1]['type'] == ('waiting_confirmation' if outcome == 'approval' else 'completed')
        if outcome != 'approval':
            assert events[-1]['content'] == '已完成'
            assert [e['message'] for e in events if e['type'] == 'message'] == ['正在处理', '已', '已完成']
    assert stopped


@pytest.mark.asyncio
async def test_missing_codex_does_not_fall_back(monkeypatch):
    monkeypatch.setattr(llm, '_codex_cli_path', lambda: None)
    with pytest.raises(RuntimeError, match='CODEX_CLI_PATH'):
        _ = [event async for event in run_codex_stream(request())]
