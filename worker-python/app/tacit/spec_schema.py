"""Typed, versioned wire contracts shared by the authoring tools and quality checks."""
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, JsonValue, field_validator


FactStatus = Literal["source_supported", "user_confirmed", "proposed", "unresolved", "conflicted"]
ReadStatus = Literal["unread", "partial", "read"]


class Record(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True, allow_inf_nan=False)


class Scope(Record):
    goal: str = ""
    in_scope: list[str] = Field(default_factory=list)
    out_of_scope: list[str] = Field(default_factory=list)
    period: str = ""
    region: str = ""


class Evidence(Record):
    source_id: str
    clause_id: str
    quote: str


class Fact(Record):
    evidence: list[Evidence] = Field(default_factory=list)
    status: FactStatus = "unresolved"


class SourceClause(Record):
    clause_id: str
    locator: str = ""
    quote: str = ""
    coverage: Literal["unreviewed", "extracted", "gap", "out_of_scope"] = "unreviewed"
    rule_ids: list[str] = Field(default_factory=list)
    process_ids: list[str] = Field(default_factory=list)
    gap_ids: list[str] = Field(default_factory=list)
    data_object_ids: list[str] = Field(default_factory=list)
    data_table_ids: list[str] = Field(default_factory=list)
    reason: str = ""


class Source(Record):
    source_id: str
    title: str = ""
    source_type: Literal["asset", "cq", "terminology", "user"]
    content_kind: Literal["unspecified", "business_text", "table_structure", "mixed"] = "unspecified"
    version: str = ""
    read_status: ReadStatus = "unread"
    disposition: Literal["included", "excluded"] = "included"
    scope_reason: str = ""
    clauses: list[SourceClause] = Field(default_factory=list)


class Condition(Record):
    operator: Literal["all", "any", "predicate", "expression", "always", "unresolved"] = "unresolved"
    clauses: list["Condition"] = Field(default_factory=list)
    field: str = ""
    comparator: Literal["", "gt", "gte", "lt", "lte", "eq", "neq", "in", "not_in", "exists", "not_exists"] = ""
    value: JsonValue = None
    unit: str = ""
    expression: str = ""
    boundary_notes: str = ""
    logical_notes: str = ""


class Rule(Fact):
    rule_id: str
    name: str = ""
    scope: str = ""
    condition: Condition = Field(default_factory=Condition)
    results: list[str] = Field(default_factory=list)
    exceptions: list[str] = Field(default_factory=list)
    inputs: list[str] = Field(default_factory=list)
    outputs: list[str] = Field(default_factory=list)
    process_ids: list[str] = Field(default_factory=list)
    process_disposition: Literal["bound", "independent", "excluded"] = "bound"
    process_reason: str = ""
    effective_from: str = ""
    effective_to: str = ""
    precedence: str = ""


class ProcessStep(Record):
    step_id: str
    name: str = ""
    kind: Literal["start", "step", "decision", "end"] = "step"
    actor: str = ""
    inputs: list[str] = Field(default_factory=list)
    outputs: list[str] = Field(default_factory=list)
    reads: list[str] = Field(default_factory=list)
    writes: list[str] = Field(default_factory=list)
    rule_ids: list[str] = Field(default_factory=list)


class ProcessBranch(Record):
    branch_id: str
    from_step_id: str
    to_step_id: str
    condition: Condition = Field(default_factory=Condition)
    rule_ids: list[str] = Field(default_factory=list)
    is_exception: bool = False
    description: str = ""


class Process(Fact):
    process_id: str
    name: str = ""
    scope: str = ""
    trigger: str = ""
    roles: list[str] = Field(default_factory=list)
    preconditions: list[str] = Field(default_factory=list)
    inputs: list[str] = Field(default_factory=list)
    outputs: list[str] = Field(default_factory=list)
    steps: list[ProcessStep] = Field(default_factory=list)
    branches: list[ProcessBranch] = Field(default_factory=list)
    exceptions: list[str] = Field(default_factory=list)
    rule_ids: list[str] = Field(default_factory=list)
    disposition: Literal["included", "subprocess", "excluded"] = "included"
    scope_reason: str = ""
    parent_process_id: str = ""


class DataField(Record):
    name: str = ""
    business_name: str = ""
    data_type: str = ""
    unit: str = ""
    default_value: JsonValue = None
    default_status: Literal["not_provided", "source_supported", "user_confirmed", "proposed", "unresolved", "conflicted"] = "not_provided"


class Relationship(Fact):
    name: str = ""
    target_object_id: str = ""
    cardinality: Literal["one_to_one", "one_to_many", "many_to_one", "many_to_many", "unresolved"] = "unresolved"


class DataObject(Fact):
    object_id: str
    name: str = ""
    identity_basis: str = ""
    fields: list[DataField] = Field(default_factory=list)
    relationships: list[Relationship] = Field(default_factory=list)


class Column(Record):
    name: str = ""
    business_name: str = ""
    data_type: str = ""
    default_status: Literal["not_provided", "declared", "unresolved"] = "not_provided"
    default_value: JsonValue = None
    primary_key: bool = False
    foreign_key: str = ""
    constraints: list[str] = Field(default_factory=list)


class DataTable(Fact):
    table_id: str
    name: str = ""
    locator: str = ""
    columns: list[Column] = Field(default_factory=list)
    read_status: ReadStatus = "unread"


class Gap(Record):
    gap_id: str
    question: str = ""
    impact: str = ""
    blocking: bool = True
    status: Literal["open", "resolved"] = "open"
    resolution: str = ""
    rule_ids: list[str] = Field(default_factory=list)
    process_ids: list[str] = Field(default_factory=list)
    data_table_ids: list[str] = Field(default_factory=list)
    data_object_ids: list[str] = Field(default_factory=list)
    evidence: list[Evidence] = Field(default_factory=list)


class AcceptanceCase(Record):
    case_id: str
    name: str = ""
    kind: Literal["normal", "exception", "boundary"] = "normal"
    process_ids: list[str] = Field(default_factory=list)
    rule_ids: list[str] = Field(default_factory=list)
    inputs: dict[str, JsonValue] = Field(default_factory=dict)
    expected_outputs: dict[str, JsonValue] = Field(default_factory=dict)
    expected_path: list[str] = Field(default_factory=list)
    status: Literal["defined", "needs_confirmation"] = "needs_confirmation"
    assumptions: list[str] = Field(default_factory=list)


class BusinessSpec(Record):
    schema_version: Literal[1] = 1
    completion: Literal["draft", "complete"] = "draft"
    @field_validator("schema_version", mode="before")
    @classmethod
    def exact_version(cls, value):
        if type(value) is not int:
            raise ValueError("版本号须为整数")
        return value

    scope: Scope = Field(default_factory=Scope)
    sources: list[Source] = Field(default_factory=list)
    data_objects: list[DataObject] = Field(default_factory=list)
    data_tables: list[DataTable] = Field(default_factory=list)
    rules: list[Rule] = Field(default_factory=list)
    processes: list[Process] = Field(default_factory=list)
    gaps: list[Gap] = Field(default_factory=list)
    acceptance_cases: list[AcceptanceCase] = Field(default_factory=list)


SECTION_MODELS = {
    "scope": Scope, "sources": Source, "data_objects": DataObject,
    "data_tables": DataTable, "rules": Rule, "processes": Process,
    "gaps": Gap, "acceptance_cases": AcceptanceCase,
}
ITEM_IDS = {
    "sources": "source_id", "data_objects": "object_id", "data_tables": "table_id",
    "rules": "rule_id", "processes": "process_id", "gaps": "gap_id",
    "acceptance_cases": "case_id",
}
