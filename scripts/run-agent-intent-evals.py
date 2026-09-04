#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


def request_json(url: str, body: dict[str, Any] | None = None, timeout: int = 120) -> Any:
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"} if data is not None else {},
        method="POST" if data is not None else "GET",
    )
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(request, timeout=timeout) as response:
        return json.load(response)


def resources() -> list[dict[str, str]]:
    return [
        {"id": "eval-folder", "name": "待整理内容", "type": "FOLDER", "group": "FILES", "status": "READY"},
        {"id": "eval-resource", "name": "项目说明", "type": "KNOWLEDGE_FILE", "group": "KNOWLEDGE", "status": "READY"},
        {"id": "eval-dataset", "name": "业务明细", "type": "DATASET", "group": "DATA", "status": "READY"},
        {"id": "eval-workflow", "name": "项目处理流程", "type": "WORKFLOW", "group": "WORKFLOW", "status": "READY"},
        {"id": "eval-output", "name": "阶段成果", "type": "DELIVERABLE", "group": "OUTPUT", "status": "READY"},
    ]


def run_scenario(worker_url: str, capabilities: list[dict[str, Any]], scenario: dict[str, Any], timeout: int) -> dict[str, Any]:
    expected = set(scenario["expected_tools"])
    selected: list[str] = []
    latest_observation: dict[str, Any] = {}
    goal = "\n".join(scenario["turns"])
    rounds = max(2, len(expected) + 2)
    summaries: list[str] = []

    for round_index in range(rounds):
        payload = {
            "session_id": f"intent-eval:{scenario['id']}",
            "execution_mode": scenario["execution_mode"],
            "continuation": round_index > 0,
            "observation": latest_observation,
            "completed_actions": round_index,
            "goal": goal,
            "page": "project-home",
            "project_id": "eval-project",
            "project_name": "Agent 隐藏评测项目",
            "selection": {},
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
        latest_observation = {
            "tool": tool_name,
            "arguments": steps[0].get("arguments", {}),
            "success": True,
            "result": "隐藏评测模拟 Observation：工具执行成功",
            "output": {"evaluation": True},
            "provenance": {"trace_id": scenario["id"]},
        }
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
    args = parser.parse_args()

    scenarios = [json.loads(line) for line in args.suite.read_text(encoding="utf-8").splitlines() if line.strip()]
    capabilities = request_json(args.java_url.rstrip("/") + "/api/assistant/tools")
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
            }
        results.append(result)
        state = "PASS" if result["passed"] else "FAIL"
        print(f"[{state}] {result['id']} expected={result['expected']} selected={result['selected']}", flush=True)

    report = {
        "total": len(results),
        "passed": sum(bool(item["passed"]) for item in results),
        "failed": sum(not bool(item["passed"]) for item in results),
        "results": results,
    }
    if args.output:
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in ("total", "passed", "failed")}, ensure_ascii=False))
    return 0 if report["failed"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
