"""Versioned browser-use replay artifacts. No DOM executor or generated Python."""
import copy
import hashlib
import json
from urllib.parse import urlsplit, urlunsplit

ENGINE = "browser-use/0.13.14"
REPLAY_ACTIONS = {"navigate", "click", "input", "scroll", "send_keys", "wait", "go_back", "select_dropdown", "dropdown_options", "find_elements", "search_page", "find_text"}


def require_complete(results, expected):
    if len(results) != expected or any(r.error for r in results):
        raise RuntimeError("固定操作失败或被页面跳转打断；已停止，不自动重试提交")


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def entry_url(url):
    value = urlsplit(url)
    return urlunsplit((value.scheme, value.netloc, value.path, "", ""))


def compile_procedure(history, goal):
    if not history.get("history") or not history["history"][-1].get("result", [{}])[-1].get("success"):
        raise ValueError("Only successful, verified browser histories can be frozen")
    from .dataflow import FLOW_ACTIONS
    if any(set(action) & FLOW_ACTIONS for step in history['history']
           for action in (step.get('model_output') or {}).get('action', [])):
        from .procedure_v2 import compile_flow
        return compile_flow(history, goal)
    steps, parameters, bindings = [], {}, []
    for original in history["history"]:
        actions = (original.get("model_output") or {}).get("action", [])
        results = original.get("result", [])
        # Failed exploratory navigation and model-format errors did not perform a write.
        if any(r.get("error") for r in results):
            if not actions or all(set(a) == {"navigate"} for a in actions):
                continue
            raise ValueError("存在结果不明的操作，需审查后才能固化，不能自动重放")
        if not actions or all(set(a) <= {"done", "request_human"} for a in actions):
            continue
        if len(actions) != len(results):
            raise ValueError("操作与执行结果不完整，不能直接固化")
        step = copy.deepcopy(original)
        # Keep only native element matching data, not screenshots, page tabs or old claims.
        step["state"]["screenshot_path"] = None
        step["state"]["tabs"] = []
        location = urlsplit(step["state"].get("url", ""))
        step["state"]["url"] = urlunsplit((location.scheme, location.netloc, location.path, "", ""))
        step["result"] = []
        step.pop("state_message", None)
        for index, action in enumerate(step["model_output"]["action"]):
            if len(action) != 1 or next(iter(action)) not in REPLAY_ACTIONS:
                raise ValueError("包含不能稳定重放的动作，需要先整理实现：" + ",".join(action))
            name, args = next(iter(action.items()))
            if name == "navigate" and (urlsplit(args["url"]).query or args.get("new_tab")):
                raise ValueError("导航包含会话参数或新标签页，不能直接固化")
            if name == "input":
                element = step["state"]["interacted_element"][index] or {}
                if (element.get("attributes") or {}).get("type") == "password":
                    raise ValueError("登录凭证不能写入固定脚本，请改为人工登录")
                key = f"input_{len(parameters) + 1}"
                parameters[key] = args["text"]
                bindings.append({"parameter": key, "step": len(steps), "action": index, "field": "text"})
                args["text"] = ""
            # Native replay does not consume original model reasoning.
        step["model_output"] = {"action": step["model_output"]["action"]}
        steps.append(step)
    if not steps:
        raise ValueError("没有可固化的操作")
    body = {"version": 1, "engine": ENGINE, "url": entry_url(goal.url), "goal": goal.goal,
            "allowed_domains": goal.allowed_domains, "approval_mode": goal.approval_mode,
            "history": {"history": steps}, "bindings": bindings}
    return {**body, "hash": digest(body), "parameters": parameters}


def bind_procedure(bundle, parameters):
    body = {k: v for k, v in bundle.items() if k not in {"hash", "parameters"}}
    if bundle.get("engine") != ENGINE or bundle.get("hash") != digest(body):
        raise ValueError("浏览器实现版本或校验值不匹配")
    if set(parameters) != set(bundle["parameters"]):
        raise ValueError("输入字段与固化脚本不匹配")
    if any(not isinstance(v, str) or len(v) > 10000 for v in parameters.values()):
        raise ValueError("浏览器输入必须为不超过10000字符的字符串")
    history = copy.deepcopy(bundle["history"])
    for binding in bundle["bindings"]:
        history["history"][binding["step"]]["model_output"]["action"][binding["action"]][binding.get('tool', 'input')][binding["field"]] = parameters[binding["parameter"]]
    if bundle.get('version') == 2:
        from .dataflow import Values, FLOW_ACTIONS, check_action
        expected = {next(iter(step['model_output']['action'][0])): 1 for step in history['history']}
        if bundle.get('toolVersions') != expected:
            raise ValueError('固定工具版本不匹配，需重新检查实现')
        values = Values()
        for step in history['history']:
            for action in step['model_output']['action']:
                name, args = next(iter(action.items()))
                if name in FLOW_ACTIONS:
                    check_action(values, name, args)
    return history
