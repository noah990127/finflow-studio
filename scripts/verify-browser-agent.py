"""Real browser-use + model smoke test against the loopback-only form fixture.

Start the fixture on 8767 and the Browser Agent on 8003 first. Does not touch mail.
"""
import asyncio
import json
from pathlib import Path
from uuid import uuid4

import httpx
from dotenv import dotenv_values


async def main():
    root = Path(__file__).resolve().parents[1]
    config = dotenv_values(root / ".env")
    headers = {"Authorization": "Bearer " + config["BROWSER_AGENT_TOKEN"],
               "X-Finflow-Owner": "browser-agent-regression"}
    task_id = None
    async with httpx.AsyncClient(trust_env=False, timeout=30) as client:
        async def rpc(method, params):
            response = await client.post("http://127.0.0.1:8003/", headers=headers,
                json={"jsonrpc": "2.0", "id": str(uuid4()), "method": method, "params": params})
            response.raise_for_status()
            value = response.json()
            if "error" in value:
                raise RuntimeError(value["error"])
            return value["result"]

        baseline = (await client.get("http://127.0.0.1:8767/result")).json()["submissions"]
        goal = "在当前本地测试页面填写事项为自动化验证、金额为128、备注为仅用于本地验证。点击登记一次，确认出现登记成功并核对三项内容。不要重复登记。"
        try:
            task = await rpc("message/send", {"message": {
                "kind": "message", "role": "user", "messageId": str(uuid4()),
                "parts": [{"kind": "text", "text": goal}],
                "metadata": {"approved": True, "requestKey": str(uuid4()),
                             "browserGoal": {"url": "http://127.0.0.1:8767", "goal": goal, "human_login": True}},
            }})
            task_id = task["id"]
            resumed = False
            last = None
            async with asyncio.timeout(300):
                while True:
                    task = await rpc("tasks/get", {"id": task_id})
                    state = task["status"]["state"]
                    if state != last:
                        print("Browser task:", state, flush=True)
                        last = state
                    if state == "input-required" and not resumed:
                        # This fixture has no credentials. Simulate the user's return once.
                        await rpc("message/send", {"message": {
                            "kind": "message", "role": "user", "messageId": str(uuid4()), "taskId": task_id,
                            "parts": [{"kind": "text", "text": "本地测试页已打开，继续"}],
                            "metadata": {"promptId": task["metadata"]["promptId"]},
                        }})
                        resumed = True
                    if state in {"completed", "failed", "canceled", "rejected"}:
                        break
                    await asyncio.sleep(2)
            actual = (await client.get("http://127.0.0.1:8767/result")).json()["submissions"]
            assert state == "completed", task["status"]
            assert actual[len(baseline):] == [{"name": "自动化验证", "amount": "128", "note": "仅用于本地验证"}], actual
            source_id = task_id
            procedure = task["metadata"]["procedure"]
            changes = {"自动化验证": "重放验证", "128": "256", "仅用于本地验证": "本轮新输入"}
            parameters = {key: changes[value] for key, value in procedure["parameters"].items()}
            replay = await rpc("message/send", {"message": {
                "kind": "message", "role": "user", "messageId": str(uuid4()),
                "parts": [{"kind": "text", "text": "用新输入重放本地表单"}],
                "metadata": {"approved": True, "requestKey": str(uuid4()), "browserGoal": {
                    "url": "http://127.0.0.1:8767", "goal": "本地回放", "replay_source": source_id,
                    "replay_hash": procedure["hash"], "parameters": parameters}},
            }})
            task_id = replay["id"]
            handled = set()
            async with asyncio.timeout(180):
                while True:
                    replay = await rpc("tasks/get", {"id": task_id})
                    state = replay["status"]["state"]
                    if state == "input-required" and replay["metadata"]["promptId"] not in handled:
                        prompt = replay["metadata"]["promptId"]
                        if prompt > 1:
                            recorded = (await client.get("http://127.0.0.1:8767/result")).json()["submissions"]
                            assert recorded[len(actual):] == [{"name": "重放验证", "amount": "256", "note": "本轮新输入"}], recorded
                        handled.add(prompt)
                        await rpc("message/send", {"message": {"kind": "message", "role": "user", "messageId": str(uuid4()),
                            "taskId": task_id, "parts": [{"kind": "text", "text": "已核验，继续"}], "metadata": {"promptId": prompt}}})
                    if state in {"completed", "failed", "canceled", "rejected"}: break
                    await asyncio.sleep(1)
            assert state == "completed", replay["status"]
            assert len(handled) == 2
            report = {"taskId": task_id, "engine": "browser-use", "state": state,
                      "handoffResumed": resumed, "newSubmissionCount": len(actual) - len(baseline), "valuesVerified": True,
                      "replayWithChangedInputs": True, "sourceTask": source_id, "implementationHash": procedure["hash"]}
            (root / ".run" / "browser-agent-verification.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
            print(json.dumps(report, ensure_ascii=False), flush=True)
        except BaseException:
            if task_id:
                try:
                    await rpc("tasks/cancel", {"id": task_id})
                except Exception:
                    pass
            raise


if __name__ == "__main__":
    asyncio.run(main())
