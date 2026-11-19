"""Read-only Markdown views of the current business ledger."""
import html
import json
import re

UNKNOWN = "材料未说明"
STATUS = {
    "user_confirmed": "用户确认", "source_supported": "材料支持",
    "proposed": "建议待确认", "unresolved": "待确认", "conflicted": "存在冲突",
}
SUPPORTED = {"user_confirmed", "source_supported"}
COMPARATORS = {
    "gt": ">", "gte": "≥", "lt": "<", "lte": "≤", "eq": "等于", "neq": "不等于",
    "in": "属于", "not_in": "不属于", "exists": "存在", "not_exists": "不存在",
}


def display(value):
    if isinstance(value, list):
        return "、".join(display(item) for item in value) or UNKNOWN
    if isinstance(value, (dict, bool)):
        return json.dumps(value, ensure_ascii=False, sort_keys=True)
    if value is None or (isinstance(value, str) and not value.strip()):
        return UNKNOWN
    return str(value)


def literal(value):
    # Source text is a literal even when exported to a Markdown/HTML viewer.
    safe = html.escape(" ".join(display(value).split()), quote=False)
    return re.sub(r"([\\`*_{}\[\]()#!|~])", r"\\\1", safe)


def condition_text(condition):
    op = condition.get("operator")
    if op in ("all", "any"):
        word = " 且 " if op == "all" else " 或 "
        return "（" + word.join(condition_text(c) for c in condition.get("clauses", [])) + "）"
    if op == "predicate":
        comparator = condition.get("comparator", "")
        value = "" if comparator in ("exists", "not_exists") else display(condition.get("value"))
        return " ".join(filter(None, [condition.get("field"), COMPARATORS.get(comparator, ""), value,
                                      condition.get("unit"), condition.get("boundary_notes")]))
    if op in ("expression", "always"):
        return "；".join(filter(None, [condition.get("expression"), condition.get("boundary_notes"),
                                      condition.get("logical_notes")])) or "待确认条件"
    return "待确认条件"


def citations(item, sources):
    refs = []
    for e in item.get("evidence", []):
        source = sources.get(e.get("source_id"), {})
        clause = next((c for c in source.get("clauses", []) if c["clause_id"] == e.get("clause_id")), {})
        refs.append(f"{display(source.get('title') or e.get('source_id'))}（{display(clause.get('locator'))}）「{display(e.get('quote'))}」")
    return refs or ["待补来源"]


def render_reports(spec):
    scope = spec.get("scope", {})
    sources = {s["source_id"]: s for s in spec.get("sources", [])}
    rules = {r["rule_id"]: r for r in spec.get("rules", [])}
    processes = {p["process_id"]: p for p in spec.get("processes", [])}
    gaps = spec.get("gaps", [])
    report = ["# 业务梳理报告", "", "## 业务范围"]
    gap_report = ["# 业务缺口报告", "", "## 待确认与冲突事项"]

    def field(lines, label, value):
        lines.append(f"- {label}：{literal(value)}")

    for label, key in [("纳入范围", "in_scope"), ("排除范围", "out_of_scope"), ("适用地区", "region"), ("适用期间", "period")]:
        field(report, label, scope.get(key))
    field(report, "业务对象", [obj.get("name") for obj in spec.get("data_objects", [])])
    field(report, "参与角色", list(dict.fromkeys(role for p in processes.values() for role in p.get("roles", []))))
    for s in sources.values():
        if s.get("disposition") == "excluded":
            field(report, "排除资料", [s.get("title"), s.get("scope_reason")])
    report += ["", "## 业务目标", "", literal(scope.get("goal")), "", "## 业务规则"]
    for rule_id, r in rules.items():
        report += ["", f"### {literal(r.get('name'))}"]
        field(report, "事实状态", STATUS.get(r.get("status"), "待确认"))
        for label, value in [("适用条件", r.get("scope")), ("判断或计算", condition_text(r.get("condition", {}))),
                             ("业务结果", r.get("results")), ("输入", r.get("inputs")), ("输出", r.get("outputs")),
                             ("例外", r.get("exceptions")), ("生效日", r.get("effective_from")),
                             ("截止日", r.get("effective_to")), ("冲突与优先依据", r.get("precedence")),
                             ("使用流程", [processes.get(p, {}).get("name", p) for p in r.get("process_ids", [])] or r.get("process_reason")),
                             ("来源", citations(r, sources))]:
            field(report, label, value)
        for g in gaps:
            if rule_id in g.get("rule_ids", []) and g.get("status") != "resolved":
                field(report, "待确认事项与影响", [g.get("question"), g.get("impact")])
    if not rules:
        report += ["", "尚无已登记规则。"]
    report += ["", "## 业务流程", ""]
    field(report, "流程目录", [p.get("name") for p in processes.values()])
    for p in processes.values():
        report += ["", f"### {literal(p.get('name'))}"]
        if p.get("disposition") == "excluded":
            field(report, "排除原因", p.get("scope_reason"))
            continue
        steps = {s["step_id"]: s.get("name") or s["step_id"] for s in p.get("steps", [])}
        for label, value in [("事实状态", STATUS.get(p.get("status"), "待确认")), ("目的与范围", p.get("scope")),
                             ("触发事件", p.get("trigger")), ("参与角色", p.get("roles")),
                             ("前提", p.get("preconditions")), ("输入", p.get("inputs"))]:
            field(report, label, value)
        for s in p.get("steps", []):
            field(report, "步骤", f"{steps[s['step_id']]}（{display(s.get('actor'))}）")
        for b in p.get("branches", []):
            origin, dest = b.get("from_step_id"), b.get("to_step_id")
            field(report, "异常分支" if b.get("is_exception") else "分支",
                  f"{steps.get(origin, origin)} → {steps.get(dest, dest)}：{condition_text(b.get('condition', {}))}")
        field(report, "例外", p.get("exceptions"))
        field(report, "输出", p.get("outputs"))
        field(report, "结束节点", [s.get("name") for s in p.get("steps", []) if s.get("kind") == "end"])
        field(report, "来源", citations(p, sources))
    if spec.get("data_tables"):
        report += ["", "## 已有表结构"]
        for t in spec["data_tables"]:
            report += ["", f"### {literal(t.get('name'))}"]
            field(report, "位置", t.get("locator"))
            for c in t.get("columns", []):
                parts = [c.get("business_name"), c.get("data_type"), "主键" if c.get("primary_key") else "",
                         f"外键 {c['foreign_key']}" if c.get("foreign_key") else "", *c.get("constraints", [])]
                if c.get("default_status") == "declared":
                    parts.append("默认值 " + display(c.get("default_value")))
                field(report, "字段", display(c.get("name")) + " · " + display([x for x in parts if x]))
            field(report, "来源", citations(t, sources))
    if not gaps:
        gap_report += ["", "当前台账未登记显式缺口。"]
    for g in gaps:
        gap_report += ["", f"### {literal(g.get('question'))}"]
        for label, value in [("业务影响", g.get("impact")), ("处理情况", "已解决" if g.get("status") == "resolved" else "待确认"),
                             ("是否阻断完成", "是" if g.get("blocking", True) else "否"), ("确认口径", g.get("resolution")),
                             ("涉及内容", [i for k in ("rule_ids", "process_ids", "data_object_ids", "data_table_ids") for i in g.get(k, [])]),
                             ("来源", citations(g, sources))]:
            field(gap_report, label, value)
    gap_report += ["", "## 待确认的数据口径"]
    for obj in spec.get("data_objects", []) + spec.get("data_tables", []):
        if obj.get("status") not in SUPPORTED:
            field(gap_report, "待核实", obj.get("name"))
    gap_report += ["", "## 尚需补充的内容"]
    for item in [*rules.values(), *processes.values()]:
        if item.get("status") not in SUPPORTED:
            field(gap_report, STATUS.get(item.get("status"), "待确认"), item.get("name"))
    for s in sources.values():
        if s.get("disposition", "included") != "excluded" and s.get("read_status") != "read":
            field(gap_report, "资料仍待阅读", s.get("title"))
    return {"business_report": "\n".join(report) + "\n", "gap_report": "\n".join(gap_report) + "\n"}
