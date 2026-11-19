"""Bounded path discovery and evidence-aware, three-valued scenario replay."""
import operator
from collections import defaultdict

from .derivations_graph import build_candidates, key, process_gaps
from .derivations_report import literal

PATH_LIMIT = 64
NODE_LIMIT = 100
SEGMENT_SIZE = 10
VISIT_LIMIT = PATH_LIMIT * NODE_LIMIT
KINDS = {"normal": "正常", "exception": "异常", "boundary": "边界"}


def path_catalog(flow):
    adjacency = defaultdict(list)
    for branch in flow["branches"]:
        adjacency[branch["from_step_id"]].append(branch)
    starts = [s["step_id"] for s in flow["steps"] if s.get("kind") == "start"]
    ends = {s["step_id"] for s in flow["steps"] if s.get("kind") == "end"}
    paths, limits = [], set()
    visits = 0

    def discover(nodes, edges):
        nonlocal visits
        if "数量上限" in limits or "搜索上限" in limits:
            return
        visits += 1
        if visits > VISIT_LIMIT:
            limits.add("搜索上限")
            return
        node = nodes[-1]
        if node in ends:
            if len(paths) == PATH_LIMIT:
                limits.add("数量上限")
                return
            paths.append({"path_id": key("path", flow["process_id"], nodes, edges),
                          "steps": nodes, "branches": edges})
            return
        if len(nodes) >= NODE_LIMIT:
            limits.add("长度上限")
            return
        for edge in adjacency[node]:
            dest = edge["to_step_id"]
            if dest in nodes:
                limits.add("循环路径")
            else:
                discover([*nodes, dest], [*edges, edge["branch_id"]])
            if "数量上限" in limits or "搜索上限" in limits:
                break

    for start in starts:
        discover([start], [])
        if "数量上限" in limits or "搜索上限" in limits:
            break
    return paths, sorted(limits)


def numeric(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool)


def equal(a, b):
    if numeric(a) and numeric(b):
        return a == b
    if type(a) != type(b):
        return False
    if isinstance(a, list):
        return len(a) == len(b) and all(equal(x, y) for x, y in zip(a, b))
    if isinstance(a, dict):
        return a.keys() == b.keys() and all(equal(v, b[k]) for k, v in a.items())
    return a == b


def evaluate(condition, inputs):
    """None means unknown, unit mismatch, missing observation, or opaque expression."""
    op = condition.get("operator")
    if op in ("any", "all"):
        clauses = condition.get("clauses", [])
        if len(clauses) < 2:
            return None
        values = [evaluate(c, inputs) for c in clauses]
        if op == "any":
            return True if True in values else (False if all(v is False for v in values) else None)
        return False if False in values else (True if all(v is True for v in values) else None)
    if op == "always":
        return True if condition.get("expression", "").strip() else None
    field = condition.get("field")
    if op != "predicate" or field not in inputs:
        return None
    actual, expected = inputs[field], condition.get("value")
    comparison = condition.get("comparator")
    if isinstance(actual, dict) and "value" in actual:
        if actual.get("unit", "") != condition.get("unit", ""):
            return None
        actual = actual["value"]
    if comparison in ("exists", "not_exists"):
        return (actual is not None) == (comparison == "exists")
    if actual is None or expected is None:
        return None
    if comparison in ("gt", "gte", "lt", "lte"):
        if not numeric(actual) or not numeric(expected) or not condition.get("unit"):
            return None
        return {"gt": operator.gt, "gte": operator.ge, "lt": operator.lt, "lte": operator.le}[comparison](actual, expected)
    if comparison in ("eq", "neq"):
        return equal(actual, expected) == (comparison == "eq")
    if comparison in ("in", "not_in") and isinstance(expected, list) and expected:
        return any(equal(actual, x) for x in expected) == (comparison == "in")
    return None


def tested_boundary(condition):
    if condition.get("comparator") in ("gt", "gte", "lt", "lte"):
        return True
    return any(tested_boundary(c) for c in condition.get("clauses", []))


def replay(case, flow, paths, catalog, gaps, rules):
    scenario = {"case_id": case["case_id"], "name": case.get("name", ""), "kind": case.get("kind", "normal"),
                "status": "incomplete", "inputs": case.get("inputs", {}), "expected_outputs": case.get("expected_outputs", {}),
                "path_id": None, "segments": [], "findings": []}

    def stop(code, message):
        scenario["findings"].append({"code": code, "message": message, "case_id": case["case_id"],
                                     "process_id": flow["process_id"]})
        return scenario

    if gaps:
        return stop("blocking_gap", "流程仍有阻断缺口：" + "、".join(g["question"] for g in gaps))
    if case.get("status") != "defined" or case.get("assumptions"):
        return stop("unconfirmed_case", "验收样例或输入假设尚待确认")
    expected = case.get("expected_path", [])
    matches = [p for p in paths if p["steps"] == expected or [p["path_id"]] == expected]
    if not expected or not matches:
        return stop("unexpected_path", "样例没有指定目录中的完整路径")
    if not case.get("expected_outputs"):
        return stop("missing_expectation", "样例缺少可供独立核对的预期产出")
    expected = matches[0]["steps"]
    step_map = {s["step_id"]: s for s in flow["steps"]}
    pid = flow["process_id"]
    logic = catalog.get(("Logic", pid, pid))
    if not logic:
        return stop("missing_candidate", "流程缺少逻辑入口或该入口证据已经变化")
    outgoing = defaultdict(list)
    for b in flow["branches"]:
        outgoing[b["from_step_id"]].append(b)
    chosen = []
    used_rules = set()
    for i, sid in enumerate(expected):
        s = step_map[sid]
        kind = s.get("kind")
        node_candidate = logic
        if kind in ("step", "decision"):
            node_candidate = catalog.get(("Action" if kind == "step" else "Function", pid, sid))
            if not node_candidate:
                return stop("missing_candidate", f"步骤「{s['name']}」缺少有效的模型候选或证据")
        if i % SEGMENT_SIZE == 0:
            scenario["segments"].append([])
        trace = {"step_id": sid, "name": s["name"], "candidate_id": node_candidate["candidate_id"],
                 "evidence": node_candidate["evidence"], "rules": []}
        scenario["segments"][-1].append(trace)
        rule_ids = list(s.get("rule_ids", []))
        if i < len(expected) - 1:
            options = [(b, evaluate(b.get("condition", {}), case["inputs"])) for b in outgoing[sid]]
            positive = [b for b, result in options if result is True]
            if any(result is None for _, result in options):
                return stop("unresolved_branch", f"步骤「{s['name']}」有条件缺少输入、适用单位或可判定依据")
            if len(positive) != 1:
                return stop("ambiguous_branch", f"步骤「{s['name']}」存在多个适用分支或没有适用分支")
            edge = positive[0]
            if edge["to_step_id"] != expected[i + 1]:
                return stop("branch_deviation", f"步骤「{s['name']}」的实际分支偏离样例路径")
            chosen.append(edge["branch_id"])
            trace["branch_id"] = edge["branch_id"]
            rule_ids.extend(edge.get("rule_ids", []))
        for rid in dict.fromkeys(rule_ids):
            rule = rules.get(rid)
            candidate = catalog.get(("Function", "", rid))
            if not rule or not candidate:
                return stop("missing_rule", f"步骤「{s['name']}」缺少规则候选 {rid}")
            guard = evaluate(rule.get("condition", {}), case["inputs"])
            trace["rules"].append({"rule_id": rid, "applicable": guard, "candidate_id": candidate["candidate_id"]})
            if guard is None:
                return stop("unresolved_rule", f"样例不足以判断「{rule['name']}」是否适用")
            if guard is False and rid in case.get("rule_ids", []):
                return stop("rule_deviation", f"样例要求适用的「{rule['name']}」条件未成立")
            if guard:
                used_rules.add(rid)
    exact = next((p for p in matches if p["branches"] == chosen), None)
    if not exact or set(case.get("rule_ids", [])) - used_rules:
        return stop("uncovered_rule_or_path", "样例引用的规则或分支未在完整路径中获得验证")
    if set(case["expected_outputs"]) - set(flow.get("outputs", [])):
        return stop("unbound_expected_output", "样例的预期输出未在流程输出中声明")
    scenario["path_id"] = exact["path_id"]
    # The ontology is a reviewable data structure, not an executable business engine.
    # A matched path cannot serve as an observation of downstream financial results.
    scenario["status"] = "path_verified"
    return scenario


def walkthrough(spec, flows, candidates):
    expected = {c["candidate_id"]: c for c in build_candidates(spec, flows)}
    actual = {c["candidate_id"]: c for c in candidates}
    if len(candidates) != len(actual):
        raise ValueError("候选标识重复，无法校核")
    catalog = {}
    for cid, current in actual.items():
        authorized = expected.get(cid)
        if authorized and current.get("review_status") != "rejected" and all(current.get(k) == authorized[k] for k in
                    ("kind", "process_id", "key", "definition", "evidence")):
            catalog[(authorized["kind"], authorized["process_id"], authorized["key"])] = authorized
    rules = {r["rule_id"]: r for r in spec.get("rules", [])}
    proc = {p["process_id"]: p for p in spec.get("processes", [])}
    findings, processes, report = [], [], ["# 建模校核报告", ""]
    for flow in flows:
        pid = flow["process_id"]
        paths, reasons = path_catalog(flow)
        cases = [c for c in spec.get("acceptance_cases", []) if pid in c.get("process_ids", [])]
        gaps = [g for g in process_gaps(spec, proc[pid]) if g.get("blocking", True)]
        scenarios = []
        required = {"normal"}
        if any(b.get("is_exception") for b in flow["branches"]):
            required.add("exception")
        if any(tested_boundary(i.get("condition", {})) for i in [
                *flow["branches"], *[rules[r] for r in proc[pid].get("rule_ids", [])]]):
            required.add("boundary")
        for kind in KINDS:
            matching = [c for c in cases if c.get("kind", "normal") == kind]
            if not matching and kind in required:
                findings.append({"code": "missing_case", "message": f"尚缺{KINDS[kind]}验收场景",
                                 "case_id": "", "process_id": pid})
            for c in matching:
                scenario = replay(c, flow, paths, catalog, gaps, rules)
                scenarios.append(scenario)
                findings.extend(scenario["findings"])
        seen = {s["path_id"] for s in scenarios if s["status"] == "path_verified"}
        uncovered = [p["path_id"] for p in paths if p["path_id"] not in seen]
        if uncovered:
            findings.append({"code": "uncovered_paths", "message": f"{len(uncovered)} 条已列出的路径尚无匹配样例",
                             "case_id": "", "process_id": pid})
        processes.append({"process_id": pid, "name": flow["name"], "paths": paths, "scenarios": scenarios,
                          "truncated": bool(reasons), "limit_reasons": reasons, "uncovered_paths": uncovered})
        report += ["", "## " + literal(flow["name"]), "", f"目录中有 {len(paths)} 条路径，样例已核对 {len(seen)} 条。"]
        if reasons:
            report.append("路径枚举未覆盖全部情况：" + "、".join(reasons) + "。")
        for s in scenarios:
            status = "路径匹配，业务产出待独立核对" if s["status"] == "path_verified" else "未通过路径核对"
            report.append(f"- {literal(s['name'])}：{status}")
        for f in findings:
            if f["process_id"] == pid:
                report.append("- " + literal(f["message"]))
    return {"status": "incomplete" if findings or any(p["truncated"] for p in processes) else "needs_review",
            "processes": processes, "findings": findings, "modeling_gap_report": "\n".join(report) + "\n"}
