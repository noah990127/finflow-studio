import asyncio
import json
from pathlib import Path

import httpx
import pytest

from app.agent.opencode_bridge import GatewayBridge, dispatch
from app.agent.opencode_runtime import cli_config, configured_providers, run_opencode_stream, stop_process, task_prompt
from app.agent.runtime import ContinuousAgentRequest
from app.config import settings


def request():
    return ContinuousAgentRequest(run_id="run-1", session_id="session-1", goal="核对销售，不要生成PPT",
                                  page="project-home", project_id="p1", capabilities=[],
                                  gateway_url="http://java:8080", gateway_token="private-token", timeout_seconds=2)


def test_deepseek_key_required_and_injected_without_mutating_config(monkeypatch):
    monkeypatch.setattr(settings, 'opencode_model', 'deepseek/deepseek-v4-flash')
    monkeypatch.setattr(settings, 'deepseek_api_key', '')
    config = {'provider': {'deepseek': {'options': {'baseURL': 'https://api.deepseek.com/v1'}}}}
    with pytest.raises(RuntimeError, match='DEEPSEEK_API_KEY'):
        configured_providers(config)
    monkeypatch.setattr(settings, 'deepseek_api_key', 'test-key')
    assert configured_providers(config)['deepseek']['options']['apiKey'] == 'test-key'
    assert 'apiKey' not in config['provider']['deepseek']['options']


def test_config_denies_direct_tools_and_uses_only_gateway():
    config = cli_config(Path('/bridge.py'), Path('/gateway.json'))
    assert config['permission'] == {'*': 'deny', 'finflow_*': 'allow'}
    assert config['share'] == 'disabled'
    assert list(config['mcp']) == ['finflow']
    assert 'private-token' not in json.dumps(config)


def test_prompt_keeps_goal_without_keyword_routing_or_gateway_secret():
    prompt = task_prompt(request())
    assert '核对销售，不要生成PPT' in prompt
    assert 'private-token' not in prompt
    assert 'work.update' in prompt
    assert 'WAITING_INPUT' in prompt
    assert 'My Work is the user-facing workflow' in prompt
    assert 'Do not create a separate legacy node/edge workflow' in prompt


def test_resource_naming_policy_is_shared_and_preserves_full_goal():
    from app.agent.naming import RESOURCE_NAMING_POLICY
    from app.agent.codex_runtime import task_prompt as codex_prompt
    req = request()
    req.goal = '新建一个项目，调研进口猫咪冻干，价格不重要，质量安全第一'
    for build in (task_prompt, codex_prompt):
        prompt = build(req)
        assert RESOURCE_NAMING_POLICY in prompt
        assert req.goal in prompt
        assert '不要机械截断' in prompt
        assert '严格保留其指定名称' in prompt
        assert '不擅自批量重命名历史资源' in prompt


def test_bridge_preserves_arguments_and_handles_platform_authorization(tmp_path):
    seen = []
    def handler(req):
        seen.append(req)
        return httpx.Response(200, json={'success': False, 'failureType': 'AUTHORIZATION'})
    config = request().model_dump()
    config['capabilities'] = [{'id': 'resource.delete'}]
    pending = tmp_path / 'approval.json'
    with httpx.Client(transport=httpx.MockTransport(handler)) as client:
        bridge = GatewayBridge(config, client, pending)
        args = {'tool': 'resource.delete', 'arguments': {'resource_id': 'f1'}}
        bridge.call('execute', args)
        assert json.loads(seen[0].content) == args
        assert seen[0].headers['X-Agent-Gateway-Token'] == 'private-token'
        assert json.loads(pending.read_text())['arguments'] == args['arguments']
        with pytest.raises(ValueError):
            bridge.call('execute', {'tool': 'shell', 'arguments': {}})
        with pytest.raises(ValueError):
            bridge.call('workflow_status', {'run_id': '../other'})


def test_bridge_mcp_handshake_and_errors(tmp_path):
    with httpx.Client(transport=httpx.MockTransport(lambda r: httpx.Response(503))) as client:
        bridge = GatewayBridge(request().model_dump(), client, tmp_path / 'approval.json')
        assert dispatch(bridge, {'method': 'initialize', 'params': {'protocolVersion': '2025-03-26'}})['protocolVersion'] == '2025-03-26'
        assert len(dispatch(bridge, {'method': 'tools/list'})['tools']) == 3
        assert dispatch(bridge, {'method': 'tools/call', 'params': {'name': 'execute', 'arguments': {'tool': 'unknown'}}})['isError']


@pytest.mark.asyncio
async def test_missing_cli_fails_without_falling_back(monkeypatch):
    monkeypatch.setattr(settings, 'opencode_cli_path', '/nonexistent/opencode')
    with pytest.raises(RuntimeError, match='未切回旧 Agent'):
        async for _ in run_opencode_stream(request()):
            pass


@pytest.mark.asyncio
async def test_cancellation_reaps_process_group():
    import sys
    process = await asyncio.create_subprocess_exec(sys.executable, '-c', 'import time; time.sleep(60)', start_new_session=True)
    await stop_process(process)
    assert process.returncode is not None


class FakeProcess:
    def __init__(self, lines, code=0):
        self.stdout = asyncio.StreamReader()
        self.stdout.feed_data(lines)
        self.stdout.feed_eof()
        self.returncode = code
    async def communicate(self):
        return await self.stdout.read(), b''
    async def wait(self):
        return self.returncode


@pytest.mark.asyncio
@pytest.mark.parametrize('events,success', [
    ([{'type': 'text', 'part': {'text': '已保存底稿'}}, {'type': 'step_finish', 'part': {'reason': 'stop'}}], True),
    ([{'type': 'text', 'part': {'text': '正在处理'}}], False),
    ([{'type': 'error', 'error': 'secret'}], False),
])
async def test_cli_exit_requires_terminal_event(tmp_path, monkeypatch, events, success):
    config = tmp_path / 'provider.json'; config.write_text('{"provider": {}}')
    monkeypatch.setattr(settings, 'opencode_provider_config', str(config))
    monkeypatch.setattr(settings, 'opencode_model', 'company/model')
    monkeypatch.setattr('app.agent.opencode_runtime.shutil.which', lambda _: '/opencode')
    launched = []
    async def spawn(*args, **kwargs):
        launched.append((args, kwargs))
        if '--version' in args:
            return FakeProcess(b'1.2.0\n')
        return FakeProcess(('\n'.join(json.dumps(e) for e in events) + '\n').encode())
    monkeypatch.setattr(asyncio, 'create_subprocess_exec', spawn)
    if success:
        result = [event async for event in run_opencode_stream(request())]
        assert result[-1]['type'] == 'completed'
        assert result[-1]['content'] == '已保存底稿'
    else:
        with pytest.raises(RuntimeError):
            _ = [event async for event in run_opencode_stream(request())]
    assert launched[-1][1]['start_new_session'] is True
    assert launched[-1][1]['stdin'] == asyncio.subprocess.DEVNULL
    assert 'private-token' not in str(launched[-1][0])
    assert 'OPENAI_API_KEY' not in launched[-1][1]['env']


@pytest.mark.asyncio
async def test_v2_is_rejected_before_starting_model(tmp_path, monkeypatch):
    config = tmp_path / 'provider.json'; config.write_text('{"provider": {}}')
    monkeypatch.setattr(settings, 'opencode_provider_config', str(config))
    monkeypatch.setattr(settings, 'opencode_model', 'company/model')
    monkeypatch.setattr('app.agent.opencode_runtime.shutil.which', lambda _: '/opencode')
    async def spawn(*args, **kwargs):
        assert '--version' in args
        return FakeProcess(b'2.0.0\n')
    monkeypatch.setattr(asyncio, 'create_subprocess_exec', spawn)
    with pytest.raises(RuntimeError, match='v2'):
        _ = [event async for event in run_opencode_stream(request())]


@pytest.mark.asyncio
async def test_timeout_cleans_up_child(tmp_path, monkeypatch):
    config = tmp_path / 'provider.json'; config.write_text('{"provider": {}}')
    monkeypatch.setattr(settings, 'opencode_provider_config', str(config))
    monkeypatch.setattr(settings, 'opencode_model', 'company/model')
    monkeypatch.setattr('app.agent.opencode_runtime.shutil.which', lambda _: '/opencode')
    process = FakeProcess(b'')
    process.stdout = asyncio.StreamReader()
    stopped = []
    async def spawn(*args, **kwargs):
        return FakeProcess(b'1.2.0\n') if '--version' in args else process
    async def stop(child):
        stopped.append(child)
    monkeypatch.setattr(asyncio, 'create_subprocess_exec', spawn)
    monkeypatch.setattr('app.agent.opencode_runtime.stop_process', stop)
    req = request(); req.timeout_seconds = 0
    with pytest.raises(RuntimeError, match='总执行时限'):
        _ = [event async for event in run_opencode_stream(req)]
    assert process in stopped


def test_tacit_chat_uses_scoped_ledger_instructions_on_all_runtimes():
    from app.agent.runtime import AgentCapability, _continuous_instructions
    from app.agent.tacit_instructions import TACIT_INSTRUCTIONS
    req = request()
    req.goal = "只读讨论业务台账"
    req.capabilities = [
        AgentCapability(id="tacit.workspace", title="查看台账", description="查看",
                        mode="READ", risk="READ_ONLY"),
    ]
    for prompt in (task_prompt(req), _continuous_instructions(req)):
        assert TACIT_INSTRUCTIONS in prompt
        assert "work.publish_implementation" not in prompt
        assert "private-token" not in prompt
