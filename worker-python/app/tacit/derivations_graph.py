"""Derive diagrams and reviewable ontology patches from the ledger's graph."""
import hashlib
import json
import unicodedata
from collections import defaultdict

from .derivations_report import SUPPORTED, condition_text


def key(kind, *parts):
    encoded = json.dumps(parts, ensure_ascii=False, separators=(",", ":")).encode()
    return f"{kind}_{hashlib.sha256(encoded).hexdigest()[:24]}"


def label(value):
    # Preserve business wording but remove all Mermaid grammar and control characters
    # from quoted labels. Only program-generated identifiers ever enter the grammar.
    def char(c):
        if c.isspace() or unicodedata.category(c).startswith("C"):
            return " "
        if c.isascii() and not c.isalnum() and c != " ":
            return chr(ord(c) + 0xFEE0)
        return c
    return " ".join("".join(char(c) for c in value).split())


def reachable(starts, edges):
    seen = set(starts)
    todo = list(starts)
    while todo:
        for dest in edges.get(todo.pop(), []):
            if dest not in seen:
                seen.add(dest)
                todo.append(dest)
    return seen


def checked_graph(p, rules):
    steps = {s["step_id"]: s for s in p.get("steps", [])}
    branches = p.get("branches", [])
    if len(steps) != len(p.get("steps", [])) or "" in steps or not steps:
        raise ValueError(f"{p['process_id']}：流程步骤 ID 缺失或重复")
    if len({b["branch_id"] for b in branches}) != len(branches) or any(not b["branch_id"] for b in branches):
        raise ValueError(f"{p['process_id']}：流程分支 ID 缺失或重复")
    allowed = set(p.get("rule_ids", []))
    if allowed.difference(rules):
        raise ValueError(f"{p['process_id']}：流程包含不存在或已排除的规则")
    outgoing, incoming = defaultdict(list), defaultdict(list)
    for b in branches:
        src, dst = b["from_step_id"], b["to_step_id"]
        if src not in steps or dst not in steps:
            raise ValueError(f"{p['process_id']}：分支 {b['branch_id']} 的端点不存在")
        outgoing[src].append(dst)
        incoming[dst].append(src)
    starts = {k for k, s in steps.items() if s.get("kind") == "start"}
    ends = {k for k, s in steps.items() if s.get("kind") == "end"}
    if not starts or not ends or any(incoming[s] for s in starts) or any(outgoing[s] for s in ends):
        raise ValueError(f"{p['process_id']}：起点和终点必须清楚且方向正确")
    if reachable(starts, outgoing) != set(steps) or reachable(ends, incoming) != set(steps):
        raise ValueError(f"{p['process_id']}：有步骤无法从起点到达，或无法通向终点")
    for s in steps.values():
        if not s.get("name", "").strip():
            raise ValueError(f"{p['process_id']}：步骤缺少名称")
        if s.get("kind") == "decision" and len(outgoing[s["step_id"]]) < 2:
            raise ValueError(f"{p['process_id']}：判断节点 {s['name']} 需要列明至少两个分支")
    used = {r for i in [*steps.values(), *branches] for r in i.get("rule_ids", [])}
    if used != allowed:
        raise ValueError(f"{p['process_id']}：步骤和分支承接的规则与流程规则不一致")
    for direction in ("inputs", "outputs"):
        bound = {v for s in steps.values() for v in s.get(direction, [])}
        if set(p.get(direction, [])) - bound:
            raise ValueError(f"{p['process_id']}：流程{('输入' if direction == 'inputs' else '输出')}未绑定具体步骤")
    return steps, branches


def process_gaps(spec, p):
    rules = set(p.get("rule_ids", []))
    return [g for g in spec.get("gaps", []) if g.get("status") != "resolved" and
            (p["process_id"] in g.get("process_ids", []) or rules.intersection(g.get("rule_ids", [])) or
             (g.get("blocking", True) and not any(g.get(k) for k in (
                 "process_ids", "rule_ids", "data_object_ids", "data_table_ids"))))]


def generate_flows(spec):
    flows = []
    rules = {r["rule_id"]: r for r in spec.get("rules", []) if r.get("process_disposition") != "excluded"}
    for p in spec.get("processes", []):
        if p.get("disposition") == "excluded":
            continue
        steps, branches = checked_graph(p, rules)
        gaps = process_gaps(spec, p)
        names = {sid: key("n", p["process_id"], sid) for sid in steps}
        lines = ["flowchart TD"]
        for sid, s in steps.items():
            text = s["name"]
            if s.get("kind") == "start" and (gaps or p.get("status") not in SUPPORTED):
                text += "（待确认）"
            shapes = {"decision": ('{"', '"}'), "start": ('(["', '"])'), "end": ('(["', '"])')}
            before, after = shapes.get(s.get("kind"), ('["', '"]'))
            lines.append(f"  {names[sid]}{before}{label(text)}{after}")
        for b in branches:
            cond = condition_text(b.get("condition", {}))
            qualifiers = ["异常"] if b.get("is_exception") else []
            if b.get("description") and b["description"] != cond:
                qualifiers.append(b["description"])
            qualifiers.append(cond)
            lines.append(f'  {names[b["from_step_id"]]} -->|"{label(" · ".join(qualifiers))}"| {names[b["to_step_id"]]}')
        flows.append({"process_id": p["process_id"], "name": p.get("name", ""), "inputs": p.get("inputs", []),
                      "outputs": p.get("outputs", []), "steps": list(steps.values()), "branches": branches,
                      "content": "\n".join(lines), "gap_ids": [g["gap_id"] for g in gaps], "status": "draft"})
    if not flows:
        raise ValueError("台账尚无可绘制的流程")
    return flows


def check_flow_versions(spec, flows):
    expected = generate_flows(spec)
    supplied = {f.get("process_id"): f for f in flows}
    if len(flows) != len(expected) or len(flows) != len(supplied):
        raise ValueError("流程集合已经变化，请重新生成并审核")
    for f in expected:
        current = supplied.get(f["process_id"], {})
        if any(current.get(k) != v for k, v in f.items() if k != "status"):
            raise ValueError(f"{f['name']} 的流程与台账不一致，请重新生成并审核")


def build_candidates(spec, flows):
    # Actor identity, upstream revision and approval are checked by the command service.
    check_flow_versions(spec, flows)
    entries = []
    sources = {s["source_id"]: s for s in spec.get("sources", []) if s.get("disposition") != "excluded"}

    def emit(kind, pid, record_id, name, owner, definition):
        evidence = owner.get("evidence", [])
        if owner.get("status") not in SUPPORTED or not evidence:
            raise ValueError(f"{name} 缺少可核对的事实状态或证据")
        for e in evidence:
            clauses = sources.get(e.get("source_id"), {}).get("clauses", [])
            clause = next((c for c in clauses if c["clause_id"] == e.get("clause_id")), None)
            quote = "".join(e.get("quote", "").split())
            if not quote or not clause or quote not in "".join(clause.get("quote", "").split()):
                raise ValueError(f"{name} 的候选证据无法定位")
        entries.append({"candidate_id": key("cand", kind, pid, record_id), "kind": kind, "name": name,
                        "process_id": pid, "key": record_id, "evidence": evidence,
                        "definition": definition, "review_status": "pending"})

    for p in spec.get("processes", []):
        if p.get("disposition") == "excluded":
            continue
        pid = p["process_id"]
        emit("Logic", pid, pid, p.get("name", pid), p,
             {k: p.get(k) for k in ("scope", "trigger", "roles", "inputs", "outputs", "steps", "branches", "rule_ids")})
        for s in p.get("steps", []):
            if s.get("kind") in ("decision", "step"):
                emit("Function" if s["kind"] == "decision" else "Action", pid, s["step_id"], s["name"], p,
                     {"step": s})
    for r in spec.get("rules", []):
        if r.get("process_disposition") != "excluded":
            emit("Function", "", r["rule_id"], r.get("name", r["rule_id"]), r, {"rule": r})
    for section, kind, id_field in [("data_objects", "Object", "object_id"), ("data_tables", "Table", "table_id")]:
        for obj in spec.get(section, []):
            emit(kind, "", obj[id_field], obj.get("name", obj[id_field]), obj, {section: obj})
    return entries
