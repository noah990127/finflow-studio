from __future__ import annotations

import asyncio
import csv
import hashlib
import json
import os
import re
import shutil
import tempfile
import zipfile
from pathlib import Path
from typing import Any, Optional

import duckdb
import sqlglot
from openpyxl import load_workbook
from sqlglot import exp

from .llm import llm
from .models import TransformGenerateRequest, TransformGenerateResponse, TransformPlan
from .transform_plan import compile_plan, quote_identifier, quote_relation


ALIAS_PATTERN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,63}$")
BLOCKED_FUNCTIONS = {
    "read_csv", "read_csv_auto", "read_parquet", "read_json", "read_json_auto",
    "http_get", "http_post", "glob", "parquet_scan", "csv_scan",
}
TRANSFORM_GENERATION_TIMEOUT_SECONDS = 90
TRANSFORM_REPAIR_TIMEOUT_SECONDS = 60


def _json_value(value: Any) -> Any:
    if value is None or isinstance(value, (str, int, float, bool)):
        return value
    return str(value)


def _escape_literal(value: str) -> str:
    return value.replace("'", "''")


def _file_hash(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def validate_transform_sql(script: str, aliases: list[str]) -> list[str]:
    sql = script.strip().rstrip(";").strip()
    if not sql:
        raise ValueError("加工脚本不能为空")
    try:
        statements = sqlglot.parse(sql, read="duckdb")
    except sqlglot.errors.ParseError as exception:
        raise ValueError(f"加工脚本语法不正确：{exception}") from exception
    if len(statements) != 1 or not isinstance(statements[0], exp.Query):
        raise ValueError("加工脚本只能包含一条只读查询")
    statement = statements[0]
    blocked = (exp.Insert, exp.Update, exp.Delete, exp.Create, exp.Drop, exp.Command,
               exp.Copy, exp.Attach, exp.Pragma, exp.Transaction, exp.Merge)
    if any(statement.find(kind) is not None for kind in blocked):
        raise ValueError("加工脚本包含不允许的写入或系统操作")
    functions = {
        str(node.name).lower() for node in statement.find_all(exp.Func)
        if getattr(node, "name", None)
    }
    if functions & BLOCKED_FUNCTIONS:
        raise ValueError("加工脚本不能自行读取文件或网络数据")
    allowed = {alias.lower() for alias in aliases}
    ctes = {str(node.alias_or_name).lower() for node in statement.find_all(exp.CTE)}
    tables = {str(node.name).lower() for node in statement.find_all(exp.Table)} - ctes
    unknown = sorted(tables - allowed)
    if unknown:
        raise ValueError("脚本引用了未连接的数据：" + "、".join(unknown))
    return ["脚本为单条只读查询", "脚本仅引用当前节点已连接的数据"]


def _csv_view_sql(path: Path, suffix: str) -> str:
    delimiter = "\\t" if suffix == ".tsv" else ","
    return (
        "read_csv_auto('%s', header=true, delim='%s', sample_size=200000, "
        "ignore_errors=false, all_varchar=false)"
    ) % (_escape_literal(str(path)), delimiter)


def _xlsx_to_csv(source: Path, target: Path, sheet_name: Optional[str]) -> None:
    workbook = load_workbook(source, read_only=True, data_only=True, keep_links=False)
    try:
        if sheet_name and sheet_name not in workbook.sheetnames:
            raise ValueError(f"工作表不存在：{sheet_name}")
        sheet = workbook[sheet_name] if sheet_name else workbook[workbook.sheetnames[0]]
        with target.open("w", encoding="utf-8-sig", newline="") as stream:
            writer = csv.writer(stream)
            for row in sheet.iter_rows(values_only=True):
                writer.writerow(["" if value is None else value for value in row])
    finally:
        workbook.close()


def _calamine_to_csv(source: Path, target: Path, sheet_name: Optional[str]) -> None:
    try:
        from python_calamine import CalamineWorkbook
    except ImportError as exception:
        raise ValueError(
            f"读取 {source.suffix.lower()} 需要部署 python-calamine；请安装依赖或先转换为 .xlsx、CSV、Parquet"
        ) from exception
    workbook = CalamineWorkbook.from_path(str(source))
    names = list(workbook.sheet_names)
    selected = sheet_name or (names[0] if names else "")
    if not selected or selected not in names:
        raise ValueError(f"工作表不存在：{selected or sheet_name}")
    rows = workbook.get_sheet_by_name(selected).to_python(skip_empty_area=False)
    with target.open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.writer(stream)
        for row in rows:
            writer.writerow(["" if value is None else value for value in row])


def _load_excel_extension(connection: duckdb.DuckDBPyConnection) -> bool:
    try:
        connection.execute("LOAD excel")
        return True
    except duckdb.Error:
        return False


def _source_sql(connection: duckdb.DuckDBPyConnection, path: Path, original_name: str,
                sheet_name: Optional[str], temp_dir: Path, warnings: list[str]) -> str:
    suffix = Path(original_name).suffix.lower()
    escaped = _escape_literal(str(path))
    if suffix in {".csv", ".tsv", ".txt"}:
        return _csv_view_sql(path, suffix)
    if suffix == ".parquet":
        return f"read_parquet('{escaped}')"
    if suffix in {".json", ".jsonl", ".ndjson"}:
        return f"read_json_auto('{escaped}')"
    if suffix in {".xlsx", ".xlsm"}:
        if _load_excel_extension(connection):
            options = [f"'{escaped}'", "header=true"]
            if sheet_name:
                options.append(f"sheet='{_escape_literal(sheet_name)}'")
            return "read_xlsx(" + ", ".join(options) + ")"
        converted = temp_dir / (path.stem + "-" + str(abs(hash(str(path)))) + ".csv")
        try:
            _calamine_to_csv(path, converted, sheet_name)
            warnings.append("当前环境未预装 DuckDB Excel 扩展，已使用 Calamine 只读解析 Excel")
        except ValueError as exception:
            if "python-calamine" not in str(exception):
                raise
            _xlsx_to_csv(path, converted, sheet_name)
            warnings.append("当前环境未预装 DuckDB Excel 扩展和 Calamine，已使用 openpyxl 兼容解析；生产环境应安装标准依赖")
        return _csv_view_sql(converted, ".csv")
    if suffix in {".xls", ".xlsb"}:
        converted = temp_dir / (path.stem + "-" + str(abs(hash(str(path)))) + ".csv")
        _calamine_to_csv(path, converted, sheet_name)
        warnings.append(f"{suffix} 已通过 Calamine 只读规范化，未执行宏或公式代码")
        return _csv_view_sql(converted, ".csv")
    raise ValueError(f"暂不支持加工该文件格式：{suffix or original_name}")


def _connection(temp_dir: Path) -> duckdb.DuckDBPyConnection:
    connection = duckdb.connect(database=":memory:")
    cpu_count = max(1, os.cpu_count() or 1)
    threads = max(1, min(8, cpu_count // 2 or 1))
    memory_mb = int(os.getenv("FINFLOW_DUCKDB_MEMORY_MB", "2048"))
    temp_path = temp_dir / "duckdb-temp"
    temp_path.mkdir(parents=True, exist_ok=True)
    connection.execute(f"SET threads TO {threads}")
    connection.execute(f"SET memory_limit = '{max(512, memory_mb)}MB'")
    connection.execute("SET temp_directory = ?", [str(temp_path)])
    connection.execute("SET preserve_insertion_order = false")
    return connection


def register_inputs(connection: duckdb.DuckDBPyConnection, inputs: list[dict[str, Any]],
                    temp_dir: Path, row_limit: int | None = None) -> tuple[list[dict[str, Any]], list[str]]:
    aliases: set[str] = set()
    manifests: list[dict[str, Any]] = []
    warnings: list[str] = []
    for item in inputs:
        alias = str(item.get("alias", ""))
        if not ALIAS_PATTERN.fullmatch(alias) or alias.lower() in aliases:
            raise ValueError(f"数据别名不正确或重复：{alias}")
        aliases.add(alias.lower())
        path = Path(str(item["path"]))
        if not path.is_file():
            raise ValueError(f"输入数据不存在：{item.get('name', alias)}")
        item_warnings: list[str] = []
        source = _source_sql(connection, path, str(item.get("name", path.name)),
                             item.get("sheet_name"), temp_dir, item_warnings)
        if row_limit is None:
            connection.execute(f"CREATE VIEW {quote_relation(alias)} AS SELECT * FROM {source}")
        else:
            limited = max(1, min(row_limit, 500))
            connection.execute(
                f"CREATE TEMP TABLE {quote_relation(alias)} AS SELECT * FROM {source} LIMIT {limited}"
            )
        row_count = int(connection.execute(f"SELECT COUNT(*) FROM {quote_relation(alias)}").fetchone()[0])
        column_count = len(connection.execute(f"DESCRIBE SELECT * FROM {quote_relation(alias)}").fetchall())
        manifests.append({
            "alias": alias, "name": str(item.get("name", path.name)),
            "sourceKind": str(item.get("source_kind", "FILE")),
            "rowCount": row_count, "columnCount": column_count,
            "contentHash": _file_hash(path), "warnings": item_warnings,
        })
        warnings.extend(item_warnings)
    return manifests, warnings


def profile_tabular(path: Path, original_name: str, sheet_name: Optional[str] = None) -> dict[str, Any]:
    with tempfile.TemporaryDirectory(prefix="finflow-profile-") as directory:
        temp_dir = Path(directory)
        connection = _connection(temp_dir)
        warnings: list[str] = []
        try:
            source = _source_sql(connection, path, original_name, sheet_name, temp_dir, warnings)
            description = connection.execute(f"DESCRIBE SELECT * FROM {source}").fetchall()
            cursor = connection.execute(f"SELECT * FROM {source} LIMIT 20")
            names = [item[0] for item in cursor.description]
            rows = [dict(zip(names, (_json_value(value) for value in row))) for row in cursor.fetchall()]
            return {
                "name": original_name,
                "columns": [{"name": row[0], "data_type": row[1],
                             "nullable": str(row[2]).upper() != "NO"} for row in description],
                "sample_rows": rows, "estimated_rows": None, "warnings": warnings,
            }
        finally:
            connection.close()


def _parse_json_response(content: str) -> dict[str, Any]:
    clean = content.strip()
    if clean.startswith("```"):
        clean = re.sub(r"^```(?:json)?\s*", "", clean)
        clean = re.sub(r"\s*```$", "", clean)
    try:
        result = json.loads(clean)
    except json.JSONDecodeError:
        match = re.search(r"\{.*\}", clean, re.DOTALL)
        if not match:
            raise ValueError("大模型没有返回可解析的加工计划")
        result = json.loads(match.group(0))
    if not isinstance(result, dict):
        raise ValueError("大模型返回的加工计划格式不正确")
    return result


def _transform_plan_protocol() -> str:
    schema = json.dumps(TransformPlan.model_json_schema(), ensure_ascii=False, separators=(",", ":"))
    return f"""输出对象只允许包含 plan、summary、assumptions、quality_rules、warnings。
plan 必须严格符合下面的 JSON Schema，不得使用同义字段或添加额外字段：
{schema}
特别注意：plan.goal 必填；condition 使用 column；casts 是字段名到目标类型的对象；
metrics 每项使用 name/function/column；join 使用 left/right/keys，keys 每项使用 left/right；
derive 使用 expressions 对象且值必须是表达式字符串；output 只使用 format/partition_by。
每个步骤只输出当前 type 实际使用的字段，省略所有空数组、空对象、空字符串和默认字段。"""


async def _complete_transform(system: str, user: str, timeout_seconds: int) -> str:
    try:
        content = await asyncio.wait_for(llm.complete(system, user), timeout=timeout_seconds)
    except asyncio.TimeoutError as exception:
        raise RuntimeError(f"加工方案生成超过 {timeout_seconds} 秒，请缩小输入范围或稍后重试") from exception
    if not content:
        raise RuntimeError("大模型尚未配置，无法生成加工计划")
    return content


def _validated_plan(result: dict[str, Any], aliases: list[str]) -> tuple[TransformPlan, str]:
    plan = TransformPlan.model_validate(result.get("plan"))
    script, _ = compile_plan(plan, aliases)
    validate_transform_sql(script, aliases)
    return plan, script


def _fallback_plan(request: TransformGenerateRequest) -> TransformPlan:
    alias = request.inputs[0].alias
    columns = [column.name for column in request.inputs[0].columns]
    return TransformPlan.model_validate({
        "version": "1.0", "goal": request.requirements,
        "steps": [{"id": "result", "type": "select", "input": alias, "columns": columns,
                   "title": "保留首个输入数据"}],
        "validations": [{"type": "row_count", "input": "result", "minimum": 0, "severity": "warning"}],
        "output": {"format": "parquet"},
    })


async def generate_transform(request: TransformGenerateRequest) -> TransformGenerateResponse:
    aliases = [item.alias for item in request.inputs]
    schema = [{
        "alias": item.alias, "name": item.name,
        "columns": [{"name": column.name, "type": column.data_type} for column in item.columns],
        "estimatedRows": item.estimated_rows,
    } for item in request.inputs]
    if not llm.configured:
        plan = _fallback_plan(request)
        script, _ = compile_plan(plan, aliases)
        return TransformGenerateResponse(
            plan=plan, script=script,
            summary="大模型未配置，已生成保留首个输入数据的基础加工计划",
            mode="local-fallback", assumptions=["未自动解释复杂加工要求，请配置大模型后重新生成"],
            quality_rules=["加工计划协议校验通过", "输出将重新读取并核对行列数"],
            warnings=["当前计划是基础回退方案"],
        )
    protocol = _transform_plan_protocol()
    system = f"""你是 FinFlow 的数据加工规划器。把业务人员的要求转换成严格、可复核的 TransformPlan JSON，禁止输出 Python 或自由 SQL。
只使用输入中真实存在的别名和字段。先 select 裁剪无关宽表字段，再过滤、转换、关联和聚合。
join 必须声明 expected_cardinality；维表通常 MANY_TO_ONE。对关键关联添加 unique 和 join_match_rate 检查。
支持步骤 type：select/filter/rename/cast/deduplicate/join/derive/aggregate/union/sort/limit。
filter condition operator 只能是 equal/not_equal/greater_than/greater_or_equal/less_than/less_or_equal/in/not_in/contains/starts_with/ends_with/is_null/is_not_null。
derive 只能使用字段算术和 abs/coalesce/concat/date_diff/date_part/date_trunc/floor/ceil/greatest/least/lower/nullif/regexp_replace/replace/round/strftime/substr/substring/trim/try_cast/upper。
aggregate metric function 只能是 sum/count/count_distinct/avg/min/max。
plan.version 固定为 1.0，默认 output.format 为 parquet。
{protocol}"""
    user = json.dumps({"requirements": request.requirements, "inputs": schema}, ensure_ascii=False, default=str)
    content = await _complete_transform(system, user, TRANSFORM_GENERATION_TIMEOUT_SECONDS)
    result = _parse_json_response(content)
    try:
        plan, script = _validated_plan(result, aliases)
    except Exception as first_exception:
        repair_system = f"""你正在修正一个被严格协议拒绝的数据加工计划。只修正结构和引用错误，不改变用户目标。
只返回完整 JSON 对象，不要解释，不要 Markdown。
{protocol}"""
        repair_user = json.dumps({
            "validation_error": str(first_exception)[:8_000],
            "original_request": {"requirements": request.requirements, "inputs": schema},
            "rejected_output": result,
        }, ensure_ascii=False, default=str)
        try:
            repaired = _parse_json_response(await _complete_transform(
                repair_system, repair_user, TRANSFORM_REPAIR_TIMEOUT_SECONDS
            ))
            plan, script = _validated_plan(repaired, aliases)
            result = repaired
        except Exception as repair_exception:
            raise ValueError(
                "大模型两次返回的加工计划都不符合协议，请调整加工要求后重试："
                + str(repair_exception)[:2_000]
            ) from repair_exception
    return TransformGenerateResponse(
        plan=plan, script=script,
        summary=str(result.get("summary", "已根据业务要求生成可复核的加工计划")),
        mode=f"{llm.provider}:{llm.model}",
        assumptions=[str(item) for item in result.get("assumptions", [])],
        quality_rules=[str(item) for item in result.get("quality_rules", [])] + ["加工计划协议校验通过"],
        warnings=[str(item) for item in result.get("warnings", [])],
    )


def _resolve_script(inputs: list[dict[str, Any]], script: str,
                    plan: dict[str, Any] | TransformPlan | None) -> tuple[str, TransformPlan | None]:
    aliases = [str(item["alias"]) for item in inputs]
    if plan:
        parsed = plan if isinstance(plan, TransformPlan) else TransformPlan.model_validate(plan)
        compiled, _ = compile_plan(parsed, aliases)
        validate_transform_sql(compiled, aliases)
        return compiled, parsed
    validate_transform_sql(script, aliases)
    return script.strip().rstrip(";"), None


def _step_reports(connection: duckdb.DuckDBPyConnection, inputs: list[dict[str, Any]],
                  plan: TransformPlan | None) -> list[dict[str, Any]]:
    if plan is None:
        return []
    aliases = [str(item["alias"]) for item in inputs]
    reports: list[dict[str, Any]] = []
    _, compiled_steps = compile_plan(plan, aliases)
    counts = {alias: int(connection.execute(f"SELECT COUNT(*) FROM {quote_relation(alias)}").fetchone()[0])
              for alias in aliases}
    for step, compiled in zip(plan.steps, compiled_steps):
        relation = quote_relation(step.id)
        try:
            connection.execute(f"CREATE TEMP TABLE {relation} AS {compiled['sql']}")
        except duckdb.Error as exception:
            raise ValueError(f"步骤“{step.title or step.id}”无法执行：{exception}") from exception
        row_count = int(connection.execute(f"SELECT COUNT(*) FROM {relation}").fetchone()[0])
        columns = connection.execute(f"DESCRIBE SELECT * FROM {relation}").fetchall()
        input_count = counts.get(step.input) if step.input else counts.get(step.left)
        report: dict[str, Any] = {
            "id": step.id, "type": step.type, "title": step.title or step.type,
            "status": "passed", "inputRowCount": input_count, "outputRowCount": row_count,
            "columnCount": len(columns), "checks": [], "warnings": [],
        }
        if step.type == "filter" and input_count is not None:
            report["checks"].append(f"过滤后保留 {row_count:,} 行，排除 {max(0, input_count - row_count):,} 行")
        if step.type == "deduplicate" and input_count is not None:
            report["checks"].append(f"去重后保留 {row_count:,} 行，移除 {max(0, input_count - row_count):,} 行")
        if step.type == "join":
            left_count = counts.get(step.left, 0)
            right_keys = ", ".join(quote_identifier(key.right) for key in step.keys)
            right = quote_relation(step.right)
            duplicate_groups = int(connection.execute(
                f"SELECT COUNT(*) FROM (SELECT {right_keys} FROM {right} GROUP BY {right_keys} HAVING COUNT(*) > 1)"
            ).fetchone()[0])
            expansion = row_count / left_count if left_count else 0.0
            predicates = " AND ".join(
                f"l.{quote_identifier(key.left)} = r.{quote_identifier(key.right)}" for key in step.keys
            )
            match_marker = f"r.{quote_identifier(step.keys[0].right)}"
            matched_count = int(connection.execute(
                f"SELECT COUNT(*) FROM {quote_relation(step.left)} l "
                f"LEFT JOIN {right} r ON {predicates} WHERE {match_marker} IS NOT NULL"
            ).fetchone()[0])
            match_rate = matched_count / left_count if left_count else 1.0
            report["join"] = {"leftRowCount": left_count, "rightDuplicateKeyGroups": duplicate_groups,
                              "expansionFactor": round(expansion, 6),
                              "matchedRowCount": matched_count, "matchRate": round(match_rate, 6),
                              "expectedCardinality": step.expected_cardinality}
            if step.expected_cardinality in {"ONE_TO_ONE", "MANY_TO_ONE"} and duplicate_groups:
                raise ValueError(
                    f"步骤“{step.title or step.id}”未通过关联门禁：右侧数据有 {duplicate_groups} 组重复关联键，"
                    f"不符合 {step.expected_cardinality}，请先去重或补充关联键"
                )
            if step.join_type == "left" and expansion > 1.000001 and step.expected_cardinality == "MANY_TO_ONE":
                raise ValueError(f"步骤“{step.title or step.id}”发生非预期行数膨胀：{left_count:,} -> {row_count:,}")
            report["checks"].append(
                f"关联行数 {left_count:,} -> {row_count:,}，匹配率 {match_rate:.1%}，膨胀系数 {expansion:.3f}"
            )
        counts[step.id] = row_count
        reports.append(report)
    return reports


def _validation_reports(connection: duckdb.DuckDBPyConnection,
                        plan: TransformPlan | None) -> list[dict[str, Any]]:
    if plan is None:
        return []
    reports: list[dict[str, Any]] = []
    for validation in plan.validations:
        relation_name = validation.input or validation.step or plan.steps[-1].id
        relation = quote_relation(relation_name)
        passed = True
        observed: Any = None
        message = ""
        if validation.type == "unique":
            if not validation.columns:
                raise ValueError("unique 检查缺少 columns")
            keys = ", ".join(quote_identifier(item) for item in validation.columns)
            observed = int(connection.execute(
                f"SELECT COUNT(*) FROM (SELECT {keys} FROM {relation} GROUP BY {keys} HAVING COUNT(*) > 1)"
            ).fetchone()[0])
            passed = observed == 0
            message = f"重复键组数：{observed}"
        elif validation.type == "not_null":
            columns = validation.columns or ([validation.column] if validation.column else [])
            if not columns:
                raise ValueError("not_null 检查缺少 columns")
            null_counts = {
                column: int(connection.execute(
                    f"SELECT COUNT(*) FROM {relation} WHERE {quote_identifier(column)} IS NULL"
                ).fetchone()[0])
                for column in columns
            }
            observed = null_counts if len(columns) > 1 else next(iter(null_counts.values()))
            passed = all(count == 0 for count in null_counts.values())
            message = "空值行数：" + "，".join(f"{column}={count}" for column, count in null_counts.items())
        elif validation.type == "row_count":
            observed = int(connection.execute(f"SELECT COUNT(*) FROM {relation}").fetchone()[0])
            passed = ((validation.minimum is None or observed >= validation.minimum)
                      and (validation.maximum is None or observed <= validation.maximum))
            message = f"行数：{observed:,}"
        elif validation.type == "join_match_rate":
            join_step = next((item for item in plan.steps if item.id == validation.step and item.type == "join"), None)
            if join_step is None:
                raise ValueError("join_match_rate 检查必须引用一个 join 步骤")
            predicates = " AND ".join(
                f"l.{quote_identifier(key.left)} = r.{quote_identifier(key.right)}" for key in join_step.keys
            )
            marker = f"r.{quote_identifier(join_step.keys[0].right)}"
            left = quote_relation(join_step.left)
            right = quote_relation(join_step.right)
            total, matched = connection.execute(
                f"SELECT COUNT(*), COUNT(*) FILTER (WHERE {marker} IS NOT NULL) "
                f"FROM {left} l LEFT JOIN {right} r ON {predicates}"
            ).fetchone()
            observed = float(matched) / float(total) if total else 1.0
            threshold = validation.minimum if validation.minimum is not None else 1.0
            passed = observed >= threshold
            message = f"关联匹配率：{observed:.1%}，最低要求：{threshold:.1%}"
        elif validation.type == "sum_reconciliation":
            if not all([validation.before_input, validation.before_column,
                        validation.after_input, validation.after_column]):
                raise ValueError("sum_reconciliation 检查必须提供前后数据和金额字段")
            before = connection.execute(
                f"SELECT COALESCE(SUM({quote_identifier(validation.before_column)}), 0) "
                f"FROM {quote_relation(validation.before_input)}"
            ).fetchone()[0]
            after = connection.execute(
                f"SELECT COALESCE(SUM({quote_identifier(validation.after_column)}), 0) "
                f"FROM {quote_relation(validation.after_input)}"
            ).fetchone()[0]
            difference = abs(float(before) - float(after))
            observed = {"before": float(before), "after": float(after), "difference": difference}
            passed = difference <= validation.tolerance
            message = f"汇总前 {before}，汇总后 {after}，差异 {difference}"
        else:
            message = "该检查由步骤级质量门或汇总报告覆盖"
        report = {"type": validation.type, "relation": relation_name, "passed": passed,
                  "severity": validation.severity, "observed": observed, "message": message}
        reports.append(report)
        if not passed and validation.severity == "error":
            raise ValueError(f"质量检查未通过（{validation.type} / {relation_name}）：{message}")
    return reports


def sample_transform(inputs: list[dict[str, Any]], script: str = "", limit: int = 100,
                     plan: dict[str, Any] | TransformPlan | None = None) -> dict[str, Any]:
    sql, parsed_plan = _resolve_script(inputs, script, plan)
    with tempfile.TemporaryDirectory(prefix="finflow-sample-") as directory:
        temp_dir = Path(directory)
        connection = _connection(temp_dir)
        try:
            input_manifest, source_warnings = register_inputs(connection, inputs, temp_dir, row_limit=100)
            step_reports = _step_reports(connection, inputs, parsed_plan)
            sample_source = quote_relation(parsed_plan.steps[-1].id) if parsed_plan else f"({sql}) AS finflow_sample"
            cursor = connection.execute(
                f"SELECT * FROM {sample_source} USING SAMPLE reservoir({max(1, min(limit, 500))} ROWS)"
            )
            columns = [{"name": item[0], "dataType": str(item[1])} for item in cursor.description]
            names = [item[0] for item in cursor.description]
            rows = [dict(zip(names, (_json_value(value) for value in row))) for row in cursor.fetchall()]
            return {
                "valid": True, "stage": "PREVIEWED", "sampleRowCount": len(rows),
                "columns": columns, "rows": rows,
                "nullCounts": {name: sum(1 for row in rows if row.get(name) is None) for name in names},
                "inputs": input_manifest, "stepReports": step_reports,
                "sandbox": {"engine": "DuckDB", "mode": "EPHEMERAL_SAMPLE",
                            "rowLimitPerInput": 100, "inputCount": len(inputs)},
                "checks": ["加工协议和只读 SQL 校验通过", "DuckDB 沙箱样本执行成功"],
                "warnings": source_warnings + ([] if rows else ["样本结果为空，请确认筛选条件和关联键"]),
            }
        except duckdb.Error as exception:
            raise ValueError(f"样本试跑失败：{exception}") from exception
        finally:
            connection.close()


def run_transform(inputs: list[dict[str, Any]], script: str, output_zip: Path,
                  plan: dict[str, Any] | TransformPlan | None = None,
                  output_format: str = "parquet") -> dict[str, Any]:
    sql, parsed_plan = _resolve_script(inputs, script, plan)
    normalized_format = output_format.lower()
    if normalized_format not in {"parquet", "csv"}:
        raise ValueError("输出格式只支持 parquet 或 csv")
    work_dir = Path(tempfile.mkdtemp(prefix="finflow-run-"))
    connection = _connection(work_dir)
    try:
        input_manifest, source_warnings = register_inputs(connection, inputs, work_dir)
        step_reports = _step_reports(connection, inputs, parsed_plan)
        validations = _validation_reports(connection, parsed_plan)
        output_query = f"SELECT * FROM {quote_relation(parsed_plan.steps[-1].id)}" if parsed_plan else sql
        extension = "parquet" if normalized_format == "parquet" else "csv"
        result_path = work_dir / f"result.{extension}"
        target = _escape_literal(str(result_path))
        if normalized_format == "parquet":
            connection.execute(f"COPY ({output_query}) TO '{target}' (FORMAT PARQUET, COMPRESSION ZSTD)")
            source = f"read_parquet('{target}')"
            media_type = "application/vnd.apache.parquet"
        else:
            connection.execute(
                f"COPY ({output_query}) TO '{target}' (FORMAT CSV, HEADER true, DELIMITER ',', QUOTE '\"', ESCAPE '\"')"
            )
            source = f"read_csv_auto('{target}', header=true)"
            media_type = "text/csv"
        row_count = int(connection.execute(f"SELECT COUNT(*) FROM {source}").fetchone()[0])
        description = connection.execute(f"DESCRIBE SELECT * FROM {source}").fetchall()
        columns = [{"name": item[0], "dataType": str(item[1])} for item in description]
        report = {
            "valid": True, "stage": "VERIFIED", "rowCount": row_count,
            "columnCount": len(columns), "columns": columns, "inputs": input_manifest,
            "stepReports": step_reports, "validations": validations,
            "output": {"fileName": result_path.name, "format": normalized_format,
                       "mediaType": media_type, "byteCount": result_path.stat().st_size,
                       "contentHash": _file_hash(result_path)},
            "checks": ["加工协议和只读 SQL 校验通过", "全量查询执行成功",
                       "结果文件已重新读取", "结果行列数已核对", "结果内容哈希已记录"],
            "warnings": source_warnings + ([] if row_count else ["加工结果为空，请确认筛选条件和关联键"]),
        }
        manifest = {
            "version": "1.0", "plan": parsed_plan.model_dump(mode="json") if parsed_plan else None,
            "scriptHash": hashlib.sha256(sql.encode("utf-8")).hexdigest(),
            "inputs": input_manifest, "output": report["output"],
        }
        quality_path = work_dir / "quality.json"
        manifest_path = work_dir / "manifest.json"
        quality_path.write_text(json.dumps(report, ensure_ascii=False, default=str), encoding="utf-8")
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, default=str), encoding="utf-8")
        with zipfile.ZipFile(output_zip, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            archive.write(result_path, result_path.name)
            archive.write(quality_path, "quality.json")
            archive.write(manifest_path, "manifest.json")
        return report
    except duckdb.Error as exception:
        raise ValueError(f"全量加工失败：{exception}") from exception
    finally:
        connection.close()
        shutil.rmtree(work_dir, ignore_errors=True)
