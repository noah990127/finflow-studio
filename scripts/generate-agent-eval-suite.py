#!/usr/bin/env python3
from __future__ import annotations

import argparse
import http.cookiejar
import json
import os
import sys
import urllib.request
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "worker-python"))

from app.evals import NaturalLanguageScenarioGenerator  # noqa: E402
from app.tools.contracts import ToolDefinition  # noqa: E402


def authenticated_opener(base_url: str, username: str, password: str) -> urllib.request.OpenerDirector:
    cookies = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(cookies))
    body = json.dumps({"username": username, "password": password}).encode("utf-8")
    request = urllib.request.Request(
        base_url.rstrip("/") + "/api/auth/login",
        data=body,
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with opener.open(request, timeout=10):
        pass
    return opener


def read_catalog(opener: urllib.request.OpenerDirector, url: str) -> list[dict[str, Any]]:
    with opener.open(url, timeout=10) as response:
        return json.load(response)


def normalize_tool(raw: dict[str, Any]) -> ToolDefinition:
    risk_name = str(raw.get("risk", "READ_ONLY"))
    if raw.get("id") == "deliverable.export":
        risk = "export"
    elif risk_name == "DESTRUCTIVE_OR_EXTERNAL":
        risk = "destructive"
    elif risk_name == "READ_ONLY":
        risk = "read"
    else:
        risk = "write"
    return ToolDefinition(
        id=str(raw["id"]),
        category=str(raw.get("category", "workspace")),
        title=str(raw.get("title", raw["id"])),
        description=str(raw.get("description", raw["id"])),
        risk=risk,
        requires_confirmation=bool(raw.get("requiresConfirmation", risk != "read")),
        arguments=[str(value) for value in raw.get("arguments", [])],
        input_schema=dict(raw.get("inputSchema", {})),
        output_schema=dict(raw.get("outputSchema", {})),
    )


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Generate held-out natural-language Agent scenarios from the live tool manifest."
    )
    parser.add_argument("--base-url", default="http://127.0.0.1:8080")
    parser.add_argument("--count", type=int, default=0)
    parser.add_argument("--seed", type=int, default=20260904)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--username", default=os.getenv("FINFLOW_EVAL_USERNAME", "default"))
    parser.add_argument("--password", default=os.getenv("FINFLOW_EVAL_PASSWORD", os.getenv("FINFLOW_AUTH_DEFAULT_PASSWORD", "Pr0d1234")))
    args = parser.parse_args()

    opener = authenticated_opener(args.base_url, args.username, args.password)
    catalog = read_catalog(opener, args.base_url.rstrip("/") + "/api/assistant/tools")
    tools = [normalize_tool(item) for item in catalog if item.get("id") != "deliverable.create"]
    count = args.count or max(80, len(tools) * 3)
    scenarios = NaturalLanguageScenarioGenerator(tools, args.seed).generate(count)
    lines = [json.dumps({
        "id": item.id,
        "seed": item.seed,
        "persona": item.persona,
        "style": item.style,
        "turns": item.turns,
        "expected_tools": sorted(item.expected_tools),
        "expected_categories": sorted(item.expected_categories),
        "execution_mode": item.execution_mode,
        "mutates_workspace": item.mutates_workspace,
        "write_tools": sorted(item.write_tools),
        "requires_recovery": item.requires_recovery,
    }, ensure_ascii=False) for item in scenarios]
    content = "\n".join(lines) + "\n"
    if args.output:
        args.output.write_text(content, encoding="utf-8")
        print(f"Generated {len(scenarios)} scenarios at {args.output}")
    else:
        sys.stdout.write(content)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
