"""Official A2A SDK transport with owner-scoped, resumable browser-use tasks."""
import asyncio
import contextvars
import hmac
import os
import tempfile
import json
import base64
import logging
from pathlib import Path
from contextlib import asynccontextmanager, suppress
from dataclasses import dataclass, field
from datetime import datetime, timezone

from a2a.server.agent_execution import AgentExecutor
from a2a.server.apps import A2AStarletteApplication
from a2a.server.request_handlers import DefaultRequestHandler
from a2a.server.tasks import InMemoryTaskStore
from a2a.types import AgentCapabilities, AgentCard, AgentSkill, Artifact, Part, TextPart, FilePart, FileWithBytes, TaskState, TaskStatus
from a2a.utils import new_agent_text_message, new_task
from starlette.responses import JSONResponse
from starlette.requests import Request
from dotenv import load_dotenv

from .runtime import BrowserGoal, BrowserUseRuntime, model_settings
from .interaction import KINDS, BrowserTaskFailure

load_dotenv(Path(__file__).resolve().parents[2] / ".env", override=False)
os.environ.setdefault("ANONYMIZED_TELEMETRY", "false")
os.environ.setdefault("BROWSER_USE_CLOUD_SYNC", "false")
# Native CDP/WebSocket traffic stays on this host, never through a model proxy.
_local_bypass = ",".join(dict.fromkeys(filter(None, ["localhost", "127.0.0.1", "::1",
    *os.environ.get("NO_PROXY", "").split(","), *os.environ.get("no_proxy", "").split(",")])))
os.environ["NO_PROXY"] = os.environ["no_proxy"] = _local_bypass

owner = contextvars.ContextVar("browser_agent_owner", default="")
TERMINAL = {TaskState.completed, TaskState.failed, TaskState.canceled, TaskState.rejected}


class OwnerStore(InMemoryTaskStore):
    async def save(self, task, context=None):
        existing = await super().get(task.id)
        if existing and existing.metadata.get("owner") != owner.get():
            raise ValueError("Task not found")
        if existing and (existing.status.timestamp or "") > (task.status.timestamp or ""):
            return
        task.metadata = {**(task.metadata or {}), "owner": owner.get()}
        await super().save(task.model_copy(deep=True))

    async def get(self, task_id, context=None):
        task = await super().get(task_id)
        return task.model_copy(deep=True) if task and task.metadata.get("owner") == owner.get() else None

    async def delete(self, task_id, context=None):
        if await self.get(task_id):
            await super().delete(task_id)


@dataclass
class Execution:
    task: object
    owner: str
    resume: asyncio.Event = field(default_factory=asyncio.Event)
    job: asyncio.Task | None = None
    prompt_id: int = 0
    cancel_reason: str = "runtime_cancelled"
    focus: object = None
    liveness: object = None
    interaction: dict = field(default_factory=dict)
    receipt: dict | None = None


class BrowserExecutor(AgentExecutor):
    def __init__(self, store, runtime=None):
        self.store = store
        self.runtime = runtime or BrowserUseRuntime()
        self.executions = {}
        self.requests = {}
        self.lock = asyncio.Lock()
        self.execution_timeout = 3600
        self.human_timeout = 86400
        self.human_poll_interval = 10

    async def status(self, execution, state, message):
        execution.task.status = TaskStatus(
            state=state, message=new_agent_text_message(message),
            timestamp=datetime.now(timezone.utc).isoformat(),
        )
        execution.task.metadata = {**(execution.task.metadata or {}), "promptId": execution.prompt_id,
                                   "canFocus": state == TaskState.input_required and execution.focus is not None,
                                   "interaction": execution.interaction if state == TaskState.input_required else None}
        await self.store.save(execution.task)
        logging.getLogger(__name__).info("Browser task=%s state=%s prompt=%s reason=%s", execution.task.id, state.value, execution.prompt_id, execution.task.metadata.get("stopReason", ""))

    async def execute(self, context, event_queue):
        async with self.lock:
            task = context.current_task
            if task:
                execution = self.executions.get(task.id)
                if not execution or execution.owner != owner.get():
                    raise ValueError("任务已失效，请重新发起；不会自动重跑旧操作")
                metadata = context.message.metadata or {}
                if metadata.get("command") == "focus":
                    if execution.task.status.state != TaskState.input_required or not execution.focus:
                        raise ValueError("当前没有可接管的操作窗口")
                    await execution.focus()
                    await event_queue.enqueue_event(execution.task.model_copy(deep=True))
                    return
                if execution.task.status.state != TaskState.input_required or metadata.get("promptId") != execution.prompt_id:
                    raise ValueError("当前没有匹配的待处理输入，请刷新任务状态")
                expected = "approve" if execution.interaction.get("kind") == "APPROVAL" else "resume"
                if metadata.get("command", "resume") != expected:
                    raise ValueError("此操作需要对应的明确确认，不能用普通继续代替审批")
                execution.receipt = {"taskId": task.id, "promptId": execution.prompt_id,
                    "kind": execution.interaction["kind"], "decision": "approved" if expected == "approve" else "completed",
                    "request": execution.interaction["message"], "owner": execution.owner,
                    "confirmedAt": datetime.now(timezone.utc).isoformat()}
                await self.status(execution, TaskState.working, "正在继续处理")
                execution.resume.set()
            else:
                # Platform authorization to start a task, not a human submission receipt.
                metadata = context.message.metadata or {}
                if metadata.get("approved") is not True:
                    raise ValueError("Browser task requires explicit user approval")
                spec = BrowserGoal.model_validate(metadata.get("browserGoal"))
                request_key = metadata.get("requestKey")
                if not isinstance(request_key, str) or not request_key or len(request_key) > 128:
                    raise ValueError("A stable requestKey is required")
                key = (owner.get(), request_key)
                if key in self.requests:
                    previous, original = self.requests[key]
                    if original != spec:
                        raise ValueError("An approved request cannot change its goal")
                    await event_queue.enqueue_event(self.executions[previous].task.model_copy(deep=True))
                    return
                if any(e.owner == owner.get() and e.task.status.state not in TERMINAL for e in self.executions.values()):
                    raise ValueError("请先完成或中止当前浏览器任务，避免操作多个窗口")
                model_settings()
                task = new_task(context.message)
                task.metadata = {"owner": owner.get(), "engine": "browser-use", "url": spec.url, "approvalMode": spec.approval_mode}
                execution = Execution(task, owner.get())
                self.executions[task.id] = execution
                self.requests[key] = (task.id, spec)
                await self.status(execution, TaskState.working, "正在打开网站")
                execution.job = asyncio.create_task(self.run(execution, spec))
            await event_queue.enqueue_event(execution.task.model_copy(deep=True))

    async def run(self, execution, spec):
        deadline = None
        async def progress(message):
            await self.status(execution, TaskState.working, message)

        async def human(message, kind="INPUT"):
            if kind not in KINDS:
                raise ValueError("Unknown human interaction kind")
            if kind == "APPROVAL" and spec.approval_mode == "AUTO":
                execution.task.metadata["policyApprovalCount"] = execution.task.metadata.get("policyApprovalCount", 0) + 1
                await progress("按自动执行设置继续处理本次任务")
                return {"kind": kind, "decision": "approved", "source": "execution_policy",
                        "taskId": execution.task.id, "owner": execution.owner,
                        "request": message, "scope": spec.goal}
            execution.resume.clear()
            execution.prompt_id += 1
            execution.receipt = None
            execution.interaction = {"kind": kind, "message": message}
            await self.status(execution, TaskState.input_required, message)
            # Human wait is explicit, not model inactivity. Never auto-resume.
            remaining = max(0, deadline.when() - asyncio.get_running_loop().time()) if deadline and deadline.when() else None
            if deadline:
                deadline.reschedule(None)
            try:
                from .recovery import WindowDisconnected
                async with asyncio.timeout(self.human_timeout):
                    while not execution.resume.is_set():
                        try:
                            await asyncio.wait_for(execution.resume.wait(), self.human_poll_interval)
                        except TimeoutError:
                            if execution.liveness:
                                try:
                                    async with asyncio.timeout(5):
                                        alive = await execution.liveness()
                                except Exception:
                                    alive = False
                                if not alive:
                                    raise WindowDisconnected("操作窗口已断开，本轮已中断。请核对已执行的操作；不会自动重发。")
                return execution.receipt
            except TimeoutError:
                # Ordinary tool errors can be retried by browser-use. Expired human
                # control must terminate the task, never let the agent proceed.
                execution.cancel_reason = "human_timeout"
                raise asyncio.CancelledError("Human handoff expired") from None
            finally:
                if deadline and remaining is not None:
                    deadline.reschedule(asyncio.get_running_loop().time() + remaining)

        try:
            history_root = Path(os.environ.get("BROWSER_AGENT_DATA_DIR", ".local-runtime/browser-agent"))
            history_root.mkdir(parents=True, exist_ok=True, mode=0o700)
            replay = None
            if spec.replay_source:
                # Platform requests carry the pinned, owner-checked resource. Local storage
                # remains a compatibility path for existing direct worker clients only.
                if spec.replay_bundle is not None:
                    replay = spec.replay_bundle
                else:
                    saved = json.loads((history_root / (spec.replay_source + ".procedure.json")).read_text())
                    if saved["owner"] != execution.owner:
                        raise ValueError("浏览器实现不属于当前账号")
                    replay = saved["bundle"]
                if replay.get("hash") != spec.replay_hash:
                    raise ValueError("浏览器实现不存在或版本不匹配")
                from .procedure import bind_procedure
                bind_procedure(replay, spec.parameters)
                spec = spec.model_copy(update={"url": replay["url"], "goal": replay["goal"], "allowed_domains": replay["allowed_domains"],
                                               "approval_mode": replay.get("approval_mode", "CONFIRM")})
                execution.task.metadata["approvalMode"] = spec.approval_mode
            with tempfile.TemporaryDirectory(prefix="task-", dir=history_root) as workspace:
                from .recovery import profile_directory, profile_path, register_focus, register_liveness
                profile_token = profile_directory.set(profile_path(history_root, execution.owner, spec.url))
                focus_token = register_focus.set(lambda callback: setattr(execution, "focus", callback))
                liveness_token = register_liveness.set(lambda callback: setattr(execution, "liveness", callback))
                try:
                    async with asyncio.timeout(self.execution_timeout) as deadline:
                        result = await self.runtime.run(spec, workspace, progress, human, replay=replay) if replay else await self.runtime.run(spec, workspace, progress, human)
                finally:
                    profile_directory.reset(profile_token)
                    register_focus.reset(focus_token)
                    register_liveness.reset(liveness_token)
                    execution.focus = None
                    execution.liveness = None
                    checkpoint = Path(workspace) / "checkpoint.json"
                    if checkpoint.exists():
                        target = history_root / (execution.task.id + ".checkpoint.json")
                        target.write_bytes(checkpoint.read_bytes())
                        target.chmod(0o600)
                    history = Path(workspace) / "history.json"
                    if history.exists():
                        target = history_root / (execution.task.id + ".json")
                        target.write_bytes(history.read_bytes())
                        target.chmod(0o600)
                text = result["text"] if isinstance(result, dict) else result
                execution.task.artifacts = [Artifact(
                    artifact_id="result", name="浏览器任务结果", parts=[Part(root=TextPart(text=text))],
                )]
                if isinstance(result, dict):
                    execution.task.metadata['procedureState'] = result.get('procedureState', 'NEEDS_REVIEW')
                    if result.get("procedure"):
                        bundle = result["procedure"]
                        target = history_root / (execution.task.id + ".procedure.json")
                        target.write_text(json.dumps({"owner": execution.owner, "bundle": bundle}, ensure_ascii=False))
                        target.chmod(0o600)
                        execution.task.metadata["procedure"] = {"hash": bundle["hash"], "parameters": bundle["parameters"], "engine": bundle["engine"],
                                                               "allowed_domains": bundle["allowed_domains"], 'version': bundle.get('version', 1),
                                                               'validation': bundle.get('validation', {})}
                        execution.task.metadata['procedureState'] = 'READY'
                        result.setdefault("files", []).append({"name": "browser-procedure.json", "mimeType": "application/json",
                            "bytes": base64.b64encode(json.dumps(bundle, ensure_ascii=False).encode()).decode()})
                    if result.get("procedureError"):
                        execution.task.metadata["procedureError"] = result["procedureError"]
                    if result.get('procedureCandidate'):
                        candidate = result['procedureCandidate']
                        target = history_root / (execution.task.id + '.candidate.json')
                        target.write_text(json.dumps({'owner': execution.owner, 'candidate': candidate}, ensure_ascii=False))
                        target.chmod(0o600)
                        result.setdefault('files', []).append({'name': 'browser-procedure-candidate.json', 'mimeType': 'application/json',
                            'bytes': base64.b64encode(json.dumps(candidate, ensure_ascii=False).encode()).decode()})
                    for index, file in enumerate(result.get("files", [])):
                        execution.task.artifacts.append(Artifact(artifact_id=f"file-{index}", name=file["name"],
                            parts=[Part(root=FilePart(file=FileWithBytes(**file)))]))
                if execution.task.metadata.get('procedureError'):
                    text = ('【平台核验】本次网页操作已完成，但可重放工序尚未固化：'
                            + execution.task.metadata['procedureError']
                            + '。下方文字是 Agent 的执行说明，附件不等于已发布的可执行脚本。\n\n' + text)
                await self.status(execution, TaskState.completed, text)
        except asyncio.CancelledError:
            execution.task.metadata["stopReason"] = execution.cancel_reason
            message = {
                "human_timeout": "等待人工操作超过24小时，任务已停止。请先核对网站结果，再决定是否重新执行。",
                "cancel_requested": "已停止执行。已提交的操作不会撤回，请核对网站结果。",
                "service_shutdown": "执行服务已关闭，任务被中断。请先核对网站结果。",
            }.get(execution.cancel_reason, "执行过程被中断，请先核对网站结果；系统不会自动重复提交。")
            await self.status(execution, TaskState.canceled, message)
        except Exception as exc:
            from .recovery import WindowDisconnected
            import logging
            logging.getLogger(__name__).exception("Browser task %s failed", execution.task.id)
            execution.task.metadata["failureCode"] = exc.code if isinstance(exc, BrowserTaskFailure) else type(exc).__name__
            await self.status(execution, TaskState.failed, str(exc) if isinstance(exc, (WindowDisconnected, BrowserTaskFailure)) else "浏览器任务未完成（" + type(exc).__name__ + "），请检查服务日志；不要重复提交。")

    async def cancel(self, context, event_queue):
        execution = self.executions.get(context.task_id)
        if not execution or execution.owner != owner.get():
            raise ValueError("Task not found")
        if execution.task.status.state not in TERMINAL and execution.job:
            execution.cancel_reason = "cancel_requested"
            execution.job.cancel()
            with suppress(asyncio.CancelledError):
                await execution.job
            if execution.task.status.state not in TERMINAL:
                await self.status(execution, TaskState.canceled, "任务已中止")
        await event_queue.enqueue_event(execution.task.model_copy(deep=True))


class BrowserRequestHandler(DefaultRequestHandler):
    """Deduplicate before the SDK allocates a new task/context identifier."""
    def __init__(self, agent_executor, task_store):
        super().__init__(agent_executor=agent_executor, task_store=task_store)
        self.browser_executor = agent_executor
        self.browser_store = task_store
        self.dispatch_lock = asyncio.Lock()

    async def on_message_send(self, params, context=None):
        async with self.dispatch_lock:
            metadata = params.message.metadata or {}
            key = (owner.get(), metadata.get("requestKey"))
            if not params.message.task_id and isinstance(key[1], str) and key in self.browser_executor.requests:
                task_id, spec = self.browser_executor.requests[key]
                if metadata.get("approved") is not True or BrowserGoal.model_validate(metadata.get("browserGoal")) != spec:
                    raise ValueError("An approved request cannot change its goal")
                return await self.browser_store.get(task_id)
            return await super().on_message_send(params, context)


store = OwnerStore()
executor = BrowserExecutor(store)
card = AgentCard(
    name="FinFlow Browser Agent", description="Delegated browser-use tasks with human handoff",
    url=os.environ.get("BROWSER_AGENT_URL", "http://127.0.0.1:8003"), version="1.0.0",
    capabilities=AgentCapabilities(streaming=False),
    default_input_modes=["text/plain"], default_output_modes=["text/plain"],
    skills=[AgentSkill(id="browser-task", name="Operate a website", description="Execute an approved website task", tags=["browser-use"])],
)
sdk_app = A2AStarletteApplication(agent_card=card, http_handler=BrowserRequestHandler(
    agent_executor=executor, task_store=store,
)).build()


@asynccontextmanager
async def lifespan(_):
    yield
    for execution in executor.executions.values():
        execution.cancel_reason = "service_shutdown"
    jobs = [execution.job for execution in executor.executions.values() if execution.job and not execution.job.done()]
    for job in jobs:
        job.cancel()
    await asyncio.gather(*jobs, return_exceptions=True)


sdk_app.router.lifespan_context = lifespan


async def app(scope, receive, send):
    if scope["type"] != "http":
        return await sdk_app(scope, receive, send)
    headers = dict(scope.get("headers", []))
    if scope.get("path") == "/health":
        return await JSONResponse({"status": "ok", "engine": "browser-use", "protocol": "a2a"})(scope, receive, send)
    secret = os.environ.get("BROWSER_AGENT_TOKEN", "")
    supplied = headers.get(b"authorization", b"").decode()
    actor = headers.get(b"x-finflow-owner", b"").decode()
    if len(secret) < 32 or not hmac.compare_digest(supplied, "Bearer " + secret) or not actor:
        return await JSONResponse({"error": "Unauthorized"}, status_code=403)(scope, receive, send)
    token = owner.set(actor)
    try:
        if scope.get("path") == "/procedures/export" and scope.get("method") == "POST":
            # Only the authenticated platform calls this after checking its durable
            # task owner and completed result. Used to adopt pre-upgrade histories.
            data = await Request(scope, receive).json()
            task_id = data.get("taskId", "")
            import re
            if not re.fullmatch(r"[a-f0-9-]{36}", task_id):
                return await JSONResponse({"error": "Invalid task"}, status_code=400)(scope, receive, send)
            root = Path(os.environ.get("BROWSER_AGENT_DATA_DIR", ".local-runtime/browser-agent"))
            path = root / (task_id + ".procedure.json")
            try:
                if path.exists():
                    record = json.loads(path.read_text())
                    if record["owner"] != actor:
                        raise ValueError("Task not found")
                    bundle = record["bundle"]
                else:
                    from .procedure import compile_procedure
                    spec = BrowserGoal.model_validate(data["goal"])
                    bundle = compile_procedure(json.loads((root / (task_id + ".json")).read_text()), spec)
                    path.write_text(json.dumps({"owner": actor, "bundle": bundle}, ensure_ascii=False)); path.chmod(0o600)
                return await JSONResponse(bundle)(scope, receive, send)
            except (ValueError, FileNotFoundError) as error:
                return await JSONResponse({"error": str(error)}, status_code=422)(scope, receive, send)
        await sdk_app(scope, receive, send)
    finally:
        owner.reset(token)
