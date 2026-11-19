#!/usr/bin/env python3
from __future__ import annotations

import argparse
import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any


def request_json(url: str, body: dict[str, Any] | None = None, timeout: int = 120,
                 opener: urllib.request.OpenerDirector | None = None) -> Any:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"} if data is not None else {},
        method="POST" if data is not None else "GET",
    )
    client = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with client.open(request, timeout=timeout) as response:
        return json.load(response)


def authenticated_opener(base_url: str, username: str, password: str) -> urllib.request.OpenerDirector:
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(cookies))
    request_json(base_url.rstrip("/") + "/api/auth/login", {
        "username": username,
        "password": password,
    }, timeout=10, opener=opener)
    return opener


def resources() -> list[dict[str, str]]:
    return [
        {"id": "eval-folder", "name": "待复核", "type": "FOLDER", "group": "FILES", "status": "READY"},
        {"id": "eval-reviewed-folder", "name": "已复核", "type": "FOLDER", "group": "FILES", "status": "READY"},
        {"id": "eval-resource", "name": "项目说明", "type": "KNOWLEDGE_FILE", "group": "KNOWLEDGE", "status": "READY"},
        {"id": "eval-knowledge", "name": "合同条款", "type": "KNOWLEDGE_FILE", "group": "KNOWLEDGE", "status": "READY"},
        {"id": "eval-dataset", "name": "业务明细", "type": "DATASET", "group": "DATA", "status": "READY"},
        {"id": "eval-source", "name": "业务明细数据库", "type": "DATA_SOURCE", "group": "DATA", "status": "READY"},
        {"id": "eval-workflow", "name": "项目处理流程", "type": "WORKFLOW", "group": "WORKFLOW", "status": "READY"},
        {"id": "eval-output", "name": "阶段成果", "type": "DELIVERABLE", "group": "OUTPUT", "status": "READY"},
    ]


def simulated_observation(tool_name: str, arguments: dict[str, Any], scenario: dict[str, Any]) -> dict[str, Any]:
    output: dict[str, Any] = {"evaluation": True}
    result = "隐藏评测模拟 Observation：工具执行成功"
    if tool_name == "workspace.inspect":
        output = {"project": {"id": "eval-project", "name": "Agent 隐藏评测项目"}, "resources": resources()}
        result = "已读取当前项目及全部工作区对象"
    elif tool_name == "project.list":
        projects = [{"id": "eval-project", "name": "Agent 隐藏评测项目"}]
        if "project.create_workspace" not in scenario["expected_tools"]:
            projects.append({"id": "eval-customer-project", "name": "客户交接"})
        output = {"projects": projects}
        result = "已返回当前用户可访问的项目列表"
    elif tool_name == "project.create_workspace":
        output = {"createdProjectId": "eval-created-project", "project": {"id": "eval-created-project", "name": "客户交接"}}
        result = "已创建项目“客户交接”并切换后续操作上下文"
    elif tool_name in {"resource.read", "knowledge.read"}:
        output = {"resource": {"id": arguments.get("resource_id", "eval-resource"), "name": "项目说明", "content": "复核版需要补充结论和来源。"}}
        result = "已读取材料正文"
    elif tool_name == "knowledge.search":
        output = {"hits": [{"id": "eval-knowledge", "name": "合同条款", "excerpt": "合同条款摘要"}]}
        result = "已找到唯一匹配的知识资料"
    elif tool_name == "dataset.profile":
        output = {"dataset": {"id": "eval-dataset", "name": "业务明细", "rows": 1200, "columns": 18}}
        result = "已检查业务明细的数据结构"
    elif tool_name == "workflow.open":
        output = {"workflow": {"id": "eval-workflow", "name": "项目处理流程", "status": "READY"}}
        result = "已打开可运行的项目处理流程"
    elif tool_name == "assistant.analyze_context":
        output = {"analysis": "当前唯一匹配对象为阶段成果，修改要求是补充结论和来源。"}
        result = "已结合会话和当前对象补齐修改要求"
    return {
        "tool": tool_name,
        "arguments": arguments,
        "success": True,
        "result": result,
        "output": output,
        "provenance": {"trace_id": scenario["id"]},
    }


def run_scenario(worker_url: str, capabilities: list[dict[str, Any]], scenario: dict[str, Any], timeout: int) -> dict[str, Any]:
    expected = set(scenario["expected_tools"])
    selected: list[str] = []
    latest_observation: dict[str, Any] = {}
    goal = "\n".join(scenario["turns"])
    rounds = max(6, len(expected) + 3)
    summaries: list[str] = []
    evaluation_session_id = f"intent-eval:{scenario['id']}:{uuid.uuid4()}"

    for round_index in range(rounds):
        payload = {
            "session_id": evaluation_session_id,
            "execution_mode": scenario["execution_mode"],
            "continuation": round_index > 0,
            "observation": latest_observation,
            "completed_actions": round_index,
            "goal": goal,
            "page": "project-home",
            "project_id": "eval-project",
            "project_name": "Agent 隐藏评测项目",
            "selection": {"type": "folder", "resource_id": "eval-folder", "range": ["待复核"]},
            "resources": resources(),
            "recent_messages": [{"role": "user", "content": turn} for turn in scenario["turns"]],
            "capabilities": capabilities,
        }
        response = request_json(worker_url.rstrip("/") + "/v1/agent/plan", payload, timeout=timeout)
        summaries.append(str(response.get("summary", "")))
        steps = response.get("steps", [])
        if not steps:
            break
        tool_name = str(steps[0].get("tool", ""))
        selected.append(tool_name)
        latest_observation = simulated_observation(
            tool_name, dict(steps[0].get("arguments", {})), scenario,
        )
        if expected.issubset(selected):
            break

    missing = sorted(expected - set(selected))
    unexpected = [tool_name for tool_name in selected
                  if tool_name not in expected and tool_name != "workspace.inspect"]
    return {
        "id": scenario["id"],
        "prompt": goal,
        "expected": sorted(expected),
        "selected": selected,
        "passed": not missing,
        "missing": missing,
        "unexpected": unexpected,
        "tool_call_count": len(selected),
        "summaries": summaries,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Run held-out prompts through the configured DeepAgents planner.")
    parser.add_argument("suite", type=Path)
    parser.add_argument("--worker-url", default="http://127.0.0.1:8001")
    parser.add_argument("--java-url", default="http://127.0.0.1:8080")
    parser.add_argument("--limit", type=int, default=20)
    parser.add_argument("--offset", type=int, default=0)
    parser.add_argument("--timeout", type=int, default=60, help="Maximum seconds for one Agent planning round")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--username", default=os.getenv("FINFLOW_EVAL_USERNAME", "default"))
    parser.add_argument("--password", default=os.getenv("FINFLOW_EVAL_PASSWORD", os.getenv("FINFLOW_AUTH_DEFAULT_PASSWORD", "Pr0d1234")))
    args = parser.parse_args()

    scenarios = [json.loads(line) for line in args.suite.read_text(encoding="utf-8").splitlines() if line.strip()]
    java_opener = authenticated_opener(args.java_url, args.username, args.password)
    capabilities = request_json(args.java_url.rstrip("/") + "/api/assistant/tools", opener=java_opener)
    results: list[dict[str, Any]] = []
    for scenario in scenarios[args.offset:args.offset + args.limit]:
        try:
            result = run_scenario(args.worker_url, capabilities, scenario, args.timeout)
        except urllib.error.HTTPError as exception:
            detail = exception.read().decode("utf-8", errors="replace")
            result = {
                "id": scenario["id"],
                "prompt": "\n".join(scenario["turns"]),
                "expected": scenario["expected_tools"],
                "selected": [],
                "passed": False,
                "missing": scenario["expected_tools"],
                "error": f"HTTP {exception.code}: {detail}",
                "failure_kind": "infrastructure" if exception.code >= 500 else "evaluation",
            }
        except (TimeoutError, urllib.error.URLError, ValueError) as exception:
            result = {
                "id": scenario["id"],
                "prompt": "\n".join(scenario["turns"]),
                "expected": scenario["expected_tools"],
                "selected": [],
                "passed": False,
                "missing": scenario["expected_tools"],
                "error": str(exception),
                "failure_kind": "infrastructure",
            }
        results.append(result)
        state = "PASS" if result["passed"] else "FAIL"
        print(f"[{state}] {result['id']} expected={result['expected']} selected={result['selected']}", flush=True)

    report = {
        "total": len(results),
        "passed": sum(bool(item["passed"]) for item in results),
        "failed": sum(not bool(item["passed"]) for item in results),
        "infrastructure_errors": sum(item.get("failure_kind") == "infrastructure" for item in results),
        "results": results,
    }
    if args.output:
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("total", "passed", "failed", "infrastructure_errors")}, ensure_ascii=False))
    return 0 if report["failed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
