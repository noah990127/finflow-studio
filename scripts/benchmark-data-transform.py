#!/usr/bin/env python3
"""Generate a wide-table business scenario and benchmark the FinFlow transform runtime."""

from __future__ import annotations

import argparse
import json
import sys
import tempfile
import time
from pathlib import Path

import duckdb
from openpyxl import Workbook


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "worker-python"))

from app.data_transform import run_transform  # noqa: E402
from app.models import TransformPlan  # noqa: E402


def create_fact(path: Path, rows: int, columns: int) -> None:
    fixed = [
        "range AS order_id",
        "'S' || LPAD(CAST(range % 1000 AS VARCHAR), 4, '0') AS store_code",
        "CASE WHEN range % 20 = 0 THEN '取消' ELSE '完成' END AS status",
        "CAST((range % 10000) / 10.0 AS DOUBLE) AS amount",
        "'CNY' AS currency",
    ]
    extras = [f"range % {index + 7} AS extra_{index}" for index in range(1, max(1, columns - len(fixed)) + 1)]
    query = "SELECT " + ", ".join((fixed + extras)[:columns]) + f" FROM range({rows})"
    with duckdb.connect() as connection:
        connection.execute(f"COPY ({query}) TO ? (FORMAT PARQUET, COMPRESSION ZSTD)", [str(path)])


def create_dimension(path: Path, columns: int) -> None:
    workbook = Workbook(write_only=True)
    sheet = workbook.create_sheet("门店维表")
    fixed = ["store_code", "region"]
    headers = (fixed + [f"dim_{index}" for index in range(1, max(1, columns - len(fixed)) + 1)])[:columns]
    sheet.append(headers)
    for index in range(1000):
        values = [f"S{index:04d}", f"区域{index % 10}"]
        values.extend(f"V{index % (extra + 3)}" for extra in range(1, len(headers) - len(fixed) + 1))
        sheet.append(values[:columns])
    workbook.save(path)


def plan() -> TransformPlan:
    return TransformPlan.model_validate({
        "goal": "过滤取消订单，关联门店和汇率，按区域汇总并对比经营目标",
        "steps": [
            {"id": "selected", "type": "select", "input": "orders", "title": "只保留分析所需字段",
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
            {"type": "row_count", "input": "result", "minimum": 10, "maximum": 10},
        ],
        "output": {"format": "parquet"},
    })


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--rows", type=int, default=1_000_000)
    parser.add_argument("--fact-columns", type=int, default=300)
    parser.add_argument("--dimension-columns", type=int, default=300)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="finflow-transform-benchmark-") as directory:
        root = Path(directory)
        fact = root / "经营明细.parquet"
        dimension = root / "门店维表.xlsx"
        rates = root / "接口汇率.json"
        targets = root / "数据库目标.parquet"
        create_fact(fact, args.rows, args.fact_columns)
        create_dimension(dimension, args.dimension_columns)
        rates.write_text(json.dumps([{"currency": "CNY", "rate": 1.0}], ensure_ascii=False), encoding="utf-8")
        with duckdb.connect() as connection:
            connection.execute(
                "COPY (SELECT '区域' || range AS region, 90000000 + range * 1000000 AS target "
                "FROM range(10)) TO ? (FORMAT PARQUET)", [str(targets)]
            )
        inputs = [
            {"alias": "orders", "name": fact.name, "path": str(fact), "source_kind": "FILE"},
            {"alias": "stores", "name": dimension.name, "path": str(dimension),
             "sheet_name": "门店维表", "source_kind": "FILE"},
            {"alias": "rates", "name": rates.name, "path": str(rates), "source_kind": "HTTP_API"},
            {"alias": "targets", "name": targets.name, "path": str(targets), "source_kind": "DATABASE"},
        ]
        output = root / "result.zip"
        started = time.perf_counter()
        report = run_transform(inputs, "", output, plan=plan(), output_format="parquet")
        elapsed = time.perf_counter() - started
        print(json.dumps({
            "inputRows": args.rows,
            "factColumns": args.fact_columns,
            "dimensionRows": 1000,
            "dimensionColumns": args.dimension_columns,
            "elapsedSeconds": round(elapsed, 3),
            "outputRows": report["rowCount"],
            "stage": report["stage"],
            "checks": report["checks"],
            "stepRows": {item["id"]: item["outputRowCount"] for item in report["stepReports"]},
        }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
