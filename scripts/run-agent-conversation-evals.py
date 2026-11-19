#!/usr/bin/env python3
from __future__ import annotations

import argparse
import http.cookiejar
import json
import os
import time
import urllib.error
import urllib.request
import uuid
from typing import Any


TERMINAL = {"SUCCEEDED", "FAILED", "CANCELED", "ROLLED_BACK", "WAITING_CONFIRMATION"}
WRITE_PREFIXES = ("project.", "folder.", "resource.", "dataset.", "workflow.", "deliverable.", "source.add")
READ_TOOLS = {
    "project.list", "project.open", "resource.open", "resource.read", "dataset.open", "dataset.query",
    "dataset.profile", "workflow.open", "deliverable.open", "workspace.inspect", "workspace.navigate",
    "workspace.select", "knowledge.search", "knowledge.read", "source.verify",
}


class Client:
    def __init__(self, base_url: str, username: str, password: str) -> None:
        self.base_url = base_url.rstrip("/")
        cookies = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(cookies),
        )
        self.request("POST", "/api/auth/login", {"username": username, "password": password})

    def request(self, method: str, path: str, body: dict[str, Any] | None = None,
                timeout: int = 30) -> Any:
        data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
        request = urllib.request.Request(
            self.base_url + path, data=data,
            headers={"Content-Type": "application/json"} if data is not None else {}, method=method,
        )
        with self.opener.open(request, timeout=timeout) as response:
            if response.status == 204:
                return None
            return json.load(response)


def send(client: Client, session_id: str, project_id: str, text: str, mode: str = "AUTO") -> dict[str, Any]:
    return client.request("POST", f"/api/assistant/sessions/{session_id}/messages", {
        "projectId": project_id,
        "text": text,
        "page": "project-home",
        "route": "/",
        "clientContextVersion": 1,
        "executionMode": mode,
        "requestId": str(uuid.uuid4()),
    }, timeout=45)


def wait_run(client: Client, run_id: str, timeout: int = 240) -> dict[str, Any]:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        run = client.request("GET", f"/api/assistant/runs/{run_id}")
        if run["status"] in TERMINAL:
            return run
        time.sleep(1)
    raise TimeoutError(f"Agent run {run_id} did not finish within {timeout} seconds")


def events_for(client: Client, session_id: str, run_id: str) -> list[dict[str, Any]]:
    events = client.request("GET", f"/api/assistant/sessions/{session_id}/event-history?after=0")
    return [event for event in events if event.get("runId") == run_id]


def tool_calls(events: list[dict[str, Any]]) -> list[str]:
    return [str(event.get("payload", {}).get("toolName", "")) for event in events
            if event.get("type") == "agent.tool_call"]


def latest_assistant_message(client: Client, session_id: str) -> str:
    messages = client.request("GET", f"/api/assistant/sessions/{session_id}/messages")
    assistant = [str(message.get("content", "")) for message in messages if message.get("role") == "ASSISTANT"]
    return assistant[-1] if assistant else ""


def record(results: list[dict[str, Any]], name: str, passed: bool, **detail: Any) -> None:
    results.append({"name": name, "passed": passed, **detail})
    print(f"[{'PASS' if passed else 'FAIL'}] {name}", flush=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Run real multi-turn natural-language conversations against FinFlow.")
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--username", default=os.getenv("FINFLOW_EVAL_USERNAME", "default"))
    parser.add_argument("--password", default=os.getenv("FINFLOW_EVAL_PASSWORD", os.getenv("FINFLOW_AUTH_DEFAULT_PASSWORD", "Pr0d1234")))
    parser.add_argument("--keep-fixtures", action="store_true")
    parser.add_argument("--output")
    args = parser.parse_args()

    client = Client(args.base_url, args.username, args.password)
    suffix = time.strftime("%m%d-%H%M%S")
    created_projects: list[str] = []
    results: list[dict[str, Any]] = []
    try:
        project = client.request("POST", "/api/projects", {
            "name": f"Agent自然语言回归-{suffix}", "description": "自动化临时项目，可安全清理",
        })
        created_projects.append(project["id"])
        session = client.request("POST", "/api/assistant/sessions", {
            "projectId": project["id"], "title": f"自然语言回归 {suffix}",
        })

        response = send(client, session["id"], project["id"],
                        "在这个项目里建一个叫‘待复核资料’的文件夹，然后把它改名成‘待核验资料’，别动其他内容。")
        run = wait_run(client, response["run"]["id"])
        events = events_for(client, session["id"], run["id"])
        calls = tool_calls(events)
        workspace = client.request("GET", f"/api/projects/{project['id']}/workspace")
        folder_names = {folder["name"] for folder in workspace.get("folders", [])}
        workflow_count_before = len(workspace.get("workflows", []))
        record(results, "连续创建并改名文件夹",
               run["status"] == "SUCCEEDED" and "folder.create" in calls and "folder.rename" in calls
               and "待核验资料" in folder_names and "待复核资料" not in folder_names,
               status=run["status"], tools=calls, folders=sorted(folder_names))

        response = send(client, session["id"], project["id"],
                        "另外新建一条可复用工作流：先看资料，再分析收入和成本，最后出一份PPT。先把流程建好，这次不要运行。")
        run = wait_run(client, response["run"]["id"])
        events = events_for(client, session["id"], run["id"])
        calls = tool_calls(events)
        workspace = client.request("GET", f"/api/projects/{project['id']}/workspace")
        workflow_summaries = workspace.get("workflows", [])
        workflow_details = [client.request("GET", f"/api/workflows/{item['id']}") for item in workflow_summaries]
        created_workflows = [item for item in workflow_details
                             if "收入" in item.get("name", "") and "成本" in item.get("name", "")]
        concise_names = all(len(item.get("name", "")) <= 24 and "第一步" not in item.get("name", "")
                            and "不运行" not in item.get("name", "") for item in created_workflows)
        complete_workflow = any(item.get("nodes") and "收入" in item.get("description", "")
                                and "成本" in item.get("description", "") and "PPT" in item.get("description", "").upper()
                                for item in created_workflows)
        record(results, "尊重只创建不运行流程",
               run["status"] == "SUCCEEDED" and "workflow.prepare" in calls and "workflow.run" not in calls
               and len(workflow_summaries) > workflow_count_before and bool(created_workflows)
               and concise_names and complete_workflow, status=run["status"], tools=calls,
               workflows=[{"name": item.get("name"), "description": item.get("description"),
                           "nodeCount": len(item.get("nodes", []))} for item in workflow_details])

        response = send(client, session["id"], project["id"],
                        "这个项目现在有什么？只告诉我项目名称、文件夹和工作流，不要修改任何内容。")
        run = wait_run(client, response["run"]["id"])
        events = events_for(client, session["id"], run["id"])
        calls = tool_calls(events)
        writes = [tool for tool in calls if tool.startswith(WRITE_PREFIXES) and tool not in READ_TOOLS]
        answer = latest_assistant_message(client, session["id"])
        record(results, "只读问答不修改工作区",
               run["status"] == "SUCCEEDED" and not writes and "待核验资料" in answer,
               status=run["status"], tools=calls, answer=answer[:500])

        response = send(client, session["id"], project["id"],
                        "帮我打开一份叫‘绝对不存在的资料’的文件。找不到就直接告诉我，别让我提供什么系统编号。")
        run = wait_run(client, response["run"]["id"])
        events = events_for(client, session["id"], run["id"])
        calls = tool_calls(events)
        answer = latest_assistant_message(client, session["id"])
        record(results, "不存在对象能收敛并说明",
               run["status"] == "SUCCEEDED" and len(calls) <= 5 and "_id" not in answer.lower()
               and ("找不到" in answer or "不存在" in answer or "没有" in answer),
               status=run["status"], tools=calls, answer=answer[:500])

        response = send(client, session["id"], project["id"], "把‘待核验资料’文件夹删掉。", mode="APPROVAL")
        run = wait_run(client, response["run"]["id"])
        events = events_for(client, session["id"], run["id"])
        workspace = client.request("GET", f"/api/projects/{project['id']}/workspace")
        folder_names = {folder["name"] for folder in workspace.get("folders", [])}
        waiting = any(event.get("type") == "agent.waiting_confirmation" for event in events)
        record(results, "审批模式写入前暂停",
               run["status"] == "WAITING_CONFIRMATION" and waiting and "待核验资料" in folder_names,
               status=run["status"], tools=tool_calls(events), folders=sorted(folder_names))
        client.request("POST", f"/api/assistant/runs/{run['id']}/cancel")

        other = client.request("POST", "/api/projects", {
            "name": f"Agent上下文切换-{suffix}", "description": "验证全局对话使用当前项目上下文",
        })
        created_projects.append(other["id"])
        response = send(client, session["id"], other["id"], "我现在所在的项目叫什么？只回答名称，不要修改。")
        run = wait_run(client, response["run"]["id"])
        answer = latest_assistant_message(client, session["id"])
        current_session = client.request("GET", f"/api/assistant/sessions/{session['id']}")
        record(results, "全局对话跟随当前项目上下文",
               run["status"] == "SUCCEEDED" and other["name"] in answer
               and current_session["projectId"] == other["id"],
               status=run["status"], answer=answer[:500], sessionProjectId=current_session["projectId"])
    except (urllib.error.HTTPError, urllib.error.URLError, TimeoutError, KeyError) as exception:
        detail = str(exception)
        if isinstance(exception, urllib.error.HTTPError):
            detail = exception.read().decode("utf-8", errors="replace")
        record(results, "评测基础设施", False, error=detail)
    finally:
        if not args.keep_fixtures:
            for project_id in reversed(created_projects):
                try:
                    client.request("DELETE", f"/api/projects/{project_id}")
                except Exception:
                    pass

    report = {
        "total": len(results),
        "passed": sum(bool(item["passed"]) for item in results),
        "failed": sum(not bool(item["passed"]) for item in results),
        "results": results,
    }
    if args.output:
        with open(args.output, "w", encoding="utf-8") as file:
            json.dump(report, file, ensure_ascii=False, indent=2)
            file.write("\n")
    print(json.dumps({key: report[key] for key in ("total", "passed", "failed")}, ensure_ascii=False))
    return 0 if report["failed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
