import asyncio
import json
from types import SimpleNamespace

import pytest
pytest.importorskip("a2a", reason="Browser Agent tests use its isolated environment")
from a2a.types import Message, Part, TextPart, TaskState

from app.browser_agent.runtime import BrowserGoal, model_settings
from app.browser_agent.service import BrowserExecutor, OwnerStore, owner


class Queue:
    def __init__(self):
        self.events = []

    async def enqueue_event(self, value):
        self.events.append(value)


class FakeBrowser:
    def __init__(self):
        self.calls = 0
        self.entered = asyncio.Event()
        self.finish = asyncio.Event()

    async def run(self, spec, workspace, progress, human):
        self.calls += 1
        await human("请登录")
        self.entered.set()
        await progress("核验页面")
        await self.finish.wait()
        return "已核验模拟结果"


def request(task=None, **metadata):
    message = Message(role="user", message_id="request-1", parts=[Part(root=TextPart(text="task"))], metadata=metadata)
    return SimpleNamespace(current_task=task, message=message, task_id=task.id if task else None)


async def wait_state(store, task_id, state):
    for _ in range(100):
        task = await store.get(task_id)
        if task and task.status.state == state:
            return task
        await asyncio.sleep(.01)
    raise AssertionError(f"Task did not reach {state}")


@pytest.fixture
def setup(monkeypatch, tmp_path):
    monkeypatch.setenv("BROWSER_MODEL_API_KEY", "test-key-not-a-real-secret")
    monkeypatch.setenv("BROWSER_AGENT_DATA_DIR", str(tmp_path))
    token = owner.set("alice")
    store = OwnerStore()
    runtime = FakeBrowser()
    executor = BrowserExecutor(store, runtime)
    yield store, executor, runtime
    owner.reset(token)


async def start(executor, mode="CONFIRM"):
    queue = Queue()
    await executor.execute(request(approved=True, requestKey="stable-key", browserGoal={"url": "https://example.com", "goal": "测试查询", "approval_mode": mode}), queue)
    return queue.events[-1]


@pytest.mark.asyncio
async def test_auto_submission_does_not_pause_but_login_still_does(setup):
    store, executor, runtime = setup
    receipts = []
    async def run(spec, workspace, progress, human):
        receipts.append(await human("Log in", "LOGIN"))
        receipts.append(await human("Send within requested scope", "APPROVAL"))
        return "verified"
    runtime.run = run
    task = await start(executor, "AUTO")
    task = await wait_state(store, task.id, TaskState.input_required)
    assert task.metadata["interaction"]["kind"] == "LOGIN"
    await executor.execute(request(task, command="resume", promptId=1), Queue())
    await wait_state(store, task.id, TaskState.completed)
    assert receipts[1]["source"] == "execution_policy"
    assert receipts[1]["scope"] == "测试查询"
    assert executor.executions[task.id].prompt_id == 1


@pytest.mark.asyncio
async def test_freeze_failure_keeps_execution_success_and_candidate(setup, tmp_path):
    store, executor, runtime = setup
    async def run(spec, workspace, progress, human):
        return {'text':'Operation verified', 'procedureError':'Missing source binding',
                'procedureCandidate':{'version':2, 'status':'NEEDS_REVIEW', 'steps':[]}, 'files':[]}
    runtime.run = run
    task = await start(executor, 'AUTO')
    completed = await wait_state(store, task.id, TaskState.completed)
    assert completed.metadata['procedureState'] == 'NEEDS_REVIEW'
    assert 'procedure' not in completed.metadata
    candidate = json.loads((tmp_path / (task.id + '.candidate.json')).read_text())
    assert candidate['owner'] == 'alice'
    assert candidate['candidate']['status'] == 'NEEDS_REVIEW'
    assert any(a.name == 'browser-procedure-candidate.json' for a in completed.artifacts)


def test_execution_policy_rejects_unknown_mode():
    with pytest.raises(ValueError):
        BrowserGoal(url="https://example.com", goal="read", approval_mode="anything")


@pytest.mark.asyncio
@pytest.mark.parametrize("saved_mode", ["AUTO", "CONFIRM", None])
@pytest.mark.parametrize("archived_resource", [False, True])
async def test_replay_inherits_frozen_policy_not_incoming_policy(setup, tmp_path, saved_mode, archived_resource):
    from app.browser_agent.procedure import ENGINE, digest
    store, executor, runtime = setup
    source = "11111111-1111-1111-1111-111111111111"
    body = {"engine": ENGINE, "url": "https://example.com", "goal": "fixed", "allowed_domains": [],
            "history": {"history": []}, "bindings": []}
    if saved_mode is not None:
        body["approval_mode"] = saved_mode
    bundle = {**body, "hash": digest(body), "parameters": {}}
    if not archived_resource:
        (tmp_path / (source + ".procedure.json")).write_text(json.dumps({"owner": "alice", "bundle": bundle}))
    observed = []
    async def run(spec, workspace, progress, human, replay=None):
        observed.append(spec.approval_mode)
        return "verified"
    runtime.run = run
    queue = Queue()
    await executor.execute(request(approved=True, requestKey="replay-policy", browserGoal={
        "url": "https://example.com", "goal": "replay", "approval_mode": "AUTO" if saved_mode != "AUTO" else "CONFIRM",
        "replay_source": source, "replay_hash": bundle["hash"],
        **({"replay_bundle": bundle} if archived_resource else {})}), queue)
    await wait_state(store, queue.events[-1].id, TaskState.completed)
    assert observed == [saved_mode or "CONFIRM"]


@pytest.mark.asyncio
async def test_submission_requires_approval_not_generic_resume(setup):
    store, executor, runtime = setup
    receipts = []
    async def run(spec, workspace, progress, human):
        receipts.append(await human("Send exactly this preview to the approved recipient", "APPROVAL"))
        return "confirmed"
    runtime.run = run
    task = await start(executor)
    task = await wait_state(store, task.id, TaskState.input_required)
    assert task.metadata["interaction"]["kind"] == "APPROVAL"
    with pytest.raises(ValueError, match="明确确认"):
        await executor.execute(request(task, command="resume", promptId=1), Queue())
    assert not receipts
    with pytest.raises(ValueError, match="待处理"):
        await executor.execute(request(task, command="approve", promptId=0), Queue())
    await executor.execute(request(task, command="approve", promptId=1), Queue())
    await wait_state(store, task.id, TaskState.completed)
    assert receipts[0]["decision"] == "approved"
    assert receipts[0]["request"] == "Send exactly this preview to the approved recipient"
    assert receipts[0]["owner"] == "alice"
    assert receipts[0]["taskId"] == task.id


@pytest.mark.asyncio
async def test_login_receipt_does_not_authorize_submission(setup):
    store, executor, runtime = setup
    receipts = []
    async def run(spec, workspace, progress, human):
        receipts.append(await human("Log in", "LOGIN"))
        return "ok"
    runtime.run = run
    task = await start(executor)
    task = await wait_state(store, task.id, TaskState.input_required)
    with pytest.raises(ValueError, match="明确确认"):
        await executor.execute(request(task, command="approve", promptId=1), Queue())
    await executor.execute(request(task, command="resume", promptId=1), Queue())
    await wait_state(store, task.id, TaskState.completed)
    assert receipts[0]["kind"] == "LOGIN"
    assert receipts[0]["decision"] == "completed"


@pytest.mark.asyncio
async def test_safe_failure_reason_reaches_user(setup):
    from app.browser_agent.interaction import unsuccessful_result
    store, executor, runtime = setup
    async def run(*args):
        raise unsuccessful_result(["Expecting ',' delimiter: secret page contents"])
    runtime.run = run
    task = await start(executor)
    task = await wait_state(store, task.id, TaskState.failed)
    assert task.metadata["failureCode"] == "MODEL_OUTPUT_INVALID"
    assert "操作格式" in task.status.message.parts[0].root.text
    assert "secret" not in task.status.message.parts[0].root.text


def test_platform_clock_has_timezone_and_does_not_need_browser():
    from app.browser_agent.interaction import current_time
    from datetime import datetime
    result = current_time("Asia/Shanghai")
    assert datetime.fromisoformat(result["time"]).utcoffset().total_seconds() == 28800
    assert result["source"] == "platform_system_clock"
    with pytest.raises(KeyError):
        current_time("not/a/timezone")


@pytest.mark.asyncio
async def test_cancel_records_reason_without_repeating_task(setup):
    store, executor, runtime = setup
    task = await start(executor)
    task = await wait_state(store, task.id, TaskState.input_required)
    await executor.cancel(request(task), Queue())
    stopped = await store.get(task.id)
    assert stopped.status.state == TaskState.canceled
    assert stopped.metadata["stopReason"] == "cancel_requested"
    assert runtime.calls == 1
    assert not runtime.entered.is_set()


@pytest.mark.asyncio
async def test_internal_cancellation_is_not_labeled_user_cancel(setup):
    store, executor, runtime = setup
    task = await start(executor)
    await wait_state(store, task.id, TaskState.input_required)
    executor.executions[task.id].job.cancel()
    await executor.executions[task.id].job
    stopped = await store.get(task.id)
    assert stopped.metadata["stopReason"] == "runtime_cancelled"


@pytest.mark.asyncio
async def test_focus_opens_window_without_resuming_or_replanning(setup):
    store, executor, runtime = setup
    task = await start(executor)
    task = await wait_state(store, task.id, TaskState.input_required)
    calls = []
    async def focus():
        calls.append("focus")
    executor.executions[task.id].focus = focus
    await executor.execute(request(task, command="focus", promptId=task.metadata["promptId"]), Queue())
    assert calls == ["focus"]
    assert (await store.get(task.id)).status.state == TaskState.input_required
    assert not runtime.entered.is_set()
    await executor.cancel(request(task), Queue())


@pytest.mark.asyncio
async def test_closed_window_ends_human_wait_without_repeating_task(setup):
    store, executor, runtime = setup
    executor.human_poll_interval = .01
    task = await start(executor)
    await wait_state(store, task.id, TaskState.input_required)
    async def closed():
        return False
    executor.executions[task.id].liveness = closed
    failed = await wait_state(store, task.id, TaskState.failed)
    assert "窗口已断开" in failed.status.message.parts[0].root.text
    assert runtime.calls == 1
    assert not runtime.entered.is_set()


@pytest.mark.asyncio
async def test_human_wait_does_not_consume_execution_budget(setup):
    store, executor, runtime = setup
    executor.execution_timeout = .08
    task = await start(executor)
    task = await wait_state(store, task.id, TaskState.input_required)
    await asyncio.sleep(.12)
    assert (await store.get(task.id)).status.state == TaskState.input_required
    runtime.finish.set()
    await executor.execute(request(task, promptId=task.metadata["promptId"]), Queue())
    await wait_state(store, task.id, TaskState.completed)


@pytest.mark.asyncio
async def test_replay_cannot_use_another_owners_artifact(setup, tmp_path):
    store, executor, runtime = setup
    source = "11111111-1111-1111-1111-111111111111"
    (tmp_path / (source + ".procedure.json")).write_text(json.dumps({"owner": "bob", "bundle": {"hash": "a" * 64}}))
    queue = Queue()
    await executor.execute(request(approved=True, requestKey="replay-key", browserGoal={
        "url": "https://example.com", "goal": "replay", "replay_source": source, "replay_hash": "a" * 64}), queue)
    task = await wait_state(store, queue.events[-1].id, TaskState.failed)
    assert task.status.state == TaskState.failed
    assert runtime.calls == 0


@pytest.mark.asyncio
async def test_handoff_resumes_same_agent_without_replanning(setup):
    store, executor, runtime = setup
    task = await start(executor)
    task = await wait_state(store, task.id, TaskState.input_required)
    assert not runtime.entered.is_set()
    queue = Queue()
    await executor.execute(request(task, promptId=task.metadata["promptId"]), queue)
    await asyncio.wait_for(runtime.entered.wait(), 1)
    assert runtime.calls == 1
    runtime.finish.set()
    task = await wait_state(store, task.id, TaskState.completed)
    assert task.artifacts[0].parts[0].root.text == "已核验模拟结果"


@pytest.mark.asyncio
async def test_repeated_request_and_resume_do_not_repeat_execution(setup):
    store, executor, runtime = setup
    task = await start(executor)
    current = await wait_state(store, task.id, TaskState.input_required)
    assert (await start(executor)).id == task.id
    with pytest.raises(ValueError, match="待处理"):
        await executor.execute(request(current, promptId=0), Queue())
    await executor.execute(request(current, promptId=1), Queue())
    current = await wait_state(store, task.id, TaskState.working)
    with pytest.raises(ValueError, match="待处理"):
        await executor.execute(request(current, promptId=1), Queue())
    assert runtime.calls == 1
    await executor.cancel(request(current), Queue())


@pytest.mark.asyncio
async def test_owner_cannot_read_resume_or_cancel_another_task(setup):
    store, executor, runtime = setup
    task = await start(executor)
    await wait_state(store, task.id, TaskState.input_required)
    token = owner.set("bob")
    try:
        assert await store.get(task.id) is None
        with pytest.raises(ValueError):
            await executor.cancel(request(task), Queue())
        with pytest.raises(ValueError):
            await executor.execute(request(task, promptId=1), Queue())
    finally:
        owner.reset(token)
    await executor.cancel(request(task), Queue())
    assert (await store.get(task.id)).status.state == TaskState.canceled


@pytest.mark.asyncio
async def test_task_requires_explicit_approval(setup):
    store, executor, runtime = setup
    with pytest.raises(ValueError, match="approval"):
        await executor.execute(request(browserGoal={"url": "https://example.com", "goal": "read"}), Queue())
    assert runtime.calls == 0


@pytest.mark.asyncio
async def test_downloads_are_a2a_file_artifacts_not_lost_temp_paths(setup):
    store, executor, runtime = setup
    async def downloaded(*args):
        return {"text": "已下载", "files": [{"name": "report.csv", "mimeType": "text/csv", "bytes": "YSxiCg=="}]}
    runtime.run = downloaded
    task = await start(executor)
    task = await wait_state(store, task.id, TaskState.completed)
    file = task.artifacts[1].parts[0].root.file
    assert file.name == "report.csv"
    assert file.bytes == "YSxiCg=="


def test_config_and_url_contract(monkeypatch):
    monkeypatch.delenv("BROWSER_MODEL_API_KEY", raising=False)
    monkeypatch.delenv("DEEPSEEK_API_KEY", raising=False)
    with pytest.raises(ValueError, match="API_KEY"):
        model_settings()
    for url in ["file:///etc/passwd", "https://user:password@example.com"]:
        with pytest.raises(ValueError):
            BrowserGoal(url=url, goal="read")
    with pytest.raises(ValueError):
        BrowserGoal(url="https://example.com", goal="x" * 601)


@pytest.mark.asyncio
async def test_official_a2a_http_contract_and_owner_isolation(setup, monkeypatch):
    import httpx
    from a2a.server.apps import A2AStarletteApplication
    from a2a.server.request_handlers import DefaultRequestHandler
    from app.browser_agent import service
    store, executor, runtime = setup
    monkeypatch.setenv("BROWSER_AGENT_TOKEN", "test-service-token-" * 3)
    monkeypatch.setattr(service, "sdk_app", A2AStarletteApplication(
        agent_card=service.card,
        http_handler=service.BrowserRequestHandler(agent_executor=executor, task_store=store),
    ).build())
    transport = httpx.ASGITransport(app=service.app)
    headers = {"Authorization": "Bearer " + "test-service-token-" * 3, "X-Finflow-Owner": "alice"}
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        assert (await client.post("/", json={})).status_code == 403
        async def rpc(method, params, actor="alice"):
            response = await client.post("/", headers={**headers, "X-Finflow-Owner": actor},
                json={"jsonrpc": "2.0", "id": "test", "method": method, "params": params})
            assert response.status_code == 200, response.text
            return response.json()
        message = {"kind": "message", "role": "user", "messageId": "http-message",
                   "parts": [{"kind": "text", "text": "read page"}],
                   "metadata": {"approved": True, "requestKey": "http-key",
                                "browserGoal": {"url": "https://example.com", "goal": "read page"}}}
        response = await rpc("message/send", {"message": message})
        assert "result" in response, response
        task_id = response["result"]["id"]
        task = await wait_state(store, task_id, TaskState.input_required)
        other = await rpc("tasks/get", {"id": task_id}, actor="bob")
        assert "error" in other, other
        response = await rpc("tasks/get", {"id": task_id})
        assert response["result"]["status"]["state"] == "input-required"
        duplicate = await rpc("message/send", {"message": message})
        assert duplicate["result"]["id"] == task_id
        message = {**message, "messageId": "resume", "taskId": task_id,
                   "metadata": {"promptId": task.metadata["promptId"]}}
        resumed = await rpc("message/send", {"message": message})
        assert "result" in resumed, resumed
        await asyncio.wait_for(runtime.entered.wait(), 1)
        assert runtime.calls == 1
        canceled = await rpc("tasks/cancel", {"id": task_id})
        assert canceled["result"]["status"]["state"] == "canceled", canceled
