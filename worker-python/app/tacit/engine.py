"""Transitions of a single project ledger. Java authenticates, snapshots inputs and CAS-commits the result."""
from copy import deepcopy
from datetime import datetime, timezone

from .workspace_state import (
    FACT_SECTIONS, add_note, changed_spec, digest, invalidate, merge_ranges,
    new_state, required_string, sources_by_id, sync_sources,
)
from .verified_view import expert_view
from .interviews import record_answer
from .spec import ITEM_IDS, item_digest, section_schema, validate_batch, validate_spec

READ_SIZE = 8000


def cursor(payload):
    value = payload.get("offset", 0)
    if type(value) is not int or value < 0:
        raise ValueError("分页位置必须是非负整数")
    return value


def upsert(rows, key, items):
    by_id = {row[key]: row for row in rows}
    by_id.update({row[key]: row for row in items})
    return list(by_id.values())


def issues_for(state, sources):
    issues = validate_spec(state["spec"], list(sources.values()))
    for i, row in enumerate(state["spec"]["sources"]):
        source = sources.get(row["source_id"], {})
        if source.get("incomplete") and row.get("disposition") != "excluded":
            issues.append({"code": "source_incomplete", "path": f"sources[{i}]",
                           "message": source.get("reason") or "原始资料未完整解析，请补充可读版本或说明排除理由"})
    return issues


def apply_batch(state, payload, sources):
    section = required_string(payload, "section")
    items = payload.get("items", [])
    scope = payload.get("scope")
    validate_batch(section, items, scope)
    spec = state["spec"]
    if section == "gaps":
        previous = {g["gap_id"]: g for g in spec["gaps"]}
        for item in items:
            if item.get("status") == "resolved" and item != previous.get(item["gap_id"]):
                raise ValueError("解决缺口须在答复时明确确认；编辑内容可先保存为待确认")
    if section in FACT_SECTIONS:
        for item in items:
            identity = item[ITEM_IDS[section]]
            for note in state["user_sources"]:
                signed = note.get("confirmed_items", {})
                if identity in signed and signed[identity] != item_digest(item):
                    # Never resurrect an earlier signature by changing the item back later.
                    del signed[identity]
                    if item.get("status") == "user_confirmed":
                        item["status"] = "proposed"
            if item.get("status") == "user_confirmed" and not any(
                n.get("confirmed_items", {}).get(identity) == item_digest(item)
                for n in state["user_sources"]
            ):
                raise ValueError("invalid_confirmation：用户确认须通过登录账户的确认操作")
    if section == "scope":
        spec["scope"] = deepcopy(scope)
    else:
        if section == "sources":
            for row in items:
                key = row["source_id"]
                if key not in sources:
                    raise ValueError("来源不在项目授权范围")
                row.update(title=sources[key]["title"], source_type=sources[key]["source_type"],
                           version=str(sources[key]["version"]), read_status=sources[key]["read_status"])
        spec[section] = upsert(spec[section], ITEM_IDS[section], deepcopy(items))


def confirm_item(state, sources, payload, actor):
    section = required_string(payload, "section")
    if section not in FACT_SECTIONS:
        raise ValueError("请选择可确认的业务对象、表、流程或规则")
    item_id = required_string(payload, "item_id")
    item = next((i for i in state["spec"][section] if i[ITEM_IDS[section]] == item_id), None)
    if item is None:
        raise ValueError("条目不存在或已删除")
    text = required_string(payload, "text")
    note = add_note(state, actor, text, "业务确认 · " + item.get("name", item_id)[:150], {item_id: item_digest(item)})
    sources[note["source_id"]] = dict(note)
    sync_sources(state, sources)
    row = next(r for r in state["spec"]["sources"] if r["source_id"] == note["source_id"])
    key = {"rules": "rule_ids", "processes": "process_ids", "data_objects": "data_object_ids",
           "data_tables": "data_table_ids"}[section]
    row["clauses"] = [{"clause_id": "confirmation", "locator": f"字符[0,{len(text)})", "quote": text,
                       "coverage": "extracted", key: [item_id]}]
    item.setdefault("evidence", []).append({"source_id": note["source_id"], "clause_id": "confirmation",
                                           "quote": text})
    item["status"] = "user_confirmed"


def record(state, name, content, status):
    state["artifacts"][name] = {"content": content, "status": status, "digest": digest(content)}


def available(state, name, statuses):
    artifact = state["artifacts"].get(name)
    if not artifact or artifact["status"] not in statuses:
        raise ValueError(f"请先完成当前版本的 {name} 阶段")
    return artifact


def review(artifact, payload, actor):
    decision = payload.get("decision")
    if decision not in ("approved", "rejected"):
        raise ValueError("无效审核决定")
    comment = payload.get("comment", "")
    if not isinstance(comment, str) or len(comment) > 4000:
        raise ValueError("审核意见应为 4000 字以内的文本")
    artifact["review"] = {"by": actor, "at": datetime.now(timezone.utc).isoformat(),
                          "digest": artifact["digest"], "decision": decision, "comment": comment}
    artifact["status"] = decision


def derive(state, action, payload, actor):
    from .derivations import build_candidates, generate_cqs, generate_flows, render_reports, walkthrough

    spec = state["spec"]
    if action == "finalize":
        mode = required_string(payload, "completion")
        if mode not in ("draft", "complete"):
            raise ValueError("交付状态无效")
        if mode == "complete" and state["issues"]:
            problems = "；".join(f'{i["code"]} {i["message"]}' for i in state["issues"][:4])
            raise ValueError("台账有待处理事项，可保存草稿：" + problems)
        if spec["completion"] != mode:
            invalidate(state)
        spec["completion"] = mode
        report_spec = expert_view(spec, state["issues"])
        for name, value in render_reports(report_spec).items():
            if name == "gap_report" and state["issues"]:
                from .derivations_report import literal
                value += "\n## 台账校验事项\n" + "".join(
                    f'- {literal(i["path"])} · {literal(i["message"])}\n' for i in state["issues"]
                )
            record(state, name, value, "ready" if mode == "complete" else "draft")
    elif action == "generate_flow":
        available(state, "business_report", {"draft", "ready"})
        flows = generate_flows(expert_view(spec, state["issues"]))
        if not flows:
            raise ValueError("台账中没有可绘制的流程")
        invalidate(state, ("candidates", "walkthrough"))
        record(state, "flows", flows, "draft")
    elif action == "review_flow":
        review(available(state, "flows", {"draft", "approved", "rejected"}), payload, actor)
        invalidate(state, ("candidates", "walkthrough"))
    elif action == "generate_cqs":
        if not any(spec[k] for k in FACT_SECTIONS):
            raise ValueError("请先整理业务对象、规则或流程")
        questions = generate_cqs(expert_view(spec, state["issues"]))
        if not questions:
            raise ValueError("现有台账无法派生有依据的业务问题")
        record(state, "cqs", questions, "draft")
        invalidate(state, ("walkthrough",))
    elif action == "review_cq":
        artifact = available(state, "cqs", {"draft", "ready"})
        question = next((q for q in artifact["content"] if q["cq_id"] == payload.get("cq_id")), None)
        decision = payload.get("decision")
        if not question or decision not in ("accepted", "rejected"):
            raise ValueError("请选择有效的能力问题和审核结论")
        question.update(review_status=decision, reviewed_by=actor, reviewed_at=datetime.now(timezone.utc).isoformat())
        artifact["digest"] = digest(artifact["content"])
        artifact["status"] = "ready" if all(q["review_status"] != "pending" for q in artifact["content"]) else "draft"
        invalidate(state, ("walkthrough",))
    elif action == "build_candidate":
        flows = available(state, "flows", {"approved"})
        if state["issues"] or spec["completion"] != "complete":
            raise ValueError("请先完成本版台账校验并处理阻断事项")
        candidates = build_candidates(spec, flows["content"])
        if not candidates:
            raise ValueError("缺少可以追溯到原文的候选项")
        record(state, "candidates", candidates, "pending")
        state["artifacts"]["candidates"]["flow_digest"] = flows["digest"]
        invalidate(state, ("walkthrough",))
    elif action == "review_candidate":
        available(state, "flows", {"approved"})
        candidate = available(state, "candidates", {"pending", "approved", "rejected"})
        review(candidate, payload, actor)
        for item in candidate["content"]:
            item["review_status"] = "accepted" if candidate["status"] == "approved" else "rejected"
        # The review binds the submitted payload. Keep the reviewed hash separately from its new projection.
        candidate["digest"] = digest(candidate["content"])
        invalidate(state, ("walkthrough",))
    elif action == "validate_ontology":
        flows = available(state, "flows", {"approved"})
        candidates = available(state, "candidates", {"approved"})
        result = walkthrough(spec, flows["content"], candidates["content"])
        record(state, "walkthrough", result, "ready")
    else:
        raise ValueError("不支持的知识梳理操作")


async def execute(state, action, payload, catalog, actor):
    state = new_state() | deepcopy(state)
    payload = deepcopy(payload)
    sources = sources_by_id(state, catalog)
    sync_sources(state, sources)
    spec = state["spec"]
    result = {}
    if action in {"sync_sources", "select_sources"}:
        pass
    elif action in {"read_source", "preview_source", "extract"}:
        key = required_string(payload, "source_id")
        source = sources.get(key)
        if source is None:
            raise ValueError("当前项目未授权该资料")
        offset = cursor(payload)
        text = source["text"]
        if offset >= len(text) and (offset or not text):
            raise ValueError("原文为空或请求位置超出原文范围")
        end = min(len(text), offset + READ_SIZE)
        if action == "extract":
            from .extraction import extract_next_page

            if not spec["scope"].get("goal") or not spec["scope"].get("in_scope"):
                raise ValueError("请先填写业务目标和纳入范围")
            if next(r for r in spec["sources"] if r["source_id"] == key)["disposition"] == "excluded":
                raise ValueError("该资料已排除，请先核对范围")
            progress = state["extraction"].get(key, {})
            if progress.get("next_offset", 0) != offset:
                raise ValueError("请从上次保存的抽取位置继续")
            end, batches = await extract_next_page(spec, source, offset)
            for batch in batches:
                apply_batch(state, batch, sources)
            state["extraction"][key] = {"next_offset": end if end < len(text) else None,
                                        "version": str(source["version"])}
            changed_spec(state)
        if action != "preview_source":
            state["coverage"][key] = merge_ranges(state["coverage"].get(key, []), offset, end)
            sync_sources(state, sources)
        result = {"source_id": key, "start": offset, "end": end, "total": len(text),
                  "next_offset": end if end < len(text) else None}
        if action != "extract":
            result["text"] = text[offset:end]
    elif action == "read_section":
        section = required_string(payload, "section")
        schema = section_schema(section)
        if section == "scope":
            result = {"scope": spec[section], "schema": schema, "next_offset": None}
        else:
            offset = cursor(payload)
            rows = spec[section]
            result = {"items": rows[offset:offset + 5], "schema": schema,
                      "next_offset": offset + 5 if offset + 5 < len(rows) else None}
    elif action == "add_note":
        title = payload.get("title") or "专家口述"
        if not isinstance(title, str):
            raise ValueError("访谈标题需为文本")
        note = add_note(state, actor, required_string(payload, "text"), title)
        result = {"source_id": note["source_id"]}
    elif action == "answer_gap":
        result = record_answer(state, sources, payload, actor)
        if result["decision"] != "no_increment":
            changed_spec(state)
    elif action == "confirm_item":
        confirm_item(state, sources, payload, actor)
        changed_spec(state)
    elif action == "save_batch":
        before = digest(spec)
        apply_batch(state, payload, sources)
        if before != digest(spec):
            changed_spec(state)
    elif action == "remove_item":
        section = required_string(payload, "section")
        if section not in ITEM_IDS or section == "sources":
            raise ValueError("该节不能删除条目")
        key = required_string(payload, "item_id")
        rows = spec[section]
        spec[section] = [row for row in rows if row[ITEM_IDS[section]] != key]
        if len(rows) == len(spec[section]):
            raise ValueError("条目已删除或不存在")
        if section == "gaps":
            state["interviews"].pop(key, None)
        for note in state["user_sources"]:
            note.get("confirmed_items", {}).pop(key, None)
        changed_spec(state)
    else:
        state["issues"] = issues_for(state, sources)
        derive(state, action, payload, actor)
    sources = sources_by_id(state, catalog)
    sync_sources(state, sources)
    state["issues"] = issues_for(state, sources)
    return {"state": state, "result": result}
