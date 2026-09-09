from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field, model_validator


ArtifactFormat = Literal[
    "pptx", "html_slides", "docx", "pdf", "financial_report", "mermaid", "excalidraw"
]


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EvidenceClaim(StrictModel):
    claim_id: str = Field(min_length=1, max_length=120)
    kind: Literal["fact", "calculation", "inference", "recommendation", "limitation"]
    text: str = Field(min_length=1, max_length=4000)
    ref_ids: list[str] = Field(default_factory=list, max_length=50)
    confidence: float = Field(default=1.0, ge=0, le=1)


class EvidenceSource(StrictModel):
    ref_id: str = Field(min_length=1, max_length=200)
    source_name: str = Field(min_length=1, max_length=500)
    resource_id: str = Field(default="", max_length=200)
    version: int = Field(default=0, ge=0)
    content_hash: str = Field(default="", max_length=200)
    location: dict[str, object] = Field(default_factory=dict)
    excerpt: str = Field(default="", max_length=6000)


class EvidencePack(StrictModel):
    task_goal: str = Field(min_length=1, max_length=10_000)
    audience_inference: str = Field(default="", max_length=1000)
    claims: list[EvidenceClaim] = Field(default_factory=list, max_length=500)
    sources: list[EvidenceSource] = Field(default_factory=list, max_length=200)


class PresentationPage(StrictModel):
    chapter: str = Field(default="", max_length=80)
    title: str = Field(min_length=1, max_length=80)
    core_message: str = Field(min_length=1, max_length=500)
    layout: Literal["statement", "metric", "chart", "comparison", "timeline", "process", "matrix", "list"]
    bullets: list[str] = Field(default_factory=list, max_length=4)
    ref_ids: list[str] = Field(default_factory=list, max_length=30)


class PresentationSpec(StrictModel):
    title: str = Field(min_length=1, max_length=200)
    subtitle: str = Field(default="", max_length=500)
    pages: list[PresentationPage] = Field(min_length=1, max_length=18)


class DocumentBlock(StrictModel):
    kind: Literal["paragraph", "bullets", "table", "chart", "callout"]
    text: str = Field(default="", max_length=20_000)
    items: list[str] = Field(default_factory=list, max_length=50)
    ref_ids: list[str] = Field(default_factory=list, max_length=50)


class DocumentSectionSpec(StrictModel):
    heading: str = Field(min_length=1, max_length=300)
    level: int = Field(default=1, ge=1, le=3)
    purpose: str = Field(default="", max_length=1000)
    blocks: list[DocumentBlock] = Field(min_length=1, max_length=100)


class DocumentSpec(StrictModel):
    document_type: Literal["report", "proposal", "memo", "sop", "minutes"] = "report"
    title: str = Field(min_length=1, max_length=300)
    executive_summary: str = Field(default="", max_length=6000)
    sections: list[DocumentSectionSpec] = Field(min_length=1, max_length=60)
    include_toc: bool = True


class PublicationSpec(StrictModel):
    document: DocumentSpec
    page_size: Literal["A4", "LETTER"] = "A4"
    tagged: bool = True
    bookmarks: bool = True
    searchable_text: bool = True


class MetricSpec(StrictModel):
    name: str = Field(min_length=1, max_length=200)
    formula: str = Field(default="", max_length=1000)
    unit: str = Field(default="", max_length=80)
    period: str = Field(default="", max_length=120)
    ref_ids: list[str] = Field(default_factory=list, max_length=30)


class DashboardView(StrictModel):
    title: str = Field(min_length=1, max_length=200)
    question: str = Field(min_length=1, max_length=500)
    chart_type: Literal["kpi", "bar", "line", "pie", "table", "scatter"]
    dimensions: list[str] = Field(default_factory=list, max_length=10)
    measures: list[str] = Field(default_factory=list, max_length=10)
    explanation: str = Field(default="", max_length=2000)


class DashboardSpec(StrictModel):
    title: str = Field(min_length=1, max_length=200)
    metrics: list[MetricSpec] = Field(default_factory=list, max_length=100)
    views: list[DashboardView] = Field(min_length=1, max_length=30)
    filters: list[str] = Field(default_factory=list, max_length=20)


class DiagramNode(StrictModel):
    id: str = Field(pattern=r"^[A-Za-z][A-Za-z0-9_]{0,63}$")
    label: str = Field(min_length=1, max_length=160)
    group: str = Field(default="", max_length=100)
    kind: Literal["start", "end", "step", "decision", "entity", "state", "actor"] = "step"


class DiagramEdge(StrictModel):
    source: str
    target: str
    label: str = Field(default="", max_length=100)


class DiagramSpec(StrictModel):
    diagram_type: Literal["flowchart", "sequence", "state", "er", "timeline", "gantt"] = "flowchart"
    title: str = Field(min_length=1, max_length=200)
    direction: Literal["TD", "LR", "BT", "RL"] = "TD"
    nodes: list[DiagramNode] = Field(min_length=1, max_length=60)
    edges: list[DiagramEdge] = Field(default_factory=list, max_length=100)
    accessible_description: str = Field(default="", max_length=1000)

    @model_validator(mode="after")
    def validate_edges(self):
        node_ids = {node.id for node in self.nodes}
        missing = {edge.source for edge in self.edges} | {edge.target for edge in self.edges}
        missing -= node_ids
        if missing:
            raise ValueError("连接引用了不存在的节点：" + ", ".join(sorted(missing)))
        return self


class WhiteboardRegion(StrictModel):
    title: str = Field(min_length=1, max_length=120)
    node_ids: list[str] = Field(default_factory=list, max_length=60)


class WhiteboardSpec(StrictModel):
    title: str = Field(min_length=1, max_length=200)
    diagram: DiagramSpec
    regions: list[WhiteboardRegion] = Field(default_factory=list, max_length=20)
    reading_direction: Literal["left_to_right", "top_to_bottom"] = "left_to_right"


class QualityIssue(StrictModel):
    code: str = Field(min_length=1, max_length=100)
    severity: Literal["error", "warning", "info"]
    message: str = Field(min_length=1, max_length=1000)
    location: str = Field(default="", max_length=300)
    repair_hint: str = Field(default="", max_length=1000)


class ArtifactQualityReport(StrictModel):
    format: ArtifactFormat
    score: int = Field(ge=0, le=100)
    passed: bool
    checks: dict[str, bool] = Field(default_factory=dict)
    issues: list[QualityIssue] = Field(default_factory=list, max_length=200)
    metrics: dict[str, int | float | str | bool] = Field(default_factory=dict)
    validator_version: str = "1.0"


class ArtifactPlan(StrictModel):
    format: ArtifactFormat
    evidence: EvidencePack
    presentation: Optional[PresentationSpec] = None
    document: Optional[DocumentSpec] = None
    publication: Optional[PublicationSpec] = None
    dashboard: Optional[DashboardSpec] = None
    diagram: Optional[DiagramSpec] = None
    whiteboard: Optional[WhiteboardSpec] = None
