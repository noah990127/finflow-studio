"""Small stdio MCP transport for the authenticated Java tool gateway.

No planning or model calls live here. Tokens are read from an owner-only run file,
never from model-visible tool arguments. Stdout is reserved for JSON-RPC.
"""
import json
import sys
from pathlib import Path

import httpx


class GatewayBridge:
    def __init__(self, config, client, pending_path):
        self.config = config
        self.client = client
        self.pending_path = pending_path
        self.calls = 0
        self.capabilities = {c["id"]: c for c in config["capabilities"]}

    def tools(self):
        return [
            {"name": "catalog", "description": "Find platform tools and their exact input schemas.",
             "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}}}},
            {"name": "execute", "description": "Execute a discovered platform tool. Use work.update for business stages and versioned artifact references.",
             "inputSchema": {"type": "object", "properties": {"tool": {"type": "string"}, "arguments": {"type": "object"}}, "required": ["tool", "arguments"]}},
            {"name": "workflow_status", "description": "Check an already started workflow run, including final artifact references.",
             "inputSchema": {"type": "object", "properties": {"run_id": {"type": "string"}}, "required": ["run_id"]}},
        ]

    def call(self, name, arguments):
        if name == "catalog":
            query = str(arguments.get("query", "")).lower()
            return [c for c in self.capabilities.values() if not query or query in json.dumps(c, ensure_ascii=False).lower()]
        self.calls += 1
        if self.calls > self.config["max_tool_calls"]:
            return {"success": False, "error": "Tool budget exhausted; report remaining work."}
        base = self.config["gateway_url"].rstrip("/") + "/internal/assistant/runs/" + self.config["run_id"]
        headers = {"X-Agent-Gateway-Token": self.config["gateway_token"]}
        if name == "workflow_status":
            run_id = str(arguments.get("run_id", ""))
            if not run_id or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_" for c in run_id):
                raise ValueError("Invalid workflow run identifier")
            response = self.client.get(base + "/workflow-runs/" + run_id, headers=headers)
        elif name == "execute":
            capability = self.capabilities.get(arguments.get("tool"))
            if capability is None:
                raise ValueError("Unknown platform tool")
            response = self.client.post(base + "/tools/call", headers=headers, json=arguments)
        else:
            raise ValueError("Unknown MCP tool")
        response.raise_for_status()
        result = response.json()
        if name == "execute" and result.get("failureType") == "AUTHORIZATION":
            self.pending_path.write_text(json.dumps({"toolName": arguments["tool"], "arguments": arguments["arguments"]}), encoding="utf-8")
        return result


def dispatch(bridge, message):
    method = message.get("method")
    if method == "initialize":
        version = message.get("params", {}).get("protocolVersion", "2024-11-05")
        return {"protocolVersion": version if version in {"2024-11-05", "2025-03-26", "2025-06-18"} else "2024-11-05",
                "capabilities": {"tools": {}}, "serverInfo": {"name": "finflow", "version": "1.0.0"}}
    if method == "ping":
        return {}
    if method == "tools/list":
        return {"tools": bridge.tools()}
    if method == "tools/call":
        params = message.get("params", {})
        try:
            value = bridge.call(params.get("name"), params.get("arguments", {}))
            return {"content": [{"type": "text", "text": json.dumps(value, ensure_ascii=False)}]}
        except (ValueError, httpx.HTTPError):
            return {"isError": True, "content": [{"type": "text", "text": "Platform call failed. Check arguments and platform availability; do not assume the operation succeeded."}]}
    raise ValueError("Unsupported method")


def main():
    path = Path(sys.argv[1])
    config = json.loads(path.read_text(encoding="utf-8"))
    with httpx.Client(timeout=config.get("tool_timeout_seconds", 600), trust_env=False) as client:
        bridge = GatewayBridge(config, client, path.parent / "approval.json")
        for line in sys.stdin:
            try:
                message = json.loads(line)
                if "id" not in message:
                    continue
                try:
                    activity = path.parent / "activity.json"
                    activity.write_text(json.dumps({"state": "running"}))
                    try:
                        response = {"jsonrpc": "2.0", "id": message["id"], "result": dispatch(bridge, message)}
                    finally:
                        activity.write_text(json.dumps({"state": "finished"}))
                except ValueError:
                    response = {"jsonrpc": "2.0", "id": message["id"], "error": {"code": -32601, "message": "Unsupported method"}}
            except (ValueError, TypeError):
                response = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Invalid JSON-RPC"}}
            print(json.dumps(response, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
