"""Deterministic validation of the editable business ledger against the server's source catalog."""
import hashlib
import json
import re

from pydantic import ValidationError

from .spec_schema import BusinessSpec, ITEM_IDS, SECTION_MODELS

CONFIRMED = {"source_supported", "user_confirmed"}
LINKS = {
    "rule_ids": "rules", "process_ids": "processes", "gap_ids": "gaps",
    "data_object_ids": "data_objects", "data_table_ids": "data_tables",
}
NUMERIC_TYPES = re.compile(
    r"^(?:tinyint|smallint|bigint|int(?:eger)?|number|numeric|decimal|float|double|real|money|"
    r"percentage|percent|amount|数值|数字|整型|整数|浮点数|金额|百分比)(?:\b|\(|$)", re.I
)
CHAR_RANGE = re.compile(r"字符\[([0-9]{1,18}),([0-9]{1,18})\)")


def empty_spec() -> dict:
    return BusinessSpec().model_dump()


def section_schema(section: str) -> dict:
    if section not in SECTION_MODELS:
        raise ValueError("未知台账章节")
    return SECTION_MODELS[section].model_json_schema()


def validate_batch(section: str, items: list[dict] | None = None, scope: dict | None = None) -> None:
    if section not in SECTION_MODELS:
        raise ValueError("未知台账章节")
    if section == "scope":
        if items not in (None, []) or not isinstance(scope, dict):
            raise ValueError("范围仅接受单个 scope 对象")
        SECTION_MODELS[section].model_validate(scope)
        return
    if scope is not None or not isinstance(items, list) or not 1 <= len(items) <= 5:
        raise ValueError("每批提交同一章节的 1 至 5 个条目")
    seen = set()
    for item in items:
        parsed = SECTION_MODELS[section].model_validate(item)
        identity = getattr(parsed, ITEM_IDS[section])
        if identity != identity.strip() or not identity or identity in seen:
            raise ValueError("批次中 ID 为空、有首尾空格或重复")
        seen.add(identity)


def item_digest(item: dict) -> str:
    content = {k: v for k, v in item.items() if k not in {"status", "evidence"}}
    canonical = json.dumps(content, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False)
    return hashlib.sha256(canonical.encode()).hexdigest()


def normalized(text: str) -> str:
    return "".join(text.split())


def locate_quote(text: str, quote: str, offset: int = 0) -> str:
    """Locate a quotation within original Unicode text, preserving source offsets."""
    if not quote.strip() or not 0 <= offset <= len(text):
        return ""
    start = text.find(quote, offset)
    if start != -1:
        return f"字符[{start},{start + len(quote)})"
    positions = [n for n in range(offset, len(text)) if not text[n].isspace()]
    needle = normalized(quote)
    start = "".join(text[n] for n in positions).find(needle)
    if start == -1:
        return ""
    return f"字符[{positions[start]},{positions[start + len(needle) - 1] + 1})"


def _quote_range(entry: dict, quote: str, locator: str) -> tuple[int, int] | None:
    text = entry["text"]
    if match := CHAR_RANGE.fullmatch(locator):
        start, end = (int(n) for n in match.groups())
        if 0 <= start < end <= len(text) and normalized(text[start:end]) == quote:
            return start, end
        return None
    for location in entry.get("locations", []):
        if not isinstance(location, dict) or location.get("locator") != locator:
            continue
        start, end = location.get("start"), location.get("end")
        if type(start) is int and type(end) is int and 0 <= start < end <= len(text):
            relative = locate_quote(text[start:end], quote)
            if match := CHAR_RANGE.fullmatch(relative):
                a, b = map(int, match.groups())
                return start + a, start + b
    return None


def _blank(value) -> bool:
    return not value.strip() if isinstance(value, str) else not value


def _present(item, *fields):
    return all(not _blank(item[key]) for key in fields)


def _blank_lists(obj, path, add):
    if isinstance(obj, dict):
        for key, value in obj.items():
            _blank_lists(value, f"{path}.{key}", add)
    if isinstance(obj, list):
        for i, value in enumerate(obj):
            if isinstance(value, str) and not value.strip():
                add("blank_list_item", f"{path}[{i}]", "清单中有空白项")
            else:
                _blank_lists(value, f"{path}[{i}]", add)


def _condition(node, path, add):
    op = node["operator"]
    if op == "unresolved":
        add("unresolved_condition", path, "条件待确认")
    if op in ("all", "any"):
        if len(node["clauses"]) < 2:
            add("condition_operands", path, "组合条件至少包含两个子条件")
        for i, child in enumerate(node["clauses"]):
            _condition(child, f"{path}.clauses[{i}]", add)
    elif node["clauses"]:
        add("condition_operands", path, "此运算符不支持子条件")
    if op == "predicate":
        cmp, value = node["comparator"], node["value"]
        if not _present(node, "field", "comparator"):
            add("predicate_missing", path, "条件缺少字段或比较符")
        if cmp not in ("exists", "not_exists") and value is None:
            add("predicate_value", path, "条件缺少比较值")
        ordered = cmp in ("gt", "gte", "lt", "lte")
        numeric = type(value) in (int, float) or (
            isinstance(value, list) and any(type(v) in (int, float) for v in value)
        )
        if (ordered or numeric) and not node["unit"].strip():
            add("numeric_unit", path, "数值条件需注明单位，无量纲须显式声明")
        if ordered and type(value) not in (int, float):
            add("numeric_boundary", path, "阈值必须是数值")
        if cmp in ("in", "not_in") and not (isinstance(value, list) and value):
            add("set_members", path, "集合条件需要非空数组")
    if op == "expression" and not _present(node, "expression", "boundary_notes", "logical_notes"):
        add("condition_expression", path, "表达式需要边界说明、逻辑说明及表达式正文")
    if op == "always" and not node["expression"].strip():
        add("unconditional_reason", path, "无条件规则须说明依据")


class SpecReview:
    def __init__(self, original: dict, spec: dict, catalog: list[dict]):
        self.spec = spec
        self.original = original
        self.catalog = {entry["source_id"]: entry for entry in catalog}
        self.rows = {section: {row[id_key]: row for row in spec[section]} for section, id_key in ITEM_IDS.items()}
        self.clauses = {(src["source_id"], clause["clause_id"]): clause for src in spec["sources"]
                        for clause in src["clauses"]}
        self.issues = []

    def add(self, code, path, message):
        self.issues.append({"code": code, "path": path, "message": message})

    def references(self, obj, path):
        for key, section in LINKS.items():
            for identity in obj.get(key, []):
                if identity not in self.rows[section]:
                    self.add("dangling_reference", f"{path}.{key}", f"条目不存在：{identity}")

    def source(self, source, path):
        identity = source["source_id"]
        entry = self.catalog.get(identity)
        included = source["disposition"] == "included"
        if not entry:
            self.add("unknown_source", path, "来源不在本项目授权资料中")
        elif source["source_type"] != entry["source_type"]:
            self.add("source_type_mismatch", path, "来源类型与授权资料不符")
        if entry and str(entry["version"]) != source["version"]:
            self.add("source_version_mismatch", path, "资料版本已变化，请按当前版本核对")
        if entry and source["title"] != entry["title"]:
            self.add("source_title_mismatch", path, "来源标题与授权资料不符")
        if included and (not entry or entry.get("incomplete") or (entry.get("source_type") != "user" and entry.get("read_status") != "read")
                         or source["read_status"] != "read"):
            self.add("source_unread", path, "仍有资料未完整读取，请继续分页或保存草稿")
        if not included and not source["scope_reason"].strip():
            self.add("source_exclusion_reason", path, "排除资料须说明范围理由")
        if included and not source["clauses"]:
            self.add("source_without_clauses", path, "须登记资料条款及其处置")
        seen = set()
        ranges = []
        for i, clause in enumerate(source["clauses"]):
            p = f"{path}.clauses[{i}]"
            cid, quote = clause["clause_id"], normalized(clause["quote"])
            if not cid.strip() or cid != cid.strip() or cid in seen:
                self.add("clause_identity", p, "条款 ID 为空、有首尾空格或重复")
            seen.add(cid)
            if not quote or not clause["locator"].strip():
                self.add("clause_evidence", p, "条款须提供引文及定位")
            if quote and entry:
                anchored = _quote_range(entry, quote, clause["locator"])
                if anchored:
                    ranges.append(anchored)
                elif quote not in normalized(entry["text"]):
                    self.add("fabricated_quote", p, "引文未命中授权原文")
                else:
                    self.add("invalid_locator", p, "引文与原文位置不符")
            self.references(clause, p)
            coverage = clause["coverage"]
            if coverage == "unreviewed":
                self.add("unclassified_clause", p, "条款尚未分类")
            if coverage == "out_of_scope" and not clause["reason"].strip():
                self.add("clause_exclusion_reason", p, "排除条款须说明理由")
            if coverage == "extracted":
                if not any(clause[k] for k in LINKS if k != "gap_ids"):
                    self.add("unbound_clause", p, "已提取条款须关联具体业务条目")
                if not included:
                    self.add("excluded_extraction", p, "排除的资料不能贡献已提取条款")
            if coverage == "gap" and not clause["gap_ids"]:
                self.add("unbound_clause_gap", p, "待补充的条款须关联缺口")
            for key, section in LINKS.items():
                for target in clause[key]:
                    row = self.rows[section].get(target)
                    if not row:
                        continue
                    if coverage == "extracted" and (row.get("disposition") == "excluded"
                                                     or row.get("process_disposition") == "excluded"):
                        self.add("excluded_extraction", p, "已提取条款关联了排除的条目")
                    if not any(e["source_id"] == identity and e["clause_id"] == cid for e in row["evidence"]):
                        self.add("coverage_without_evidence", p, f"条目 {target} 未引用本条款")

        if included and entry:
            text = entry["text"]
            cursor = 0
            for start, end in sorted(ranges):
                if text[cursor:start].strip():
                    break
                cursor = max(cursor, end)
            if text[cursor:].strip():
                self.add("unclassified_source_text", path, f"资料仍有未逐条处置的正文，起始位置 {cursor}")

    def evidence(self, fact, path, identity, original, section=None):
        confirmed = fact.get("status") == "user_confirmed"
        if fact.get("status") in CONFIRMED and not fact["evidence"]:
            self.add("missing_evidence", path, "材料支持或用户确认须有原文证据")
        user_approval = False
        for i, ev in enumerate(fact["evidence"]):
            p = f"{path}.evidence[{i}]"
            sid, cid, quote = ev["source_id"], ev["clause_id"], normalized(ev["quote"])
            clause = self.clauses.get((sid, cid))
            if not clause or not quote or quote not in normalized(clause["quote"]):
                self.add("invalid_evidence", p, "引文须命中已登记的原文条款")
                continue
            if self.rows["sources"][sid]["disposition"] == "excluded" or clause["coverage"] == "out_of_scope":
                self.add("excluded_source_used", p, "条目引用了已排除的资料或条款")
            if section:
                key = next((k for k, v in LINKS.items() if v == section), None)
                if key and identity not in clause[key]:
                    self.add("coverage_without_evidence", p, "条款未登记此条目")
            entry = self.catalog.get(sid)
            if confirmed and entry and entry["source_type"] == "user":
                user_approval |= entry.get("confirmed_items", {}).get(identity) == item_digest(original)
        if confirmed and not user_approval:
            self.add("invalid_confirmation", path, "缺少针对当前条目内容的人工确认记录")

    def rule(self, row, path):
        if not _present(row, "name", "scope", "results", "inputs", "outputs"):
            self.add("rule_missing", path, "规则缺少名称、范围、结果或输入输出")
        if row["status"] not in CONFIRMED:
            self.add("unresolved_rule", path, "规则仍待确认")
        _condition(row["condition"], f"{path}.condition", self.add)
        rid = row["rule_id"]
        if not any(rid in case["rule_ids"] for case in self.spec["acceptance_cases"]):
            self.add("rule_without_case", path, "规则缺少验收样例")
        if row["process_disposition"] == "bound":
            if not row["process_ids"]:
                self.add("rule_without_process", path, "须指定承接规则的流程")
            for pid in row["process_ids"]:
                proc = self.rows["processes"].get(pid)
                if proc and (rid not in proc["rule_ids"] or not any(
                        rid in step["rule_ids"] for step in proc["steps"] + proc["branches"])):
                    self.add("rule_process_binding", path, "流程目录及相应步骤或分支须承接规则")
        elif not row["process_reason"].strip():
            self.add("rule_disposition_reason", path, "独立或排除规则须说明理由")

    def process(self, row, path):
        if not _present(row, "name", "scope", "trigger", "roles", "inputs", "outputs", "steps"):
            self.add("process_missing", path, "流程缺少必要范围、触发、角色、输入输出或步骤")
        if row["status"] not in CONFIRMED:
            self.add("unresolved_process", path, "流程仍待确认")
        pid, parent = row["process_id"], row["parent_process_id"]
        if row["disposition"] == "subprocess" and (parent not in self.rows["processes"] or parent == pid):
            self.add("subprocess_parent", path, "子流程缺少有效的父流程")
        if row["disposition"] == "excluded" and not row["scope_reason"].strip():
            self.add("process_exclusion_reason", path, "排除流程须说明理由")
        if parent and parent not in self.rows["processes"]:
            self.add("dangling_reference", f"{path}.parent_process_id", "父流程不存在")
        seen = {pid}
        while parent in self.rows["processes"]:
            if parent in seen:
                self.add("process_parent_cycle", path, "父子流程形成循环")
                break
            seen.add(parent)
            parent = self.rows["processes"][parent]["parent_process_id"]
        for rid in row["rule_ids"]:
            rule = self.rows["rules"].get(rid)
            if rule and (rule["process_disposition"] != "bound" or pid not in rule["process_ids"]):
                self.add("rule_process_binding", path, f"规则 {rid} 未绑定本流程")
        step_ids = {step["step_id"] for step in row["steps"]}
        for key, id_key in [("steps", "step_id"), ("branches", "branch_id")]:
            ids = set()
            for i, sub in enumerate(row[key]):
                p = f"{path}.{key}[{i}]"
                identity = sub[id_key]
                if not identity.strip() or identity != identity.strip() or identity in ids:
                    self.add("duplicate_or_empty_id", p, "步骤或分支 ID 为空、有首尾空格或重复")
                ids.add(identity)
                self.references(sub, p)
                if not set(sub["rule_ids"]).issubset(row["rule_ids"]):
                    self.add("rule_process_binding", p, "步骤或分支使用了流程目录外的规则")
                if key == "branches":
                    if sub["from_step_id"] not in step_ids or sub["to_step_id"] not in step_ids:
                        self.add("dangling_reference", p, "流程连线未连接真实步骤")
                    _condition(sub["condition"], f"{p}.condition", self.add)
                elif not sub["name"].strip():
                    self.add("process_missing", p, "步骤缺少业务名称")

    def data_object(self, row, path):
        if not _present(row, "name", "identity_basis", "fields"):
            self.add("object_identity", path, "对象缺少名称、身份依据或字段")
        if row["status"] not in CONFIRMED:
            self.add("unresolved_object", path, "对象仍待确认")
        for i, field in enumerate(row["fields"]):
            p = f"{path}.fields[{i}]"
            if not _present(field, "business_name", "data_type"):
                self.add("field_definition", p, "字段缺少业务名称或数据类型")
            if NUMERIC_TYPES.match(field["data_type"].strip()) and not field["unit"].strip():
                self.add("field_unit", p, "数值字段须注明单位或无量纲")
            if field["default_value"] is not None:
                if field["default_status"] not in CONFIRMED:
                    self.add("unconfirmed_default", p, "默认值未经确认")
                elif field["default_status"] == "user_confirmed" and row["status"] != "user_confirmed":
                    self.add("invalid_confirmation", p, "默认值缺少对应对象的人工确认")
                elif field["default_status"] == "source_supported" and not _literal_in_evidence(field["default_value"], row):
                    self.add("unconfirmed_default", p, "默认值未见于对象的证据")
        for i, rel in enumerate(row["relationships"]):
            p = f"{path}.relationships[{i}]"
            if not rel["name"].strip() or rel["cardinality"] == "unresolved":
                self.add("relationship_definition", p, "关系缺少名称或基数")
            if rel["target_object_id"] not in self.rows["data_objects"]:
                self.add("dangling_reference", p, "关系目标对象不存在")
            if rel["status"] not in CONFIRMED:
                self.add("unresolved_relationship", p, "关系仍待确认")
            # Nested relationships are part of the signed object payload.
            if rel["status"] == "user_confirmed" and row["status"] != "user_confirmed":
                self.add("invalid_confirmation", p, "需对包含此关系的对象版本作人工确认")
            self.evidence({**rel, "status": "source_supported" if rel["status"] in CONFIRMED else rel["status"]},
                          p, row["object_id"], {})

    def table(self, row, path):
        if not _present(row, "name", "locator", "columns"):
            self.add("table_definition", path, "表缺少名称、定位或列")
        if row["read_status"] != "read":
            self.add("table_unread", path, "表结构尚未读完")
        if row["status"] not in CONFIRMED:
            self.add("unresolved_table", path, "表结构仍待确认")
        if not _literal_in_evidence(row["name"], row):
            self.add("table_literal_evidence", path, "表名未命中所引原文")
        seen = set()
        for i, col in enumerate(row["columns"]):
            p = f"{path}.columns[{i}]"
            if not _present(col, "name", "data_type"):
                self.add("table_definition", p, "列缺少名称或类型")
            name = col["name"].strip().casefold()
            if name in seen:
                self.add("duplicate_table_column_name", p, "列名重复")
            seen.add(name)
            if col["default_status"] == "not_provided" and col["default_value"] is not None:
                self.add("table_default_status", p, "未声明的默认值必须留空")
            if col["default_status"] == "declared" and (
                    col["default_value"] is None or isinstance(col["default_value"], str) and not col["default_value"].strip()):
                self.add("table_default_value", p, "已声明默认值须提供字面值")
            if col["default_status"] == "unresolved":
                self.add("unresolved_table", p, "列默认值仍待确认")
            for key in ("name", "data_type", "default_value"):
                value = col[key]
                if value is not None and not _literal_in_evidence(value, row):
                    self.add("table_literal_evidence", f"{p}.{key}", "列名、类型及默认值须有字面依据")

    def gap(self, row, path):
        if not _present(row, "question", "impact"):
            self.add("gap_description", path, "缺口缺少问题或业务影响")
        if row["status"] == "open" and row["blocking"]:
            self.add("blocking_gap", path, "阻断业务结论的问题尚未解决")
        if row["status"] == "resolved" and not _present(row, "resolution", "evidence"):
            self.add("unsupported_resolution", path, "已解决的缺口须提供确认口径及对应证据")

    def case(self, row, path):
        if not _present(row, "name", "inputs", "expected_outputs") or not (row["rule_ids"] or row["process_ids"]):
            self.add("case_definition", path, "验收场景须有关联条目、输入及预期结果")
        if row["status"] == "needs_confirmation":
            self.add("unresolved_case", path, "验收场景仍待确认")
        if row["expected_path"]:
            procs = [self.rows["processes"][pid] for pid in row["process_ids"] if pid in self.rows["processes"]]
            if not any(_follows_path(row["expected_path"], proc) for proc in procs):
                self.add("dangling_reference", f"{path}.expected_path", "预期路径未沿所引流程的真实连线")

    def run(self):
        spec = self.spec
        if not _present(spec["scope"], "goal", "in_scope"):
            self.add("scope_missing", "scope", "须明确业务目标及纳入范围")
        for section in ("sources", "data_objects", "acceptance_cases"):
            if not spec[section]:
                self.add("section_missing", section, "章节为空")
        if not (spec["rules"] or spec["processes"]):
            self.add("business_content_missing", "rules", "须登记业务规则或流程")
        for identity in sorted(self.catalog.keys() - self.rows["sources"].keys()):
            self.add("source_omitted", "sources", f"授权资料未登记：{identity}")
        _blank_lists(spec, "$", self.add)
        checks = {"sources": self.source, "rules": self.rule, "processes": self.process,
                  "gaps": self.gap, "data_objects": self.data_object, "data_tables": self.table,
                  "acceptance_cases": self.case}
        for section, key in ITEM_IDS.items():
            seen = set()
            for i, row in enumerate(spec[section]):
                path = f"{section}[{i}]"
                identity = row[key]
                if not identity or identity != identity.strip() or identity in seen:
                    self.add("source_identity" if section == "sources" else "duplicate_or_empty_id",
                             f"{path}.{key}", "稳定 ID 为空、有首尾空格或重复")
                seen.add(identity)
                if "evidence" in row:
                    self.evidence(row, path, identity, self.original[section][i], section)
                self.references(row, path)
                checks[section](row, path)
        return self.issues


def _literal_in_evidence(value, row):
    text = value if isinstance(value, str) else json.dumps(value, ensure_ascii=False)
    literal = normalized(text)
    if type(value) in (int, float):
        # A default of zero must not be justified by a quote containing only "100".
        token = re.compile(r"(?<![0-9.+-])" + re.escape(literal) + r"(?![0-9.])")
        return any(token.search(normalized(ev["quote"])) for ev in row["evidence"])
    return bool(literal) and any(literal in normalized(ev["quote"]) for ev in row["evidence"])


def _follows_path(ids, process):
    steps = {s["step_id"] for s in process["steps"]}
    edges = {(e["from_step_id"], e["to_step_id"]) for e in process["branches"]}
    return set(ids) <= steps and all((a, b) in edges for a, b in zip(ids, ids[1:]))


def validate_spec(spec: dict, catalog: list[dict]) -> list[dict]:
    try:
        parsed = BusinessSpec.model_validate(spec)
    except ValidationError as exc:
        return [{"code": "schema_invalid", "path": ".".join(map(str, err["loc"])), "message": err["msg"]}
                for err in exc.errors(include_url=False, include_input=False)]
    return SpecReview(spec, parsed.model_dump(), catalog).run()
