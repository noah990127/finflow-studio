"""Codex CLI task harness using the same authenticated MCP gateway as OpenCode."""
import asyncio
import json
import sys
import tempfile
from pathlib import Path

from ..config import settings
from ..llm import llm
from .opencode_runtime import stop_process, task_prompt
from .activity import ActivityWatch


def codex_command(executable, root, request):
    command = [executable, "exec", "--json", "--ephemeral", "--ignore-user-config",
               "--skip-git-repo-check", "--sandbox", "read-only", "-C", str(root)]
    for feature in ("plugins", "apps", "shell_tool", "browser_use", "computer_use", "js_repl", "hooks"):
        command.extend(["--disable", feature])
    overrides = {
        "web_search": "disabled",
        "model_reasoning_effort": settings.codex_cli_reasoning_effort,
        "mcp_servers.finflow.command": sys.executable,
        "mcp_servers.finflow.args": [str(Path(__file__).with_name("opencode_bridge.py")), str(root / "gateway.json")],
        "mcp_servers.finflow.required": True,
        "mcp_servers.finflow.enabled_tools": ["catalog", "execute", "workflow_status"],
        "mcp_servers.finflow.default_tools_approval_mode": "approve",
        "mcp_servers.finflow.tool_timeout_sec": request.tool_timeout_seconds,
    }
    if settings.codex_cli_force_http:
        overrides.update({"model_provider": "finflow-http", "model_providers.finflow-http.name": "OpenAI HTTPS",
                          "model_providers.finflow-http.requires_openai_auth": True,
                          "model_providers.finflow-http.supports_websockets": False})
    for key, value in overrides.items():
        command.extend(["-c", key + "=" + json.dumps(value, ensure_ascii=False)])
    model = llm._codex_cli_model()
    if model:
        command.extend(["--model", model])
    command.append("-")
    return command


async def run_codex_stream(request):
    executable = llm._codex_cli_path()
    if not executable:
        raise RuntimeError("Codex CLI 未安装或路径不可用，请配置 CODEX_CLI_PATH；不会切回旧助手")
    if request.model_config_override is not None:
        raise RuntimeError("Codex CLI 模式使用本机模型配置，请先清除会话的自定义模型设置")
    with tempfile.TemporaryDirectory(prefix="finflow-codex-agent-") as directory:
        root = Path(directory)
        gateway = root / "gateway.json"
        gateway.write_text(request.model_dump_json(by_alias=True), encoding="utf-8")
        gateway.chmod(0o600)
        inherited = llm._codex_cli_environment(root)
        env = {k: v for k, v in inherited.items() if k in {
            "PATH", "HOME", "LANG", "TMPDIR", "CODEX_HOME", "SSL_CERT_FILE", "SSL_CERT_DIR",
            "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "http_proxy", "https_proxy", "no_proxy"}}
        process = await asyncio.create_subprocess_exec(*codex_command(executable, root, request),
            cwd=root, env=env, stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL, start_new_session=True, limit=4 * 1024 * 1024)
        content, terminal = [], False
        activity = ActivityWatch(root, request.idle_timeout_seconds, request.tool_timeout_seconds)
        try:
            async with asyncio.timeout(request.timeout_seconds):
                process.stdin.write(task_prompt(request).encode("utf-8"))
                await process.stdin.drain()
                process.stdin.close()
                yield {"type": "planning", "message": "Codex 正在安排业务工序", "mode": "codex-cli"}
                while True:
                    activity.check()
                    if (root / "approval.json").exists():
                        await stop_process(process)
                        yield {"type": "waiting_confirmation", "actions": [json.loads((root / "approval.json").read_text())],
                               "message": "需要确认此操作"}
                        return
                    try:
                        line = await asyncio.wait_for(process.stdout.readline(), 1)
                    except asyncio.TimeoutError:
                        yield {"type": "heartbeat"}
                        continue
                    if not line:
                        break
                    try:
                        event = json.loads(line)
                    except ValueError:
                        continue
                    if event.get("type") == "error":
                        # Codex also uses error events for recoverable network reconnects.
                        yield {"type": "heartbeat"}
                        continue
                    if event.get("type") in {"item.started", "item.updated", "item.completed", "turn.completed"}:
                        activity.progress()
                    if event.get("type") == "turn.failed":
                        raise RuntimeError("Codex CLI 执行失败，请检查本机登录状态、模型与网络")
                    if event.get("type") in {"item.updated", "item.completed"} and event.get("item", {}).get("type") == "agent_message":
                        item = event["item"]
                        if item.get("text"):
                            yield {"type": "message", "message": item["text"],
                                   "activity_id": item.get("id") or f"reply-{len(content)}",
                                   "status": "completed" if event["type"] == "item.completed" else "running"}
                    if event.get("type") == "item.completed" and event.get("item", {}).get("type") == "agent_message":
                        content.append(event["item"].get("text", ""))
                    if event.get("type") == "turn.completed":
                        terminal = True
                await process.wait()
                if (root / "approval.json").exists():
                    yield {"type": "waiting_confirmation", "actions": [json.loads((root / "approval.json").read_text())]}
                    return
                if process.returncode != 0 or not terminal or not any(content):
                    raise RuntimeError("Codex CLI 未返回完整结束事件；本轮未标记成功")
                yield {"type": "completed", "content": content[-1], "mode": "codex-cli"}
        except TimeoutError as exception:
            raise RuntimeError(f"本轮达到 {request.timeout_seconds} 秒总执行时限，已停止；已保存产物保留，可继续未完成步骤") from exception
        finally:
            await stop_process(process)
