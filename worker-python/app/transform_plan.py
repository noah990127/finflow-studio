from __future__ import annotations

import re
from typing import Any

import sqlglot
from sqlglot import exp

from .models import TransformCondition, TransformPlan, TransformStep


IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z0-9_]{0,63}$")
DECIMAL_TYPE = re.compile(r"^DECIMAL(?:\((\d{1,2}),(\d{1,2})\))?$")
ALLOWED_EXPRESSION_FUNCTIONS = {
    "abs", "ceil", "coalesce", "concat", "date_diff", "date_part", "date_trunc",
    "floor", "greatest", "least", "lower", "nullif", "regexp_replace", "replace",
    "round", "strftime", "substr", "substring", "trim", "try_cast", "upper",
}


def quote_identifier(value: str) -> str:
    if not value or len(value) > 200 or "\x00" in value:
        raise ValueError(f"字段名不正确：{value!r}")
    return '"' + value.replace('"', '""') + '"'


def quote_relation(value: str) -> str:
    if not IDENTIFIER.fullmatch(value):
        raise ValueError(f"步骤或数据别名不正确：{value}")
    return quote_identifier(value)


def sql_literal(value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "TRUE" if value else "FALSE"
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        return str(value)
    if isinstance(value, list):
        return "(" + ", ".join(sql_literal(item) for item in value) + ")"
    return "'" + str(value).replace("'", "''") + "'"


def compile_condition(condition: TransformCondition, table_alias: str = "") -> str:
    column = quote_identifier(condition.column)
    if table_alias:
        column = f"{quote_relation(table_alias)}.{column}"
    operator = condition.operator
    if operator == "is_null":
        return f"{column} IS NULL"
    if operator == "is_not_null":
        return f"{column} IS NOT NULL"
    if operator in {"in", "not_in"}:
        if not isinstance(condition.value, list) or not condition.value:
            raise ValueError(f"{operator} 条件必须提供非空数组")
        keyword = "IN" if operator == "in" else "NOT IN"
        return f"{column} {keyword} {sql_literal(condition.value)}"
    if operator in {"contains", "starts_with", "ends_with"}:
        raw = str(condition.value or "")
        pattern = {"contains": f"%{raw}%", "starts_with": f"{raw}%", "ends_with": f"%{raw}"}[operator]
        return f"CAST({column} AS VARCHAR) LIKE {sql_literal(pattern)}"
    token = {
        "equal": "=", "not_equal": "<>", "greater_than": ">", "greater_or_equal": ">=",
        "less_than": "<", "less_or_equal": "<=",
    }[operator]
    return f"{column} {token} {sql_literal(condition.value)}"


def validate_expression(expression: str) -> str:
    if not expression or len(expression) > 4000:
        raise ValueError("派生字段表达式为空或过长")
    try:
        parsed = sqlglot.parse_one(expression, read="duckdb")
    except sqlglot.errors.ParseError as exception:
        raise ValueError(f"派生字段表达式语法不正确：{exception}") from exception
    blocked = (exp.Query, exp.Table, exp.Subquery, exp.Command, exp.Attach, exp.Pragma)
    if any(parsed.find(kind) is not None for kind in blocked):
        raise ValueError("派生字段表达式不能包含查询、文件或系统操作")
    functions = {
        str(node.name).lower() for node in parsed.find_all(exp.Func)
        if getattr(node, "name", None)
    }
    unknown = sorted(functions - ALLOWED_EXPRESSION_FUNCTIONS)
    if unknown:
        raise ValueError("派生字段使用了未开放的函数：" + "、".join(unknown))
    return parsed.sql(dialect="duckdb")


def _compile_cast(step: TransformStep) -> str:
    replacements = []
    for column, target in step.casts.items():
        normalized = target.upper()
        if not DECIMAL_TYPE.fullmatch(normalized) and normalized != "DECIMAL":
            normalized = target
        replacements.append(
            f"TRY_CAST({quote_identifier(column)} AS {normalized}) AS {quote_identifier(column)}"
        )
    return f"SELECT * REPLACE ({', '.join(replacements)}) FROM {quote_relation(step.input)}"


def _compile_step(step: TransformStep) -> str:
    source = quote_relation(step.input) if step.input else ""
    if step.type == "select":
        return "SELECT " + ", ".join(quote_identifier(item) for item in step.columns) + f" FROM {source}"
    if step.type == "filter":
        return f"SELECT * FROM {source} WHERE {compile_condition(step.condition)}"
    if step.type == "rename":
        renames = ", ".join(
            f"{quote_identifier(old)} AS {quote_identifier(new)}" for old, new in step.renames.items()
        )
        return f"SELECT * RENAME ({renames}) FROM {source}"
    if step.type == "cast":
        return _compile_cast(step)
    if step.type == "deduplicate":
        keys = ", ".join(quote_identifier(item) for item in step.columns)
        ordering = ", ".join(
            f"{quote_identifier(item.column)} {item.direction.upper()}" for item in step.sort_by
        ) or keys
        return (
            f"SELECT * EXCLUDE (__finflow_row_number) FROM (SELECT *, ROW_NUMBER() OVER "
            f"(PARTITION BY {keys} ORDER BY {ordering}) AS __finflow_row_number FROM {source}) "
            "WHERE __finflow_row_number = 1"
        )
    if step.type == "join":
        left = quote_relation(step.left)
        right = quote_relation(step.right)
        same_named_join_keys = {key.right for key in step.keys if key.left == key.right}
        predicates = " AND ".join(
            f"l.{quote_identifier(item.left)} = r.{quote_identifier(item.right)}" for item in step.keys
        )
        if step.right_columns:
            selected = [
                f"r.{quote_identifier(item)}" for item in step.right_columns
                if item not in same_named_join_keys
            ]
            extras = (", " + ", ".join(selected)) if selected else ""
        else:
            excluded = ", ".join(quote_identifier(item) for item in same_named_join_keys)
            extras = f", r.* EXCLUDE ({excluded})" if excluded else ", r.*"
        return f"SELECT l.*{extras} FROM {left} l {step.join_type.upper()} JOIN {right} r ON {predicates}"
    if step.type == "derive":
        expressions = ", ".join(
            f"{validate_expression(value)} AS {quote_identifier(name)}" for name, value in step.expressions.items()
        )
        return f"SELECT *, {expressions} FROM {source}"
    if step.type == "aggregate":
        groups = ", ".join(quote_identifier(item) for item in step.group_by)
        metrics = []
        for metric in step.metrics:
            column = quote_identifier(metric.column) if metric.column else "*"
            expression = {
                "sum": f"SUM({column})", "count": f"COUNT({column})",
                "count_distinct": f"COUNT(DISTINCT {column})", "avg": f"AVG({column})",
                "min": f"MIN({column})", "max": f"MAX({column})",
            }[metric.function]
            metrics.append(f"{expression} AS {quote_identifier(metric.name)}")
        selected = ", ".join(([groups] if groups else []) + metrics)
        grouped = f" GROUP BY {groups}" if groups else ""
        return f"SELECT {selected} FROM {source}{grouped}"
    if step.type == "union":
        return " UNION ALL BY NAME ".join(f"SELECT * FROM {quote_relation(item)}" for item in step.inputs)
    if step.type == "sort":
        ordering = ", ".join(
            f"{quote_identifier(item.column)} {item.direction.upper()}" for item in step.sort_by
        )
        return f"SELECT * FROM {source} ORDER BY {ordering}"
    if step.type == "limit":
        return f"SELECT * FROM {source} LIMIT {step.limit}"
    raise ValueError(f"暂不支持的加工步骤：{step.type}")


def compile_plan(plan: TransformPlan, aliases: list[str]) -> tuple[str, list[dict[str, str]]]:
    available = {item.lower() for item in aliases}
    ctes: list[str] = []
    step_sql: list[dict[str, str]] = []
    for step in plan.steps:
        if step.id.lower() in available:
            raise ValueError(f"步骤 ID 与已有数据或步骤重复：{step.id}")
        references = []
        if step.input:
            references.append(step.input)
        references.extend(step.inputs)
        if step.left:
            references.append(step.left)
        if step.right:
            references.append(step.right)
        unknown = [item for item in references if item.lower() not in available]
        if unknown:
            raise ValueError(f"步骤 {step.id} 引用了尚不存在的输入：{'、'.join(unknown)}")
        query = _compile_step(step)
        ctes.append(f"{quote_relation(step.id)} AS ({query})")
        step_sql.append({"id": step.id, "type": step.type, "title": step.title or step.type, "sql": query})
        available.add(step.id.lower())
    final = quote_relation(plan.steps[-1].id)
    return f"WITH {', '.join(ctes)} SELECT * FROM {final}", step_sql
