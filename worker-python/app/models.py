from typing import Any, Dict, List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, model_validator


class KnowledgeChunk(BaseModel):
    ref_id: str
    source_name: str
    text: str
    location: Dict[str, object] = Field(default_factory=dict)


class SummarizeRequest(BaseModel):
    text: str = Field(min_length=1, max_length=200_000)
    max_points: int = Field(default=5, ge=1, le=20)
    source_name: str = "用户输入"


class SummarizeResponse(BaseModel):
    summary: str
    points: List[str]
    mode: str
    refs: List[KnowledgeChunk]


class GenerateContentRequest(BaseModel):
    format: str = Field(min_length=1, max_length=20)
    requirements: str = Field(min_length=1, max_length=10_000)
    source_text: str = Field(min_length=1, max_length=200_000)
    skill_name: str = Field(default="", max_length=200)
    skill_instructions: str = Field(default="", max_length=100_000)


class GenerateContentResponse(BaseModel):
    content: str
    mode: str


class SearchRequest(BaseModel):
    query: str = Field(min_length=1, max_length=2_000)
    chunks: List[KnowledgeChunk]
    limit: int = Field(default=5, ge=1, le=20)


class SearchHit(BaseModel):
    ref_id: str
    source_name: str
    text: str
    location: Dict[str, object]
    score: float


class DatasetProfileRequest(BaseModel):
    columns: List[str]
    rows: List[Dict[str, object]] = Field(max_length=1000)


class ColumnProfile(BaseModel):
    name: str
    non_null_count: int
    null_count: int
    distinct_count: int
    sample_values: List[object]


class DatasetProfileResponse(BaseModel):
    row_count: int
    columns: List[ColumnProfile]
    suggestions: List[str]


class DeepSeekStatus(BaseModel):
    configured: bool
    model: str
    message: Optional[str] = None


class LlmStatus(BaseModel):
    configured: bool
    provider: str
    model: str
    message: Optional[str] = None


class ResearchRequest(BaseModel):
    topic: str = Field(min_length=1, max_length=500)
    max_sources: int = Field(default=12, ge=3, le=20)


class ResearchSource(BaseModel):
    title: str
    url: str
    source_type: str
    why_relevant: str


class ResearchResponse(BaseModel):
    topic: str
    summary: str
    sources: List[ResearchSource]
    mode: str
    status: Literal["ready", "empty", "unavailable", "failed"]
    provider: str


class ResearchFetchRequest(BaseModel):
    url: str = Field(min_length=8, max_length=2_000)
    domain_allowlist: List[str] = Field(default_factory=list, max_length=50)


class ParsedChunk(BaseModel):
    index: int
    text: str
    location: Dict[str, object]
    content_hash: str


class ParsedDocument(BaseModel):
    file_name: str
    media_type: str
    title: str
    text_length: int
    chunks: List[ParsedChunk]
    warnings: List[str] = Field(default_factory=list)


class DeliverableRef(BaseModel):
    ref_id: str = ""
    resource_id: str = ""
    version: int = 0
    source_name: str
    text: str = ""
    location: Dict[str, object] = Field(default_factory=dict)
    content_hash: str = ""


class DeliverableChartSeries(BaseModel):
    name: str = Field(default="指标", max_length=120)
    values: List[float] = Field(default_factory=list, max_length=24)


class DeliverableChart(BaseModel):
    type: str = Field(default="bar", pattern=r"^(bar|line|pie)$")
    title: str = Field(default="数据对比", max_length=200)
    categories: List[str] = Field(default_factory=list, max_length=24)
    series: List[DeliverableChartSeries] = Field(default_factory=list, max_length=6)
    source_ref: str = Field(default="", max_length=200)

    @model_validator(mode="after")
    def validate_series(self):
        import math
        if not self.categories or not self.series:
            raise ValueError("图表必须提供分类与真实数值序列")
        if any(len(series.values) != len(self.categories) or
               any(not math.isfinite(value) for value in series.values) for series in self.series):
            raise ValueError("图表分类与数值数量必须一致，且数值必须有限")
        if self.type == "pie" and (len(self.series) != 1 or any(value < 0 for value in self.series[0].values)
                                    or sum(self.series[0].values) <= 0):
            raise ValueError("饼图需要一组非负且合计大于零的数据")
        return self


class VisualItem(BaseModel):
    model_config = ConfigDict(extra="forbid")
    label: str = Field(min_length=1, max_length=60)
    detail: str = Field(default="", max_length=160)
    owner: str = Field(default="", max_length=60)
    outcome: str = Field(default="", max_length=100)


class VisualRow(BaseModel):
    model_config = ConfigDict(extra="forbid")
    label: str = Field(min_length=1, max_length=80)
    cells: List[str] = Field(min_length=1, max_length=5)


class PresentationVisual(BaseModel):
    """Business relationships, not arbitrary HTML or drawing coordinates."""
    model_config = ConfigDict(extra="forbid")
    kind: Literal["timeline", "process", "matrix", "comparison", "statement", "list"]
    rationale: str = Field(min_length=1, max_length=300)
    items: List[VisualItem] = Field(default_factory=list, max_length=6)
    columns: List[str] = Field(default_factory=list, max_length=5)
    rows: List[VisualRow] = Field(default_factory=list, max_length=6)

    @model_validator(mode="after")
    def check_structure(self):
        if self.kind in {"matrix", "comparison"}:
            if not self.columns or not self.rows or self.items:
                raise ValueError("矩阵/对比需要 columns 与 rows，不能用 items 代替")
            if any(len(row.cells) != len(self.columns) for row in self.rows):
                raise ValueError("每行数据必须与列维度一一对应")
            if any(len(cell) > 100 for row in self.rows for cell in row.cells):
                raise ValueError("矩阵单元格过长，请拆分表达而不是缩小文字")
        elif not self.items or self.columns or self.rows:
            raise ValueError("顺序/主题表达需要 items，不能混用矩阵结构")
        if self.kind in {"timeline", "process"} and not 2 <= len(self.items) <= 5:
            raise ValueError("时间线或流程需要2至5个有序阶段")
        return self


class DeliverableSection(BaseModel):
    heading: str
    chapter: str = Field(default="", max_length=80)
    core_message: str = Field(default="", max_length=240)
    layout: str = Field(
        default="auto",
        pattern=r"^(auto|statement|metric|chart|comparison|timeline|process|matrix|list|decision|table)$",
    )
    paragraphs: List[str] = Field(default_factory=list)
    bullets: List[str] = Field(default_factory=list)
    refs: List[DeliverableRef] = Field(default_factory=list)
    chart: Optional[DeliverableChart] = None
    visual: Optional[PresentationVisual] = None


class PresentationBrief(BaseModel):
    purpose: str = Field(default="", max_length=500)
    audience: str = Field(default="", max_length=300)
    desired_outcome: str = Field(default="", max_length=500)
    density: Literal["speaking", "reading"] = "speaking"
    evidence_policy: Literal["as_needed", "research"] = "as_needed"
    include_cover: bool = False


class DeliverableRequest(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    subtitle: str = Field(default="", max_length=500)
    sections: List[DeliverableSection] = Field(min_length=1, max_length=100)
    theme: str = "blue-white"
    ppt_skill: Optional[str] = None
    include_citations: bool = True
    citation_style: str = Field(default="IEEE", pattern=r"^(IEEE|APA_7|GB_T_7714)$")
    plan_mode: Literal["auto", "fixed"] = "auto"
    presentation_brief: PresentationBrief = Field(default_factory=PresentationBrief)


class SpreadsheetSheetProfile(BaseModel):
    name: str
    rows: int
    columns: int
    formula_count: int
    merged_range_count: int


class SpreadsheetProfile(BaseModel):
    file_name: str
    format: str
    has_macros: bool
    sheets: List[SpreadsheetSheetProfile]
    warnings: List[str] = Field(default_factory=list)


class FormulaColumn(BaseModel):
    name: str = Field(min_length=1, max_length=200)
    formula: str = Field(min_length=1, max_length=2000)


class SpreadsheetTransformRequest(BaseModel):
    sheet_name: Optional[str] = None
    rename_headers: Dict[str, str] = Field(default_factory=dict)
    fill_blanks: Dict[str, object] = Field(default_factory=dict)
    formula_columns: List[FormulaColumn] = Field(default_factory=list, max_length=50)
    remove_duplicates: bool = False


class PreviewBlock(BaseModel):
    type: str
    text: str = ""
    rows: List[List[str]] = Field(default_factory=list)


class PreviewPage(BaseModel):
    number: int
    title: str = ""
    blocks: List[PreviewBlock] = Field(default_factory=list)


class DocumentPreview(BaseModel):
    file_name: str
    kind: str
    title: str
    pages: List[PreviewPage]
    warnings: List[str] = Field(default_factory=list)
    research_sources: List[dict] = Field(default_factory=list)


class TransformColumn(BaseModel):
    name: str
    data_type: str
    nullable: bool = True


class TransformInputProfile(BaseModel):
    alias: str = Field(pattern=r"^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    name: str
    columns: List[TransformColumn]
    sample_rows: List[Dict[str, object]] = Field(default_factory=list, max_length=50)
    estimated_rows: Optional[int] = None


class TransformGenerateRequest(BaseModel):
    requirements: str = Field(min_length=1, max_length=10_000)
    inputs: List[TransformInputProfile] = Field(min_length=1, max_length=20)


class TransformStrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class TransformCondition(TransformStrictModel):
    column: str = Field(min_length=1, max_length=200)
    operator: Literal[
        "equal", "not_equal", "greater_than", "greater_or_equal", "less_than",
        "less_or_equal", "in", "not_in", "contains", "starts_with", "ends_with",
        "is_null", "is_not_null",
    ]
    value: Any = None


class TransformJoinKey(TransformStrictModel):
    left: str = Field(min_length=1, max_length=200)
    right: str = Field(min_length=1, max_length=200)


class TransformMetric(TransformStrictModel):
    name: str = Field(pattern=r"^[A-Za-z_\u4e00-\u9fff][A-Za-z0-9_\u4e00-\u9fff]{0,127}$")
    function: Literal["sum", "count", "count_distinct", "avg", "min", "max"]
    column: str = Field(default="", max_length=200)


class TransformSortKey(TransformStrictModel):
    column: str = Field(min_length=1, max_length=200)
    direction: Literal["asc", "desc"] = "asc"


class TransformStep(TransformStrictModel):
    id: str = Field(pattern=r"^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    type: Literal["select", "filter", "rename", "cast", "deduplicate", "join", "derive", "aggregate", "union", "sort", "limit"]
    title: str = Field(default="", max_length=200)
    input: str = Field(default="", max_length=64)
    inputs: List[str] = Field(default_factory=list, max_length=20)
    columns: List[str] = Field(default_factory=list, max_length=300)
    renames: Dict[str, str] = Field(default_factory=dict)
    casts: Dict[str, Literal["VARCHAR", "BIGINT", "DOUBLE", "DECIMAL", "DATE", "TIMESTAMP", "BOOLEAN"]] = Field(default_factory=dict)
    condition: Optional[TransformCondition] = None
    keys: List[TransformJoinKey] = Field(default_factory=list, max_length=20)
    join_type: Literal["left", "inner", "right", "full"] = "left"
    left: str = Field(default="", max_length=64)
    right: str = Field(default="", max_length=64)
    expected_cardinality: Literal["ONE_TO_ONE", "MANY_TO_ONE", "ONE_TO_MANY", "MANY_TO_MANY"] = "MANY_TO_ONE"
    right_columns: List[str] = Field(default_factory=list, max_length=300)
    expressions: Dict[str, str] = Field(default_factory=dict)
    group_by: List[str] = Field(default_factory=list, max_length=100)
    metrics: List[TransformMetric] = Field(default_factory=list, max_length=100)
    sort_by: List[TransformSortKey] = Field(default_factory=list, max_length=30)
    limit: Optional[int] = Field(default=None, ge=1, le=100_000_000)

    @model_validator(mode="after")
    def validate_operation(self) -> "TransformStep":
        if not self.title:
            self.title = {
                "select": "选择需要的字段", "filter": "筛选符合条件的数据", "rename": "统一字段名称",
                "cast": "统一字段类型", "deduplicate": "去除重复数据", "join": "关联补充数据",
                "derive": "计算业务字段", "aggregate": "汇总业务指标", "union": "合并同类数据",
                "sort": "整理结果顺序", "limit": "限制结果数量",
            }[self.type]
        required_input = {"select", "filter", "rename", "cast", "deduplicate", "derive", "aggregate", "sort", "limit"}
        if self.type in required_input and not self.input:
            raise ValueError(f"{self.type} 步骤缺少 input")
        if self.type == "select" and not self.columns:
            raise ValueError("select 步骤至少选择一个字段")
        if self.type == "filter" and self.condition is None:
            raise ValueError("filter 步骤缺少 condition")
        if self.type == "rename" and not self.renames:
            raise ValueError("rename 步骤缺少 renames")
        if self.type == "cast" and not self.casts:
            raise ValueError("cast 步骤缺少 casts")
        if self.type == "deduplicate" and not self.columns:
            raise ValueError("deduplicate 步骤至少需要一个去重键")
        if self.type == "join" and (not self.left or not self.right or not self.keys):
            raise ValueError("join 步骤必须提供 left、right 和 keys")
        if self.type == "derive" and not self.expressions:
            raise ValueError("derive 步骤缺少 expressions")
        if self.type == "aggregate" and not self.metrics:
            raise ValueError("aggregate 步骤至少需要一个指标")
        if self.type == "union" and len(self.inputs) < 2:
            raise ValueError("union 步骤至少需要两个输入")
        if self.type == "sort" and not self.sort_by:
            raise ValueError("sort 步骤缺少 sort_by")
        if self.type == "limit" and self.limit is None:
            raise ValueError("limit 步骤缺少 limit")
        return self


class TransformValidation(TransformStrictModel):
    type: Literal["unique", "not_null", "join_match_rate", "row_count", "sum_reconciliation"]
    input: str = Field(default="", max_length=64)
    step: str = Field(default="", max_length=64)
    columns: List[str] = Field(default_factory=list, max_length=20)
    column: str = Field(default="", max_length=200)
    before_input: str = Field(default="", max_length=64)
    before_column: str = Field(default="", max_length=200)
    after_input: str = Field(default="", max_length=64)
    after_column: str = Field(default="", max_length=200)
    minimum: Optional[float] = None
    maximum: Optional[float] = None
    tolerance: float = Field(default=0.01, ge=0)
    severity: Literal["error", "warning"] = "error"


class TransformOutput(TransformStrictModel):
    format: Literal["parquet", "csv"] = "parquet"
    partition_by: List[str] = Field(default_factory=list, max_length=10)


class TransformPlan(TransformStrictModel):
    version: Literal["1.0"] = "1.0"
    goal: str = Field(min_length=1, max_length=1000)
    steps: List[TransformStep] = Field(min_length=1, max_length=100)
    validations: List[TransformValidation] = Field(default_factory=list, max_length=100)
    output: TransformOutput = Field(default_factory=TransformOutput)


class TransformGenerateResponse(BaseModel):
    plan: TransformPlan
    script: str
    summary: str
    mode: str
    assumptions: List[str] = Field(default_factory=list)
    quality_rules: List[str] = Field(default_factory=list)
    warnings: List[str] = Field(default_factory=list)
