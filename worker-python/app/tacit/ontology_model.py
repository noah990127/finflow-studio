"""Project a signed FinFlow review into NVIDIA Auto Ontology's native model document."""
from collections import Counter, defaultdict
from copy import deepcopy

from .candidate_work import KINDS, clauses_by_id, for_review, has_evidence, reviewed
from .derivations_graph import build_candidates, key
from .derivations_walkthrough import walkthrough
from .ontology_limits import MODEL_LIMIT, TERM_LIMIT, model_bytes
from .ontology_rdf import business_ontology
from .scenario_work import as_case, baseline
from .spec import CONFIRMED, normalized
from .spec_schema import DataField, DataObject, DataTable, Relationship
from .workspace_state import digest

FORMAT_VERSION = 1


def _approved(state, name):
    artifact = state.get("artifacts", {}).get(name, {})
    content = artifact.get("content")
    stamp = artifact.get("review") or {}
    if (artifact.get("status") != "approved" or not isinstance(content, list) or not content
            or artifact.get("digest") != digest(content) or stamp.get("decision") != "approved"
            or not stamp.get("by") or not stamp.get("at")):
        raise ValueError(f"请先完成当前版本的 {name} 审核并核对签名")
    return artifact


def _candidate_signatures(artifact):
    rows = artifact["content"]
    signed = artifact["review"].get("digest")
    if all("review" in r for r in rows):
        if signed == artifact["digest"] and all(reviewed(r) and r["review"].get("by")
                                                 and r["review"].get("at") for r in rows):
            return "item_and_set"
    # The initial FinFlow release signed the whole submitted set BEFORE projecting
    # its acceptance onto the rows. Restore only the three possible uniform prior
    # states and check the entire payload, including every name, definition and quote.
    elif all("review" not in r and r.get("review_status") == "accepted" for r in rows):
        for prior in ("pending", "accepted", "rejected"):
            if signed == digest([dict(r, review_status=prior) for r in rows]):
                return "legacy_set"
    raise ValueError("候选内容与逐项或整组审核签名不一致，请重新审核")


def _validate_ledger(state):
    spec = state.get("spec", {})
    if (spec.get("completion") != "complete" or state.get("issues") != []
            or not spec.get("scope", {}).get("goal") or not spec["scope"].get("in_scope")):
        raise ValueError("请先完成本版业务台账并确认建模范围")
    if any(g.get("blocking", True) and g.get("status") != "resolved" for g in spec.get("gaps", [])):
        raise ValueError("请先解决业务台账中的阻断缺口")
    flows = _approved(state, "flows")
    candidates = _approved(state, "candidates")
    if flows["review"].get("digest") != flows["digest"] or candidates.get("flow_digest") != flows["digest"]:
        raise ValueError("候选与当前流程审核版本不一致")
    derived = build_candidates(spec, flows["content"])
    mode = _candidate_signatures(candidates)
    rows = candidates["content"]
    ids = {r["candidate_id"] for r in rows}
    if len(ids) != len(rows):
        raise ValueError("候选标识重复")
    bound_rules = {rid for f in flows["content"] for item in (*f["steps"], *f["branches"])
                   for rid in item.get("rule_ids", [])}
    mandatory = {r["candidate_id"] for r in derived if r["process_id"] or
                 (r["kind"] == "Function" and r["key"] in bound_rules)}
    if mandatory - ids:
        raise ValueError("审核候选缺少当前流程的步骤或绑定规则")
    clauses = clauses_by_id(spec)
    processes = {f["process_id"] for f in flows["content"]}
    for row in rows:
        cid, pid = row["candidate_id"], row["process_id"]
        if (row["kind"] not in KINDS or not row.get("name", "").strip()
                or not row.get("key") or cid != key("cand", row["kind"], pid, row["key"])
                or pid and pid not in processes):
            raise ValueError(f"候选 {cid} 不属于审核范围或标识无效")
        if not has_evidence(row.get("evidence"), clauses):
            raise ValueError(f"候选 {cid} 无法定位到本版台账的原文")
        definition = row.get("definition")
        if not isinstance(definition, dict) or not definition or any(
                target not in ids for target in definition.get("references", [])):
            raise ValueError(f"候选 {cid} 缺少定义或引用了未审核的候选")
    return spec, flows, candidates, mode


def _questions(state):
    artifact = state["artifacts"].get("cqs", {})
    if artifact.get("status") not in {"draft", "ready"}:
        return []
    rows = artifact.get("content", [])
    if artifact.get("digest") != digest(rows):
        raise ValueError("能力问题内容与当前版本不一致")
    return [q for q in rows if q.get("review_status") == "accepted" and q.get("reviewed_by") and q.get("reviewed_at")]


def _validation(state, flows, candidates):
    spec = state["spec"]
    proposals = state["artifacts"].get("scenarios", {})
    replay = state["artifacts"].get("walkthrough", {})
    rows = proposals.get("content", [])
    current = (proposals.get("status") in {"ready", "draft"} and proposals.get("baseline") == baseline(state, flows)
               and proposals.get("digest") == digest(rows))
    signed = []
    reasons = []
    if current:
        for r in rows:
            vote = {"accepted": "approved", "rejected": "rejected"}.get(r.get("review_status"))
            stamp = r.get("review") or {}
            if vote and (stamp.get("decision") == vote and stamp.get("by") and stamp.get("at")
                         and stamp.get("digest") == digest(for_review(r))):
                signed.append(r)
            elif vote:
                reasons.append("场景内容与审核签名不一致，请重新审核")
    validated = replay.get("status") == "ready" and replay.get("digest") == digest(replay.get("content"))
    result = replay.get("content", {}) if validated and current else {}
    if not replay:
        status = "not_run"
        reasons.append("请先在建模校核中运行本版场景验证")
    elif not result:
        status = "stale"
        reasons.append("场景或走查记录与当前审核版本不一致，请重新运行建模校核")
    else:
        pending = sum(r.get("review_status") == "pending" for r in rows)
        verified = walkthrough(spec, flows["content"], candidates["content"], [as_case(r) for r in signed if reviewed(r)])
        if pending:
            reasons.append(f"仍有 {pending} 个场景待专家审核")
        if result.get("pending_scenario_count") != pending or any(result.get(k) != verified[k]
                for k in ("processes", "findings")):
            reasons.append("保存的走查与本版候选、场景重放结果不一致")
        if verified["status"] != "needs_review" or result.get("status") != "needs_review":
            reasons.extend(dict.fromkeys(f["message"] for f in verified["findings"]))
            if any(p["truncated"] for p in verified["processes"]):
                reasons.append("流程路径未完整枚举")
            if not reasons:
                reasons.append("本版场景走查未通过")
        status = "incomplete" if reasons else "path_verified"
    quality = {"publication_ready": status == "path_verified", "scenario_validation": status,
               "business_outcome": "requires_independent_verification", "reasons": list(dict.fromkeys(reasons))}
    return quality, {"acceptance_cases": spec.get("acceptance_cases", []), "scenarios": signed,
                     "scenario_baseline": proposals.get("baseline") if current else None,
                     "walkthrough": {"status": replay.get("status", "not_run"), "digest": replay.get("digest"),
                                     "result": result}}


def _sources(spec, *records):
    """Include original source locators for nested relationships, replay traces and CQs."""
    cited = defaultdict(set)
    pending = list(records)
    while pending:
        value = pending.pop()
        if isinstance(value, list):
            pending.extend(value)
        elif isinstance(value, dict):
            sid = value.get("source_id")
            if isinstance(sid, str) and isinstance(value.get("quote"), str):
                clause_id = value.get("clause_id")
                cited[sid].add(clause_id if isinstance(clause_id, str) else None)
            else:
                pending.extend(value.values())
    return [{**s, "clauses": [c for c in s["clauses"] if c["clause_id"] in cited[s["source_id"]]]}
            for s in spec.get("sources", []) if s["source_id"] in cited and s.get("disposition") != "excluded"]


def _source_definition(row):
    kind = row["kind"]
    if kind not in {"Table", "Object"}:
        return None
    field = "data_tables" if kind == "Table" else "data_objects"
    definition = row["definition"].get(field)
    if kind == "Object" and definition is None:
        # Explicit class extensions can use a description and optional logical fields.
        fields = row["definition"].get("fields", [])
        if not isinstance(fields, list):
            raise ValueError(f"{row['name']} 的对象字段须为数组")
        try:
            fields = [DataField.model_validate(f).model_dump() for f in fields]
        except ValueError as exc:
            raise ValueError(f"{row['name']} 的对象字段定义无效") from exc
        relations = row["definition"].get("relationships", [])
        if not isinstance(relations, list):
            raise ValueError(f"{row['name']} 的对象关系须为数组")
        try:
            relations = [Relationship.model_validate(r).model_dump() for r in relations]
        except ValueError as exc:
            raise ValueError(f"{row['name']} 的对象关系格式无效") from exc
        return {"name": row["name"], "fields": fields, "relationships": relations,
                "description": row["definition"].get("description", "")}
    if not isinstance(definition, dict):
        return None
    # Human-edited candidate definitions allow arbitrary JSON. Only the typed
    # business/physical schema can be projected into native fields.
    try:
        schema = DataTable if kind == "Table" else DataObject
        definition = schema.model_validate(definition).model_dump()
    except ValueError as exc:
        raise ValueError(f"{row['name']} 的结构与审核表或对象字段格式不符") from exc
    if definition.get("status") not in CONFIRMED:
        raise ValueError(f"{row['name']} 的来源事实未经确认")
    if kind == "Table" and (definition.get("read_status") != "read" or not definition.get("columns")):
        raise ValueError(f"{row['name']} 的表结构尚未完整读取")
    if kind == "Table":
        quotes = [normalized(e["quote"]) for e in row["evidence"]]
        literals = [definition["name"], *(f[k] for f in definition["columns"] for k in ("name", "data_type"))]
        if any(not normalized(literal) or not any(normalized(literal) in quote for quote in quotes)
               for literal in literals):
            raise ValueError(f"{row['name']} 的表名、列名或类型缺少审核原文依据")
    return definition


def _mapped_relation(row, definition, name, snapshot):
    logical = row["kind"] == "Object"
    cid = row["candidate_id"]

    def identity(entity, *parts):
        return digest([snapshot, cid, entity, *parts])

    table_id = identity("table")
    term_id = identity("term")
    details = definition.get("identity_basis" if logical else "locator") or definition.get("description") or ""
    if not isinstance(details, str):
        details = ""
    desc = "FinFlow 逻辑对象投影" if logical else "FinFlow 已审核表结构"
    if details:
        desc += " · " + details
    table = {"id": table_id, "name": name, "description": desc, "pk": [],
             "type": "LOGICAL OBJECT" if logical else "TABLE", "columns": []}
    term = {"id": term_id, "name": f"{row['name']} [ff:{snapshot}:{digest(cid)}]", "description": desc,
            "represents": [table_id], "columns_attributes": []}
    mapping = {"kind": "logical_object" if logical else "physical_table", "term_id": term_id,
               "table_id": table_id, "fields": []}
    fields = definition.get("fields" if logical else "columns", [])
    primary = [f["name"] for f in fields if not logical and f.get("primary_key") is True]
    table["pk"] = primary
    seen = set()
    for field in fields:
        column = field.get("name") or (field.get("business_name") if logical else "")
        if not isinstance(column, str) or not column.strip() or column in seen:
            raise ValueError(f"{row['name']} 的字段名称缺失或重复，请核对候选")
        seen.add(column)
        business = field.get("business_name") or column
        col_desc = business
        if logical and field.get("unit"):
            col_desc += "（" + field["unit"] + "）"
        col_id, attr_id = identity("column", column), identity("attribute", column)
        constraints = {c.strip().upper() for c in field.get("constraints", [])} if not logical else set()
        table["columns"].append({"id": col_id, "name": column, "type": field.get("data_type") or "",
                                 "description": col_desc, "sample_values": [],
                                 "is_nullable": column not in primary and "NOT NULL" not in constraints,
                                 "is_unique": primary == [column] or "UNIQUE" in constraints})
        term["columns_attributes"].append({"id": attr_id, "name": business, "description": col_desc, "column_id": col_id})
        mapping["fields"].append({"name": column, "column_id": col_id, "attribute_id": attr_id})
    if logical and not fields:
        # NVIDIA's catalog export inner-joins columns. A technical provenance column keeps
        # a fieldless logical class round-trippable, without adding a business attribute.
        col_id = identity("provenance_column")
        table["columns"].append({"id": col_id, "name": "finflow_candidate_id", "description": "FinFlow 逻辑投影的候选标识",
                                 "type": "TEXT", "sample_values": [], "is_nullable": True, "is_unique": False})
        mapping["projection_column_id"] = col_id
    return table, term, mapping


def _foreign_keys(tables, warnings):
    targets = defaultdict(set)
    pending = []
    for row, definition, table in tables:
        for field, col in zip(definition["columns"], table["columns"], strict=True):
            for alias in {row["key"], definition["name"], definition.get("table_id", "")} - {""}:
                targets[alias + "." + field["name"]].add(col["id"])
            if field.get("foreign_key", "").strip():
                pending.append((row, field, col))
    edges = []
    for row, field, col in pending:
        declaration = field["foreign_key"].strip()
        matches = targets.get(declaration, set())
        if len(matches) == 1:
            edges.append({"source_column_id": col["id"], "target_column_id": next(iter(matches))})
        else:
            warnings.append({"code": "unresolved_foreign_key", "candidate_id": row["candidate_id"],
                             "field": field["name"], "declaration": declaration,
                             "reason": "外键声明未唯一命中已审核物理表列，原文保留在清单"})
    return edges


def build_ontology_model(state, project_id):
    """Build an immutable native interchange document and its local review manifest."""
    if not isinstance(project_id, str) or not project_id.strip():
        raise ValueError("缺少当前项目标识")
    spec, flows, candidates, mode = _validate_ledger(state)
    rows = sorted(candidates["content"], key=lambda r: r["candidate_id"])
    questions = _questions(state)
    quality, validation = _validation(state, flows, candidates)
    versions = state.get("source_versions", {})
    snapshot = digest([FORMAT_VERSION, project_id, versions, digest(spec), flows["review"],
                       candidates["digest"], candidates["review"], questions, validation, quality])
    db_name = f"finflow_{digest(project_id)[:16]}_{snapshot}"
    native_rows = []
    counts_by_name = Counter()
    for row in rows:
        definition = _source_definition(row)
        if definition is not None:
            name = definition.get("name") if row["kind"] == "Table" else row["name"]
            if not isinstance(name, str) or not name.strip():
                raise ValueError(f"{row['name']} 的表名缺失")
            counts_by_name[row["kind"], name] += 1
            native_rows.append((row, definition, name))
    if not native_rows:
        raise ValueError("当前审核范围缺少可映射的业务对象或已读取的表结构，请补充并审核后再构建本体")

    mappings, schemas, terms, physical, warnings = {}, {}, [], [], []
    for row, definition, name in native_rows:
        schema_name = "finflow_tables" if row["kind"] == "Table" else "finflow_objects"
        schema = schemas.setdefault(schema_name, {"id": digest([snapshot, "schema", schema_name]),
                                                   "name": schema_name, "database_name": db_name, "tables": []})
        if counts_by_name[row["kind"], name] > 1:
            name += " [ff:" + digest(row["candidate_id"]) + "]"
        table, term, mapping = _mapped_relation(row, definition, name, snapshot)
        schema["tables"].append(table)
        terms.append(term)
        mappings[row["candidate_id"]] = mapping
        if row["kind"] == "Table":
            physical.append((row, definition, table))

    objects = [(row, definition) for row, definition, _ in native_rows if row["kind"] == "Object"]
    turtle, relationships, class_iris, property_count = business_ontology(project_id, snapshot, objects, spec)
    for cid, iri in class_iris.items():
        mappings[cid]["class_iri"] = iri

    fk = _foreign_keys(physical, warnings)
    model = {"data_layer": {"databases": [{"id": digest([snapshot, "database"]), "dialect": "",
                                           "schemas": list(schemas.values())}], "foreign_keys": fk, "joins": []},
             "semantic_layer": {"terms": terms, "semantic_fks": [], "custom_analyses": [],
                                "sql_attributes": {"manual": [], "table": [], "sql": [], "bridge_table": []}},
             "zones": []}
    size = len(model_bytes(model))
    if size > MODEL_LIMIT:
        quality["publication_ready"] = False
        quality["reasons"].append(f"原生模型为 {size} 字节，超过发布上限 {MODEL_LIMIT} 字节，请缩小范围或离线导出")
    if len(terms) > TERM_LIMIT:
        quality["publication_ready"] = False
        quality["reasons"].append(f"有 {len(terms)} 个语义术语，超过发布上限 {TERM_LIMIT} 个，请缩小范围或离线导出")
    model_digest = digest(model)
    manifest_rows = [dict(r, mapping=mappings.get(r["candidate_id"], {
        "kind": "manifest_only", "reason": "保留审核定义与证据，当前定义没有对应的原生表或语义术语"})) for r in rows]
    manifest = {"format_version": FORMAT_VERSION, "project_id": project_id, "database_name": db_name,
                "snapshot_digest": snapshot, "model_digest": model_digest, "candidate_digest": candidates["digest"],
                "flow_digest": flows["digest"], "review": {"mode": mode, "flows": flows["review"],
                                                           "candidates": candidates["review"]},
                "scope": spec["scope"], "source_versions": versions,
                "sources": _sources(spec, rows, questions, validation, spec.get("gaps", [])),
                "quality": quality, **validation,
                "candidates": manifest_rows, "competency_questions": questions,
                "gaps": spec.get("gaps", []), "relationships": relationships, "warnings": warnings}
    counts = {"databases": 1, "schemas": len(schemas), "tables": len(native_rows),
              "columns": sum(len(t["columns"]) for s in schemas.values() for t in s["tables"]),
              "terms": len(terms), "column_attributes": sum(len(t["columns_attributes"]) for t in terms),
              "foreign_keys": len(fk), "sql_attributes": 0, "custom_analyses": 0, "semantic_fks": 0,
              "candidates": len(rows), "manifest_only": len(rows) - len(native_rows),
              "physical_tables": len(physical), "logical_objects": len(native_rows) - len(physical),
              "competency_questions": len(questions), "object_properties": property_count}
    return {"model": model, "manifest": deepcopy(manifest), "digest": model_digest, "counts": counts,
            "database_name": db_name, "quality": quality, "ontology_turtle": turtle}
