"""Deterministic, evidence-linked CQ suggestions from normalized semantic units."""
import re

from .derivations_graph import key
from .derivations_report import SUPPORTED, condition_text, display


def generate_cqs(spec):
    cqs = []
    scope = spec.get("scope", {})
    # Avoid embedding schema identifiers or addresses in expert-facing questions.
    literals = {s for t in spec.get("data_tables", []) for s in [
        t.get("name"), *[c.get("name") for c in t.get("columns", [])]] if s}

    def business(text, fallback):
        text = " ".join(str(text or "").split())
        if not text or any(s in text for s in literals) or re.search(r"(https?://|\b[A-Z_]+_[A-Z_]+\b|\bHTTP\s*\d+|\bSTATUS_\w+)", text, re.I):
            return fallback
        return text

    def emit(unit, entity_id, suffix, text, cq_type, owner, context, answer, requirement,
             technical, *, entities=(), attributes=(), relations=()):
        answer_text = display(answer)
        if owner.get("status") not in SUPPORTED:
            answer_text = "待业务专家提供口径。"
        cq_id = key("cq", unit, entity_id, suffix)
        cqs.append({"cq_id": cq_id, "cq_text": text, "cq_type": cq_type, "business_goal": scope.get("goal", ""),
                    "business_context": display(context), "expected_business_answer": answer_text,
                    "modeling_requirement": requirement, "technical_validation_question": technical,
                    "review_status": "pending", "related_entities": list(entities),
                    "related_attributes": list(attributes), "related_relations": list(relations),
                    "source": {"section": unit, "item_id": entity_id, "evidence": owner.get("evidence", [])},
                    "origin": "ledger_derived_hypothesis"})
    for r in spec.get("rules", []):
        if r.get("process_disposition") == "excluded":
            continue
        name = business(r.get("name"), "这项业务规定")
        rid = r["rule_id"]
        emit("rules", rid, "condition", f"什么情况下适用{name}？", "判断类", r, r.get("scope"),
             condition_text(r.get("condition", {})), "表示条件、计量单位、适用期间和例外",
             "使用边界值与缺失输入校验条件树", entities=r.get("process_ids", []), attributes=r.get("inputs", []))
        emit("rules", rid, "effect", f"满足{name}的条件后，应得到什么业务结果？", "推理类", r, r.get("scope"),
             r.get("results"), "关联条件、结果和下游流程", "核对结果、输出与验收样例",
             entities=r.get("process_ids", []), attributes=r.get("outputs", []))
    for p in spec.get("processes", []):
        if p.get("disposition") == "excluded":
            continue
        pid = p["process_id"]
        name = business(p.get("name"), "这项业务")
        emit("processes", pid, "trigger", f"发生什么事情时需要启动{name}？", "触发类", p, p.get("roles"),
             p.get("trigger"), "建立流程入口、事件及前置条件", "核对触发事件和首节点", entities=[pid])
        emit("processes", pid, "finish", f"怎样判断{name}已经办结？", "执行类", p, p.get("roles"),
             p.get("outputs"), "关联处理步骤、结束节点与产出", "沿审核流程核对输出和结束节点",
             entities=[pid], attributes=p.get("outputs", []))
    for obj in spec.get("data_objects", []):
        name = business(obj.get("name"), "这类业务对象")
        oid = obj["object_id"]
        emit("data_objects", oid, "identity", f"业务上如何识别同一个{name}？", "查询类", obj, scope.get("in_scope"),
             obj.get("identity_basis"), "明确对象身份和业务属性", "核对主标识及属性定义",
             entities=[oid], attributes=[f.get("business_name") or f.get("name") for f in obj.get("fields", [])])
        for rel in obj.get("relationships", []):
            target = rel.get("target_object_id", "")
            emit("data_objects", oid, key("relationship", target, rel.get("name")),
                 f"{name}与相关业务对象有什么对应关系？", "推理类", rel, scope.get("in_scope"),
                 [rel.get("name"), rel.get("cardinality")], "记录关系方向和基数", "按对象对检查关系方向和基数",
                 entities=[oid, target], relations=[rel.get("name")])
    for t in spec.get("data_tables", []):
        emit("data_tables", t["table_id"], "meaning", "这些业务记录能够说明哪些信息？", "查询类", t, scope.get("in_scope"),
             [c.get("business_name") for c in t.get("columns", [])], "关联业务含义与物理表列",
             f"按原文核对 {t.get('name', '')} 的列类型、约束和默认值", attributes=[c.get("name") for c in t.get("columns", [])])
    for g in spec.get("gaps", []):
        if g.get("status") != "resolved":
            emit("gaps", g["gap_id"], "clarify", business(g.get("question"), "需补充哪些业务依据才能判定？"),
                 "判断类", g, g.get("impact"), None, "记录未定字段、规则或例外并关联证据",
                 "缺少依据时保留未知分支，核对后更新缺口", entities=g.get("process_ids", []))
    return cqs
