import copy

import pytest

from app.tacit.spec import empty_spec, item_digest, locate_quote, section_schema, validate_batch, validate_spec


@pytest.fixture
def ledger():
    text = "采购单应记录单号。\n金额在100元及以上时须审批。"
    first, second = text.splitlines()
    source = {"source_id": "S1", "title": "采购政策", "source_type": "asset", "version": "3", "read_status": "read"}
    def citation(clause, quote):
        return {"source_id": "S1", "clause_id": clause, "quote": quote}

    spec = {
        **empty_spec(), "scope": {"goal": "确定采购审批要求", "in_scope": ["采购"]},
        "sources": [dict(source, clauses=[
            {"clause_id": "c1", "quote": first, "locator": locate_quote(text, first),
             "coverage": "extracted", "data_object_ids": ["purchase"]},
            {"clause_id": "c2", "quote": second, "locator": locate_quote(text, second),
             "coverage": "extracted", "rule_ids": ["threshold"]},
        ])],
        "data_objects": [{"object_id": "purchase", "name": "采购单", "identity_basis": "单号",
                          "fields": [{"name": "单号", "business_name": "单号", "data_type": "文本"}],
                          "evidence": [citation("c1", first)], "status": "source_supported"}],
        "rules": [{"rule_id": "threshold", "name": "审批门槛", "scope": "采购单",
                   "condition": {"operator": "predicate", "field": "金额", "comparator": "gte", "value": 100, "unit": "元"},
                   "results": ["提交审批"], "inputs": ["金额"], "outputs": ["处理要求"],
                   "process_disposition": "independent", "process_reason": "单独判断申请",
                   "evidence": [citation("c2", second)], "status": "source_supported"}],
        "acceptance_cases": [{"case_id": "exact", "name": "恰好100元", "kind": "boundary", "rule_ids": ["threshold"],
                              "inputs": {"金额": 100}, "expected_outputs": {"处理要求": "提交审批"}, "status": "defined"}],
    }
    return spec, [{**source, "text": text}]


def codes(ledger):
    return {e["code"] for e in validate_spec(*ledger)}


def test_clean_ledger_and_strict_batch(ledger):
    spec, _ = ledger
    assert validate_spec(*ledger) == []
    for section in ["sources", "data_objects", "rules", "acceptance_cases"]:
        validate_batch(section, spec[section], None)
        assert "properties" in section_schema(section)
    rule = spec["rules"][0]
    for batch in ([dict(rule, invented=True)], [rule] * 2, [dict(rule, rule_id=1)], [], [rule] * 6):
        with pytest.raises(ValueError):
            validate_batch("rules", batch, None)
    validate_batch("scope", [], spec["scope"])
    with pytest.raises(ValueError):
        validate_batch("scope", [rule], spec["scope"])
    assert "schema_invalid" in codes(({**spec, "special_permission": True}, ledger[1]))


def test_coverage_and_source_version_and_cross_project(ledger):
    spec, catalog = ledger
    catalog[0].update(read_status="partial", version="4")
    assert {"source_unread", "source_version_mismatch"} <= codes(ledger)
    assert "unknown_source" in codes((spec, []))
    spec["sources"].clear()
    assert "source_omitted" in codes(ledger)


def test_anchor_and_full_text_disposition(ledger):
    spec, catalog = ledger
    clause = spec["sources"][0]["clauses"][0]
    clause["locator"] = "第200页"
    assert {"invalid_locator", "unclassified_source_text"} <= codes(ledger)
    catalog[0]["locations"] = [{"locator": "第200页", "start": 0, "end": len(clause["quote"])}]
    assert not codes(ledger)
    clause["quote"] = "此处没有这段话"
    assert {"fabricated_quote", "invalid_evidence"} <= codes(ledger)
    assert locate_quote("📄甲 乙\n 丙丁", "甲乙丙") == "字符[1,7)"
    assert locate_quote("甲乙甲乙", "甲乙", 2) == "字符[2,4)"


def test_facts_bind_to_classified_clauses_both_directions(ledger):
    spec, catalog = ledger
    del spec["sources"][0]["clauses"][0]["data_object_ids"]
    assert {"coverage_without_evidence", "unbound_clause"} <= codes(ledger)
    spec["sources"][0].update(disposition="excluded", scope_reason="暂不纳入")
    assert {"excluded_extraction", "excluded_source_used"} <= codes(ledger)
    spec["data_objects"][0]["evidence"].clear()
    assert "missing_evidence" in codes(ledger)


def test_confirmations_bind_to_item_not_just_user_text(ledger):
    spec, catalog = ledger
    rule = spec["rules"][0]
    rule["status"] = "user_confirmed"
    assert "invalid_confirmation" in codes(ledger)
    catalog[0]["source_type"] = spec["sources"][0]["source_type"] = "user"
    catalog[0]["confirmed_items"] = {"threshold": item_digest(rule)}
    assert not codes(ledger)
    rule["condition"]["value"] = 90
    assert "invalid_confirmation" in codes(ledger)


@pytest.mark.parametrize("condition,kind", [
    ({"operator": "unresolved"}, "unresolved_condition"),
    ({"operator": "all", "clauses": [{"operator": "unresolved"}]}, "condition_operands"),
    ({"operator": "predicate", "field": "金额", "comparator": "lte", "value": "315", "unit": "kVA"}, "numeric_boundary"),
    ({"operator": "predicate", "field": "金额", "comparator": "lte", "value": 315}, "numeric_unit"),
    ({"operator": "predicate", "field": "金额", "comparator": "in", "value": []}, "set_members"),
    ({"operator": "predicate"}, "predicate_missing"),
    ({"operator": "expression", "expression": "1+2"}, "condition_expression"),
    ({"operator": "always"}, "unconditional_reason"),
])
def test_condition_contract(ledger, condition, kind):
    ledger[0]["rules"][0]["condition"] = condition
    assert kind in codes(ledger)


def test_cases_gaps_and_default_are_not_assumed(ledger):
    spec, _ = ledger
    spec["acceptance_cases"] = []
    spec["gaps"] = [{"gap_id": "g1", "question": "困难依据？", "impact": "计量方式待复核"}]
    assert {"rule_without_case", "blocking_gap"} <= codes(ledger)
    spec["gaps"][0].update(status="resolved", resolution="附件已提交")
    assert "unsupported_resolution" in codes(ledger)
    field = spec["data_objects"][0]["fields"][0]
    field.update(data_type="decimal(10,2)", default_value=1000)
    assert {"unconfirmed_default", "field_unit"} <= codes(ledger)
    field.update(default_status="source_supported", unit="元")
    assert "unconfirmed_default" in codes(ledger)


def test_schema_tables_and_relationships(ledger):
    spec, _ = ledger
    spec["data_tables"] = [{"table_id": "t", "name": "missing_table", "locator": "邮件",
                            "columns": [{"name": "x", "data_type": "int", "default_value": 5}, {"name": "X"}]}]
    spec["data_objects"][0]["relationships"] = [{"name": "所属", "target_object_id": "absent"}]
    assert {"table_literal_evidence", "table_unread", "table_definition", "unresolved_table", "table_default_status",
            "duplicate_table_column_name", "relationship_definition", "dangling_reference", "unresolved_relationship"} <= codes(ledger)


def test_parent_cycle_and_dangling_branches(ledger):
    spec, _ = ledger
    p = {"process_id": "p", "name": "审批", "disposition": "subprocess", "parent_process_id": "q",
         "branches": [{"branch_id": "b", "from_step_id": "missing", "to_step_id": "end",
                       "condition": {"operator": "always", "expression": "结束"}}]}
    spec["processes"] = [p, dict(copy.deepcopy(p), process_id="q", parent_process_id="p")]
    assert {"process_parent_cycle", "dangling_reference", "process_missing"} <= codes(ledger)
