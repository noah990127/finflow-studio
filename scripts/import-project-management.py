#!/usr/bin/env python3
"""Import the portable project-management package through public Studio APIs.

Requires Python 3.11+ standard library only. Never imports a database or credentials.
"""
import argparse
import copy
import getpass
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def validate_package(package):
    manifest = read_json(package / "manifest.json")
    if manifest.get("formatVersion") != 1:
        raise ValueError("不支持的数据包版本")
    keys = set()
    for item in manifest["files"]:
        path = (package / item["path"]).resolve()
        if not path.is_relative_to(package.resolve()) or item["key"] in keys:
            raise ValueError("数据包路径或资源键无效")
        keys.add(item["key"])
        if hashlib.sha256(path.read_bytes()).hexdigest() != item["sha256"]:
            raise ValueError(f"文件校验失败：{item['path']}")
    definition = read_json(package / "live-workflow.json")
    if hashlib.sha256((package / "live-workflow.json").read_bytes()).hexdigest() != manifest["workflowSha256"]:
        raise ValueError("工序定义校验失败")
    for step in definition["steps"]:
        for binding in step["inputs"]:
            if binding.get("resourceKey") and binding["resourceKey"] not in keys:
                raise ValueError("工序引用了未打包的资源")
            if binding.get("resourceId"):
                raise ValueError("工序含本机资源编号，不能移植")
    ledger_entry = next(x for x in manifest["files"] if x["key"] == "output_ledger_data")
    ledger = read_json(package / ledger_entry["path"])["sheets"]
    tasks, people = ledger["任务台账"], ledger["人员容量"]
    hours = {p["姓名"]: sum(t["下周计划工时"] for t in tasks if t["负责人"] == p["姓名"]) for p in people}
    actual = {"taskCount": len({t["任务ID"] for t in tasks}),
              "capacityHours": sum(p["可用工时"] for p in people),
              "plannedHours": sum(hours.values()),
              "overloadedPeople": sum(hours[p["姓名"]] > p["可用工时"] for p in people),
              "overloadHours": sum(max(0, hours[p["姓名"]] - p["可用工时"]) for p in people)}
    if actual != manifest["expected"] or len(tasks) != actual["taskCount"]:
        raise ValueError(f"台账指标校验失败：{actual}")
    return manifest, definition


class API:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, method, path, data=None, upload=None):
        headers = {"Accept": "application/json"}
        body = None
        if upload:
            name, content, mime = upload
            boundary = uuid.uuid4().hex
            body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}"\r\n'
                    f'Content-Type: {mime}\r\n\r\n').encode() + content + f'\r\n--{boundary}--\r\n'.encode()
            headers["Content-Type"] = "multipart/form-data; boundary=" + boundary
        elif data is not None:
            body = json.dumps(data, ensure_ascii=False).encode()
            headers["Content-Type"] = "application/json"
        try:
            with self.opener.open(urllib.request.Request(self.base + path, data=body, headers=headers, method=method), timeout=180) as response:
                content = response.read()
                return json.loads(content) if content else None
        except urllib.error.HTTPError as error:
            # Do not echo responses that may contain submitted credentials or provider settings.
            raise RuntimeError(f"API {method} {path}: HTTP {error.code}；检查权限、服务日志与版本要求") from None


def snapshot_definition(live, resource_ids):
    """Render checked-in data; do not pretend to rerun the meeting analysis."""
    steps, stages, results = [], [], []
    for original in live["businessStages"]:
        stage = {k: copy.deepcopy(v) for k, v in original.items() if k not in {"stepIds", "outcomes"}}
        stage["stepIds"], stage["outcomes"] = [], []
        for step_id in original["stepIds"]:
            if step_id not in {"changes_view", "ledger_view", "project_view", "people_view", "heatmap_view", "risk_view", "load_view", "ppt"}:
                continue
            source = next(s for s in live["steps"] if s["id"] == step_id)
            is_ppt = step_id == "ppt"
            binding = {"$input": "data", "as": "json"}
            if is_ppt:
                binding["path"] = "/ppt"
            arguments = {"document": binding}
            if is_ppt:
                arguments.update(format="pptx", pptSkill="blue-professional-v2")
            steps.append({"id": step_id, "title": source["title"], "kind": "TOOL",
                "outputName": source["outputName"], "code": json.dumps({"tool": "document.render" if is_ppt else "presentation.render", "version": 1, "arguments": arguments}, ensure_ascii=False),
                "inputs": [{"alias": "data", "resourceId": resource_ids["output_report_data" if is_ppt else "output_" + step_id]}]})
            stage["stepIds"].append(step_id)
            stage["outcomes"].append({"stepId": step_id, "role": "PRIMARY"})
            results.append({"stepId": step_id, "title": source["title"], "scope": "PER_RUN"})
        stage["goal"] = "展示已备会议后资料：" + original["goal"]
        stage["acceptance"] = "核对预置成果的内容、图表与文件是否正常显示；本工序不重新分析输入。"
        stages.append(stage)
    return {"title": "项目进展看板", "inputGuide": "本工序展示2026年11月26日会议后的已备成果。新会议与新台账需使用另行导入的「周会任务更新与风险汇报」工序重新分析。",
            "steps": steps, "businessStages": stages, "results": results}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:5173/api")
    parser.add_argument("--username", default=os.environ.get("FINFLOW_IMPORT_USERNAME", "default"))
    parser.add_argument("--package", type=Path, default=ROOT / "examples/project-management")
    parser.add_argument("--state", type=Path, default=ROOT / ".run/project-management-import.json")
    parser.add_argument("--project-name")
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--include-live", action="store_true", help="额外发布完整分析工序，不自动调用模型")
    parser.add_argument("--run-live", action="store_true", help="额外运行完整分析工序，会调用目标环境的模型")
    parser.add_argument("--retry-failed", action="store_true", help="明确重试上次失败的工序，不能修复缺失依赖")
    parser.add_argument("--wait-seconds", type=int, default=1800)
    args = parser.parse_args(argv)
    manifest, live = validate_package(args.package)
    print("数据包校验通过：17项任务、6人、176/184小时、3人超负荷。", flush=True)
    if args.validate_only:
        return 0
    parsed = urllib.parse.urlparse(args.base_url)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc or parsed.username or parsed.query or parsed.fragment:
        raise ValueError("base-url必须是无凭据、无查询参数的HTTP(S) API地址")
    if parsed.scheme == "http" and parsed.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise ValueError("远程导入请使用HTTPS；也可通过SSH隧道访问localhost")
    api = API(args.base_url)
    password = os.environ.get("FINFLOW_IMPORT_PASSWORD") or getpass.getpass("工作台登录密码：")
    api.request("POST", "/auth/login", {"username": args.username, "password": password})
    package_hash = hashlib.sha256((args.package / "manifest.json").read_bytes()).hexdigest()
    identity = {"baseUrl": api.base, "username": args.username, "packageHash": package_hash}
    state = read_json(args.state) if args.state.exists() else {**identity, "resources": {}, "works": {}}
    if any(state.get(k) != v for k, v in identity.items()):
        raise ValueError("状态文件属于不同服务器、用户或数据包；请使用另一个--state文件")

    def save():
        args.state.parent.mkdir(parents=True, exist_ok=True)
        temp = args.state.with_suffix(".tmp")
        temp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
        os.chmod(temp, 0o600)
        temp.replace(args.state)

    if not state.get("projectId"):
        name = args.project_name or manifest["project"]["name"]
        if any(p["name"] == name for p in api.request("GET", "/projects")):
            raise ValueError("同名项目已存在但状态文件未记录；请恢复状态文件，或明确指定新的--project-name和--state")
        project = api.request("POST", "/projects", {**manifest["project"], "name": name})
        state["projectId"] = project["id"]
        save()
    project_id = state["projectId"]
    available = {r["id"] for r in api.request("GET", f"/projects/{project_id}/workspace")["resources"]}
    for item in manifest["files"]:
        if item["key"] in state["resources"]:
            if state["resources"][item["key"]] not in available:
                raise ValueError("已导入资源被删除，请使用新项目导入；不会悄悄改写现有工序")
            continue
        result = api.request("POST", f"/projects/{project_id}/files", upload=(item["name"], (args.package / item["path"]).read_bytes(), item["mediaType"]))
        state["resources"][item["key"]] = result["id"]
        save()
    print(f"项目资料已就绪：{project_id}", flush=True)

    def install(key, definition, execute):
        work = state["works"].setdefault(key, {})
        if not work.get("sessionId"):
            work["sessionId"] = api.request("POST", f"/projects/{project_id}/assistant/sessions", {"title": definition["title"]})["id"]
            save()
        path = f"/projects/{project_id}/works/{work['sessionId']}/implementation"
        if not work.get("definitionId"):
            if key == "live" and os.environ.get("FINFLOW_IMPORT_MODEL_BASE_URL"):
                model = os.environ.get("FINFLOW_IMPORT_MODEL", "")
                model_key = os.environ.get("FINFLOW_IMPORT_MODEL_API_KEY", "")
                if not model or not model_key:
                    raise ValueError("自定义模型需要同时设置FINFLOW_IMPORT_MODEL和FINFLOW_IMPORT_MODEL_API_KEY")
                api.request("PUT", f"/assistant/sessions/{work['sessionId']}/model", {
                    "mode": "CUSTOM", "baseUrl": os.environ["FINFLOW_IMPORT_MODEL_BASE_URL"],
                    "model": model, "apiKey": model_key})
            current = api.request("GET", path)["definition"]
            if current is not None:
                # Recover a successful publish whose response/state write was interrupted.
                original = copy.deepcopy(current["implementation"])
                original.pop("modelSnapshot", None)
                def normalized(value):
                    if isinstance(value, dict):
                        return {k: normalized(v) for k, v in value.items() if v is not None}
                    if isinstance(value, list):
                        return [normalized(v) for v in value]
                    return value
                if normalized(original) != normalized(definition):
                    raise ValueError("目标工序已被修改，停止导入以保护现有工作")
                published = current
            else:
                published = api.request("PUT", path, {"implementation": definition, "expected_version": 0})
            work["definitionId"] = published["id"]
            save()
        if not execute:
            print(f"已发布：{definition['title']}；尚未执行。", flush=True)
            return
        current = api.request("GET", path)
        run = next((r for r in current["runs"] if r["id"] == work.get("runId")), None)
        if run and run["status"] in {"FAILED", "CANCELED", "CANCELLED"}:
            if not args.retry_failed:
                raise RuntimeError("上次执行失败；检查服务日志后使用--retry-failed，不会自动重复执行")
            work.pop("requestId", None)
            work.pop("runId", None)
            run = None
        if not run:
            work.setdefault("requestId", str(uuid.uuid4()))
            save()
            run = api.request("POST", path + "/runs", {"definitionId": work["definitionId"], "requestId": work["requestId"]})
            work["runId"] = run["id"]
            save()
        deadline, last = time.monotonic() + args.wait_seconds, None
        while True:
            current = api.request("GET", path)
            run = next(r for r in current["runs"] if r["id"] == work["runId"])
            progress = (run["status"], sum(s["status"] == "SUCCEEDED" for s in run["stages"]))
            if progress != last:
                print(f"{definition['title']}：{progress[0]}，{progress[1]}/{len(run['stages'])}", flush=True)
                last = progress
            if run["status"] == "SUCCEEDED":
                work["status"] = run["status"]
                save()
                return
            if run["status"] in {"FAILED", "CANCELED", "CANCELLED"}:
                raise RuntimeError("工序未完成；检查工作台技术详情和服务日志，已有产物保留")
            if time.monotonic() >= deadline:
                raise TimeoutError("等待超时，服务可能仍在运行；保留状态文件并重新运行同一命令以继续观察")
            time.sleep(5)

    install("snapshot", snapshot_definition(live, state["resources"]), True)
    if args.include_live or args.run_live:
        rebound = copy.deepcopy(live)
        for step in rebound["steps"]:
            for binding in step["inputs"]:
                if binding.get("resourceKey"):
                    binding["resourceId"] = state["resources"][binding.pop("resourceKey")]
        install("live", rebound, args.run_live)
    print("导入完成。打开项目，在第2步的成果下拉框切换WBS、人员分工和热力图。", flush=True)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, RuntimeError, TimeoutError, OSError, urllib.error.URLError) as error:
        print(f"导入停止：{error}", file=sys.stderr)
        sys.exit(1)
