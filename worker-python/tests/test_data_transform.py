import json
import zipfile
from pathlib import Path

import duckdb
import pytest
from openpyxl import Workbook

from app.data_transform import run_transform, sample_transform, validate_transform_sql
from app.models import TransformPlan
from app.transform_plan import compile_plan


def write_csv(path: Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")


def test_joins_multiple_structured_inputs_and_writes_quality_report(tmp_path: Path) -> None:
    sales = tmp_path / "sales.csv"
    customers = tmp_path / "customers.csv"
    write_csv(sales, "customer_id,amount\nC001,1200.5\nC003,450.25\n")
    write_csv(customers, "customer_id,name\nC001,华东一部\nC002,华南二部\n")
    inputs = [
        {"alias": "sales", "name": "sales.csv", "path": str(sales)},
        {"alias": "customers", "name": "customers.csv", "path": str(customers)},
    ]
    script = """
        SELECT s.customer_id, c.name, s.amount
        FROM sales s LEFT JOIN customers c ON s.customer_id = c.customer_id
    """

    sample = sample_transform(inputs, script)
    assert sample["sampleRowCount"] == 2
    assert sample["rows"][1]["name"] is None

    output = tmp_path / "result.zip"
    report = run_transform(inputs, script, output, output_format="csv")
    assert report["rowCount"] == 2
    assert report["columnCount"] == 3
    with zipfile.ZipFile(output) as archive:
        assert set(archive.namelist()) == {"result.csv", "quality.json", "manifest.json"}
        manifest = json.loads(archive.read("manifest.json"))
        assert manifest["output"]["format"] == "csv"


def test_preview_materializes_only_one_hundred_rows_in_duckdb_sandbox(tmp_path: Path) -> None:
    source = tmp_path / "large.csv"
    write_csv(source, "id,value\n" + "\n".join(f"{index},{index * 2}" for index in range(250)) + "\n")

    sample = sample_transform(
        [{"alias": "source", "name": source.name, "path": str(source), "source_kind": "DATABASE_PREVIEW"}],
        "SELECT * FROM source",
    )

    assert sample["sampleRowCount"] == 100
    assert sample["inputs"][0]["rowCount"] == 100
    assert sample["sandbox"] == {
        "engine": "DuckDB", "mode": "EPHEMERAL_SAMPLE", "rowLimitPerInput": 100, "inputCount": 1,
    }


@pytest.mark.parametrize("script", [
    "DROP TABLE sales",
    "SELECT * FROM read_csv_auto('/tmp/private.csv')",
    "SELECT * FROM unknown_data",
    "SELECT * FROM sales; SELECT * FROM sales",
])
def test_rejects_unsafe_or_unconnected_sql(script: str) -> None:
    with pytest.raises(ValueError):
        validate_transform_sql(script, ["sales"])


def test_structured_plan_compiles_without_arbitrary_file_access() -> None:
    plan = TransformPlan.model_validate({
        "goal": "过滤有效订单并按区域汇总",
        "steps": [
            {"id": "active", "type": "filter", "input": "orders", "title": "排除取消订单",
             "condition": {"column": "status", "operator": "not_equal", "value": "取消"}},
            {"id": "summary", "type": "aggregate", "input": "active", "title": "按区域汇总",
             "group_by": ["region"], "metrics": [{"name": "sales", "function": "sum", "column": "amount"}]},
        ],
    })
    script, steps = compile_plan(plan, ["orders"])
    assert len(steps) == 2
    assert "read_csv" not in script.lower()
    assert "SUM(\"amount\")" in script


def test_structured_plan_adds_business_title_when_model_omits_it() -> None:
    plan = TransformPlan.model_validate({
        "goal": "保留订单字段",
        "steps": [{"id": "result", "type": "select", "input": "orders", "columns": ["order_id"]}],
    })

    assert plan.steps[0].title == "选择需要的字段"


def test_mixed_source_workflow_produces_parquet_and_step_lineage(tmp_path: Path) -> None:
    orders = tmp_path / "orders.csv"
    write_csv(orders, "order_id,store_code,status,amount,currency\nO1,S1,完成,100,CNY\nO2,S2,取消,50,CNY\nO3,S2,完成,200,CNY\n")

    stores = tmp_path / "stores.xlsx"
    workbook = Workbook()
    sheet = workbook.active
    sheet.title = "门店维表"
    sheet.append(["store_code", "region"])
    sheet.append(["S1", "华南"])
    sheet.append(["S2", "华东"])
    workbook.save(stores)

    rates = tmp_path / "api-rates.json"
    rates.write_text(json.dumps([{"currency": "CNY", "rate": 1.0}]), encoding="utf-8")

    targets = tmp_path / "db-targets.parquet"
    connection = duckdb.connect()
    connection.execute("CREATE TABLE targets(region VARCHAR, target DOUBLE)")
    connection.execute("INSERT INTO targets VALUES ('华南', 90), ('华东', 180)")
    connection.execute("COPY targets TO ? (FORMAT PARQUET)", [str(targets)])
    connection.close()

    inputs = [
        {"alias": "orders", "name": "orders.csv", "path": str(orders), "source_kind": "FILE"},
        {"alias": "stores", "name": "stores.xlsx", "path": str(stores), "sheet_name": "门店维表", "source_kind": "FILE"},
        {"alias": "rates", "name": "api-rates.json", "path": str(rates), "source_kind": "API_EXTRACT"},
        {"alias": "targets", "name": "db-targets.parquet", "path": str(targets), "source_kind": "DATABASE_EXTRACT"},
    ]
    plan = TransformPlan.model_validate({
        "goal": "过滤有效订单，关联门店、汇率和数据库目标后汇总",
        "steps": [
            {"id": "selected", "type": "select", "input": "orders", "title": "保留分析字段",
             "columns": ["order_id", "store_code", "status", "amount", "currency"]},
            {"id": "active", "type": "filter", "input": "selected", "title": "排除取消订单",
             "condition": {"column": "status", "operator": "not_equal", "value": "取消"}},
            {"id": "with_store", "type": "join", "left": "active", "right": "stores", "title": "补充门店区域",
             "keys": [{"left": "store_code", "right": "store_code"}], "expected_cardinality": "MANY_TO_ONE",
             "right_columns": ["region"]},
            {"id": "with_rate", "type": "join", "left": "with_store", "right": "rates", "title": "补充汇率",
             "keys": [{"left": "currency", "right": "currency"}], "expected_cardinality": "MANY_TO_ONE",
             "right_columns": ["rate"]},
            {"id": "converted", "type": "derive", "input": "with_rate", "title": "计算折算金额",
             "expressions": {"converted_amount": "amount * rate"}},
            {"id": "summary", "type": "aggregate", "input": "converted", "title": "按区域汇总",
             "group_by": ["region"], "metrics": [{"name": "sales", "function": "sum", "column": "converted_amount"}]},
            {"id": "result", "type": "join", "left": "summary", "right": "targets", "title": "关联经营目标",
             "keys": [{"left": "region", "right": "region"}], "expected_cardinality": "MANY_TO_ONE",
             "right_columns": ["target"]},
        ],
        "validations": [
            {"type": "unique", "input": "stores", "columns": ["store_code"]},
            {"type": "join_match_rate", "step": "with_store", "minimum": 1.0},
            {"type": "row_count", "input": "result", "minimum": 2, "maximum": 2},
        ],
        "output": {"format": "parquet"},
    })

    preview = sample_transform(inputs, plan=plan)
    assert preview["stage"] == "PREVIEWED"
    assert [step["outputRowCount"] for step in preview["stepReports"]][:2] == [3, 2]
    assert preview["stepReports"][2]["join"]["matchRate"] == 1.0

    output = tmp_path / "mixed-result.zip"
    report = run_transform(inputs, "", output, plan=plan, output_format="parquet")
    assert report["stage"] == "VERIFIED"
    assert report["rowCount"] == 2
    assert {item["sourceKind"] for item in report["inputs"]} == {"FILE", "API_EXTRACT", "DATABASE_EXTRACT"}
    with zipfile.ZipFile(output) as archive:
        assert set(archive.namelist()) == {"result.parquet", "quality.json", "manifest.json"}


def test_rejects_many_to_one_join_when_dimension_key_is_duplicated(tmp_path: Path) -> None:
    facts = tmp_path / "facts.csv"
    dimensions = tmp_path / "dimensions.csv"
    write_csv(facts, "key,amount\nA,10\n")
    write_csv(dimensions, "key,name\nA,first\nA,second\n")
    plan = TransformPlan.model_validate({
        "goal": "关联维表",
        "steps": [{"id": "result", "type": "join", "left": "facts", "right": "dimensions",
                   "keys": [{"left": "key", "right": "key"}], "expected_cardinality": "MANY_TO_ONE"}],
    })
    inputs = [
        {"alias": "facts", "name": facts.name, "path": str(facts)},
        {"alias": "dimensions", "name": dimensions.name, "path": str(dimensions)},
    ]
    with pytest.raises(ValueError, match="重复关联键"):
        sample_transform(inputs, plan=plan)


def test_full_join_keeps_differently_named_right_key_for_unmatched_rows(tmp_path: Path) -> None:
    actual = tmp_path / "actual.csv"
    adjustments = tmp_path / "adjustments.csv"
    write_csv(actual, "actual_code,amount\nA,10\n")
    write_csv(adjustments, "adjustment_code,extra\nB,5\n")
    plan = TransformPlan.model_validate({
        "goal": "保留两边未匹配业务对象",
        "steps": [
            {"id": "joined", "type": "join", "left": "actual", "right": "adjustments",
             "join_type": "full", "keys": [{"left": "actual_code", "right": "adjustment_code"}],
             "right_columns": ["adjustment_code", "extra"]},
            {"id": "result", "type": "derive", "input": "joined",
             "expressions": {"business_code": "coalesce(actual_code, adjustment_code)"}},
        ],
    })

    preview = sample_transform([
        {"alias": "actual", "name": actual.name, "path": str(actual)},
        {"alias": "adjustments", "name": adjustments.name, "path": str(adjustments)},
    ], plan=plan)

    assert preview["sampleRowCount"] == 2
    assert {row["business_code"] for row in preview["rows"]} == {"A", "B"}


def test_not_null_validation_accepts_multiple_columns(tmp_path: Path) -> None:
    source = tmp_path / "source.csv"
    write_csv(source, "period,region_code,amount\n2026-08,R01,10\n")
    plan = TransformPlan.model_validate({
        "goal": "检查业务主键并输出明细",
        "steps": [
            {"id": "result", "type": "select", "input": "source",
             "columns": ["period", "region_code", "amount"]},
        ],
        "validations": [
            {"type": "not_null", "input": "result", "columns": ["period", "region_code"]},
        ],
    })

    report = run_transform(
        [{"alias": "source", "name": source.name, "path": str(source)}],
        "", tmp_path / "result.zip", plan=plan,
    )

    validation = report["validations"][0]
    assert validation["passed"] is True
    assert validation["observed"] == {"period": 0, "region_code": 0}
