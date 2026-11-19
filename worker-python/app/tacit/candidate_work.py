"""Human editing, validation, submission and review of the ontology candidate set."""
from copy import deepcopy
from datetime import datetime, timezone

from .derivations_graph import key
from .spec import normalized
from .workspace_state import digest, invalidate, required_string

KINDS = {"Logic", "Action", "Function", "Object", "Table"}
EDITABLE = {"draft", "pending", "approved", "rejected"}


def clauses_by_id(spec):
    return {(s["source_id"], c["clause_id"]): normalized(c["quote"])
            for s in spec.get("sources", []) if s.get("disposition") != "excluded"
            for c in s.get("clauses", []) if c.get("coverage") != "out_of_scope"}


def has_evidence(evidence, clauses, texts=None):
    if not isinstance(evidence, list) or not evidence:
        return False
    for e in evidence:
        if not isinstance(e, dict) or set(e) != {"source_id", "clause_id", "quote"}:
            return False
        if any(not isinstance(v, str) or not v.strip() for v in e.values()):
            return False
        quote = normalized(e["quote"])
        sid = e["source_id"]
        if not quote or quote not in clauses.get((sid, e["clause_id"]), ""):
            return False
        if texts is not None and quote not in texts.get(sid, ""):
            return False
    return True


def inspect_candidates(rows, spec, flows, sources):
    issues = []
    pids = {f["process_id"] for f in flows}
    clauses = clauses_by_id(spec)
    texts = {sid: normalized(s["text"]) for sid, s in sources.items()}
    ids = {row["candidate_id"] for row in rows}
    for row in rows:
        cid = row["candidate_id"]

        def problem(code, field, message):
            issues.append({"code": code, "path": f"candidates.{cid}.{field}", "message": message})

        if row["kind"] not in KINDS or row["process_id"] and row["process_id"] not in pids:
            problem("invalid_identity", "kind", "候选类型或所属流程无效")
        if not isinstance(row["name"], str) or not row["name"].strip():
            problem("missing_name", "name", "请补充候选名称")
        definition = row.get("definition")
        if not isinstance(definition, dict) or not definition:
            problem("missing_definition", "definition", "请提供非空定义对象")
        else:
            refs = definition.get("references", [])
            if not isinstance(refs, list) or any(not isinstance(r, str) or r not in ids for r in refs):
                problem("invalid_reference", "definition.references", "引用的候选不存在")
        if not has_evidence(row.get("evidence"), clauses, texts):
            problem("invalid_evidence", "evidence", "候选须引用当前项目已登记的原文与条款")
    for flow in flows:
        pid = flow["process_id"]
        if key("cand", "Logic", pid, pid) not in ids:
            issues.append({"code": "missing_logic", "path": f"flows.{pid}", "message": "流程缺少逻辑入口候选"})
        for step in flow["steps"]:
            kind = {"step": "Action", "decision": "Function"}.get(step["kind"])
            if kind and key("cand", kind, pid, step["step_id"]) not in ids:
                issues.append({"code": "missing_step_candidate", "path": f"flows.{pid}.{step['step_id']}",
                               "message": f"流程步骤「{step['name']}」缺少对应的 {kind} 候选"})
        rule_ids = {r for owner in [*flow["steps"], *flow["branches"]] for r in owner.get("rule_ids", [])}
        for rid in sorted(rule_ids):
            if key("cand", "Function", "", rid) not in ids:
                issues.append({"code": "missing_rule_candidate", "path": f"flows.{pid}.{rid}",
                               "message": f"流程引用的规则 {rid} 缺少函数候选"})
    return issues


def edit(rows, payload):
    if set(payload) - {"patches", "extensions", "remove_ids"}:
        raise ValueError("候选编辑参数无效")
    sets = [payload.get(k, []) for k in ("patches", "extensions", "remove_ids")]
    if not all(isinstance(s, list) for s in sets) or not any(sets):
        raise ValueError("请提供候选变更")
    patches, extensions, removals = sets
    current = {row["candidate_id"]: deepcopy(row) for row in rows}
    touched = set()

    def register(cid):
        if cid in touched:
            raise ValueError("同一批次重复修改候选")
        touched.add(cid)

    def existing(cid):
        if not isinstance(cid, str) or cid not in current:
            raise ValueError("候选不存在")
        register(cid)

    for cid in removals:
        existing(cid)
        del current[cid]
    writable = {"name", "definition", "evidence"}
    for change in patches:
        if not isinstance(change, dict) or set(change) - (writable | {"candidate_id"}) or not writable.intersection(change):
            raise ValueError("候选变更字段无效")
        cid = change.get("candidate_id")
        existing(cid)
        row = current[cid]
        updates = {k: deepcopy(v) for k, v in change.items() if k in writable}
        if any(row.get(k) != v for k, v in updates.items()):
            row.update(updates, review_status="pending")
            row.pop("review", None)
    for extension in extensions:
        fields = writable | {"kind", "process_id", "key"}
        if not isinstance(extension, dict) or set(extension) != fields:
            raise ValueError("扩展候选须包含类型、名称、流程、标识、定义和证据")
        kind = required_string(extension, "kind")
        pid = extension["process_id"]
        record_id = required_string(extension, "key")
        if kind not in KINDS or not isinstance(pid, str):
            raise ValueError("扩展候选类型或流程无效")
        cid = key("cand", kind, pid, record_id)
        register(cid)
        if cid in current:
            raise ValueError("扩展候选标识与现有项重复")
        current[cid] = {**deepcopy(extension), "key": record_id, "candidate_id": cid, "origin": "extension",
                        "review_status": "pending"}
    return list(current.values())


def decision(payload, actor, content):
    vote = payload.get("decision")
    comment = payload.get("comment", "")
    if vote not in ("approved", "rejected") or not isinstance(comment, str) or len(comment) > 4000:
        raise ValueError("请提供有效审核决定，意见限 4000 字")
    return {"decision": vote, "by": actor, "at": datetime.now(timezone.utc).isoformat(),
            "digest": digest(content), "comment": comment}


def for_review(row):
    return {k: v for k, v in row.items() if k not in ("review_status", "review")}


def review_row(row, payload, actor):
    stamp = decision(payload, actor, for_review(row))
    row.update(review_status="accepted" if stamp["decision"] == "approved" else "rejected", review=stamp)


def reviewed(row):
    review = row.get("review", {})
    return (row.get("review_status") == "accepted" and review.get("decision") == "approved"
            and review.get("digest") == digest(for_review(row)))


async def handle_candidate_action(state, action, payload, sources, actor):
    from .engine import available, record
    from .scenario_work import handle_scenario_action
    if action in {"propose_scenarios", "review_scenario", "validate_ontology"}:
        return await handle_scenario_action(state, action, payload, actor)
    flows = available(state, "flows", {"approved"})
    artifact = available(state, "candidates", EDITABLE)
    if artifact.get("flow_digest") != flows["digest"]:
        raise ValueError("候选与当前流程版本不一致，请重新生成")
    rows = artifact["content"]
    if action == "edit_candidates":
        result = edit(rows, payload)
        if result != rows:
            record(state, "candidates", result, "draft")
            state["artifacts"]["candidates"]["flow_digest"] = flows["digest"]
            invalidate(state, ("scenarios", "walkthrough"))
        return {"target_id": "candidates", "count": len(result)}
    issues = inspect_candidates(rows, state["spec"], flows["content"], sources)
    if action == "check_candidates":
        return {"target_id": "candidates", "issues": issues, "valid": not issues}
    if issues:
        raise ValueError("候选校验未通过：" + "；".join(f"{i['path']} {i['message']}" for i in issues[:4]))
    if action == "submit_candidates":
        if artifact["status"] not in {"draft", "rejected"}:
            raise ValueError("仅草稿或已退回的候选集可以提交审核")
        # A changed set must receive a new set decision even if every surviving row was previously signed.
        for row in rows:
            row["review_status"] = "pending"
            row.pop("review", None)
        artifact.update(status="pending", digest=digest(rows))
        artifact.pop("review", None)
        invalidate(state, ("scenarios", "walkthrough"))
        return {"target_id": "candidates", "count": len(rows)}
    if action not in {"review_candidate_item", "review_candidate"}:
        raise ValueError("未知候选操作")
    if artifact["status"] not in {"pending", "approved", "rejected"}:
        raise ValueError("请先提交候选进入审核")
    target = payload.get("candidate_id") if action == "review_candidate_item" else "candidates"
    selected = [r for r in rows if r["candidate_id"] == target] if action == "review_candidate_item" else rows
    if not selected:
        raise ValueError("所选候选不存在")
    for row in selected:
        review_row(row, payload, actor)
    statuses = {r["review_status"] for r in rows}
    status = "pending" if "pending" in statuses else ("rejected" if "rejected" in statuses else "approved")
    artifact.update(status=status, digest=digest(rows))
    if status == "pending":
        artifact.pop("review", None)
    else:
        artifact["review"] = decision({**payload, "decision": status}, actor, rows)
    invalidate(state, ("scenarios", "walkthrough"))
    return {"target_id": target, "status": status}
