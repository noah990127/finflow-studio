from typing import Dict, List, Optional

from pydantic import BaseModel, Field


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


class DeliverableSection(BaseModel):
    heading: str
    paragraphs: List[str] = Field(default_factory=list)
    bullets: List[str] = Field(default_factory=list)
    refs: List[DeliverableRef] = Field(default_factory=list)
    chart: Optional[DeliverableChart] = None


class DeliverableRequest(BaseModel):
    title: str = Field(min_length=1, max_length=200)
    subtitle: str = Field(default="", max_length=500)
    sections: List[DeliverableSection] = Field(min_length=1, max_length=100)
    theme: str = "blue-white"
    ppt_skill: Optional[str] = None
    include_citations: bool = True
    citation_style: str = Field(default="IEEE", pattern=r"^(IEEE|APA_7|GB_T_7714)$")


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


class TransformGenerateResponse(BaseModel):
    script: str
    summary: str
    mode: str
    assumptions: List[str] = Field(default_factory=list)
    quality_rules: List[str] = Field(default_factory=list)
    warnings: List[str] = Field(default_factory=list)
