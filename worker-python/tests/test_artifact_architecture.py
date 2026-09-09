import json

import pytest
from pydantic import ValidationError

from app.artifacts.models import DiagramEdge, DiagramNode, DiagramSpec, EvidencePack
from app.artifacts.planning import build_evidence_pack, prepare_for_format
from app.artifacts.quality import evaluate_artifact
from app.deliverables import (
    create_docx,
    create_excalidraw,
    create_financial_report,
    create_html_slides,
    create_mermaid,
    create_pdf,
    create_pptx,
)
from app.models import DeliverableRequest


def artifact_request() -> DeliverableRequest:
    return DeliverableRequest.model_validate({
        "title": "区域经营诊断",
        "subtitle": "基于已核验经营数据",
        "include_citations": True,
        "sections": [{
            "heading": "核心发现",
            "core_message": "华南收入同比增长 18%，但回款周期延长",
            "paragraphs": ["华南区域收入增长较快。应收账款周转天数从 42 天增加至 51 天，需要同步加强回款管理。"],
            "bullets": ["收入增长主要来自企业客户", "建议建立大额应收周度跟踪机制"],
            "refs": [{
                "ref_id": "ref-1", "resource_id": "resource-1", "version": 2,
                "source_name": "区域经营数据.csv", "content_hash": "abc123",
                "location": {"sheet": "summary", "rows": "2:14"},
            }],
            "chart": {
                "type": "line", "title": "区域收入趋势", "categories": ["Q1", "Q2", "Q3"],
                "series": [{"name": "收入（万元）", "values": [1200, 1380, 1510]}],
                "source_ref": "[Ref 1]",
            },
        }],
    })


def test_format_specs_are_strict_and_validate_relationships() -> None:
    with pytest.raises(ValidationError):
        EvidencePack(task_goal="分析经营", unsupported=True)
    with pytest.raises(ValidationError, match="不存在的节点"):
        DiagramSpec(
            title="审批流程",
            nodes=[DiagramNode(id="start", label="开始")],
            edges=[DiagramEdge(source="start", target="missing")],
        )


def test_evidence_pack_keeps_claims_and_provenance() -> None:
    evidence = build_evidence_pack(artifact_request())

    assert evidence.sources[0].resource_id == "resource-1"
    assert evidence.sources[0].version == 2
    assert evidence.claims
    assert all("ref-1" in claim.ref_ids for claim in evidence.claims)


@pytest.mark.asyncio
async def test_document_planner_builds_report_structure_without_formatting_noise() -> None:
    async def complete(_system: str, _prompt: str) -> str:
        return json.dumps({
            "document_type": "report",
            "executive_summary": "华南业务增长较快，但回款风险上升。",
            "sections": [{
                "heading": "经营表现与风险",
                "purpose": "解释增长与风险",
                "paragraphs": ["华南收入同比增长 18%，应收账款周转天数增加至 51 天 [Ref 1]。"],
                "bullets": ["建立大额应收周度跟踪机制"],
                "ref_indexes": [1],
            }],
        }, ensure_ascii=False)

    prepared, evidence = await prepare_for_format("docx", artifact_request(), complete=complete)

    assert prepared.sections[0].heading == "执行摘要"
    assert prepared.sections[1].heading == "经营表现与风险"
    assert prepared.sections[1].refs[0].ref_id == "ref-1"
    assert evidence.sources[0].content_hash == "abc123"
    assert "CSS" not in " ".join(prepared.sections[1].paragraphs)


def test_all_supported_renderers_pass_format_quality_checks() -> None:
    request = artifact_request()
    renderers = {
        "pptx": create_pptx,
        "html_slides": create_html_slides,
        "docx": create_docx,
        "pdf": create_pdf,
        "financial_report": create_financial_report,
        "mermaid": create_mermaid,
        "excalidraw": create_excalidraw,
    }

    for output_format, renderer in renderers.items():
        content = renderer(request)
        report = evaluate_artifact(output_format, request, content)
        assert report.passed, (output_format, report.model_dump())
        assert report.checks["non_empty"]
        assert report.metrics["bytes"] > 0


def test_financial_report_has_semantic_layer_views_and_states() -> None:
    report = json.loads(create_financial_report(artifact_request()))

    assert report["semantic_layer"]["metrics"][0]["name"] == "收入（万元）"
    assert report["views"][0]["question"] == "华南收入同比增长 18%，但回款周期延长"
    assert report["views"][0]["dataset"]["dimensions"] == ["category", "收入（万元）"]
    assert report["views"][0]["aria"]["show"] is True
    assert report["states"]["empty"]


def test_interactive_report_rejects_empty_data_shell() -> None:
    request = artifact_request().model_copy(deep=True)
    request.sections[0].chart = None
    content = create_financial_report(request)
    quality = evaluate_artifact("financial_report", request, content)

    assert quality.passed is False
    assert any(issue.code == "dashboard_without_chart" for issue in quality.issues)


def test_generated_mermaid_includes_accessible_description() -> None:
    source = create_mermaid(artifact_request()).decode("utf-8")

    assert "accTitle:" in source
    assert "accDescr:" in source


def test_process_request_generates_decisions_and_recovery_paths() -> None:
    request = DeliverableRequest.model_validate({
        "title": "采购审批流程",
        "sections": [{
            "heading": "审批过程",
            "paragraphs": ["申请人提交材料。负责人审批，驳回时返回修改。材料不全时补充，通过后安排付款。"],
        }],
        "include_citations": False,
    })

    source = create_mermaid(request).decode("utf-8")

    assert source.startswith("flowchart LR")
    assert "|不通过/补充|" in source
    assert "{" in source and "}" in source


@pytest.mark.asyncio
async def test_presentation_replaces_generic_source_heading_with_core_message() -> None:
    request = DeliverableRequest.model_validate({
        "title": "科技公司经营分析",
        "sections": [{
            "heading": "已核验资料",
            "paragraphs": ["公司甲收入同比增长 21%。", "资本开支同比增长 38%。"],
        }],
        "include_citations": False,
    })

    async def unavailable(_system: str, _prompt: str):
        raise RuntimeError("模型不可用")

    prepared, _ = await prepare_for_format("pptx", request, complete=unavailable)

    assert prepared.sections[0].heading == "公司甲收入同比增长 21%"
    assert all(section.heading != "已核验资料" for section in prepared.sections)
