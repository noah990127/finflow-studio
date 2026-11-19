from dataclasses import dataclass
import asyncio

from ..deliverables import (
    create_docx,
    create_excalidraw,
    create_financial_report,
    create_html_slides,
    create_mermaid,
    create_pdf,
    create_pptx,
)
from ..models import DeliverableRequest
from ..services.presentation import enforce_presentation_rules
from ..ppt_skills import reflow_for_readability
from .models import ArtifactFormat, ArtifactQualityReport, EvidencePack
from .planning import prepare_for_format
from .quality import evaluate_artifact
from .presentation_review import review_rendered
from .models import QualityIssue


@dataclass(frozen=True)
class GeneratedArtifact:
    content: bytes
    prepared_request: DeliverableRequest
    evidence: EvidencePack
    quality: ArtifactQualityReport


RENDERERS = {
    "pptx": create_pptx,
    "html_slides": create_html_slides,
    "docx": create_docx,
    "pdf": create_pdf,
    "financial_report": create_financial_report,
    "mermaid": create_mermaid,
    "excalidraw": create_excalidraw,
}


async def generate_artifact(output_format: ArtifactFormat, request: DeliverableRequest) -> GeneratedArtifact:
    prepared, evidence = await prepare_for_format(output_format, request)
    content = await asyncio.to_thread(RENDERERS[output_format], prepared)
    quality = evaluate_artifact(output_format, prepared, content)
    if output_format == "pptx" and quality.passed:
        quality = await asyncio.to_thread(review_rendered, content, quality)
        _require_template_render_check(prepared, quality)
    if not quality.passed and output_format in {"pptx", "html_slides"} and prepared.ppt_skill != "blue-professional-v2":
        # A model-produced page plan may be structurally valid yet fail the rendered-file
        # quality gate. Preserve the verified source material and rebuild pages with the
        # deterministic density/layout rules before failing the whole workflow.
        # Fixed runs must not summarize, truncate or re-plan supplied content.
        visual_failure = any(issue.code in {"rendered_text_loss", "text_overlap", "shape_out_of_bounds"}
                             for issue in quality.issues)
        repaired = (reflow_for_readability(prepared) if output_format == "pptx" and visual_failure else
                    prepared if request.plan_mode == "fixed" else enforce_presentation_rules(prepared))
        repaired_content = await asyncio.to_thread(RENDERERS[output_format], repaired)
        repaired_quality = evaluate_artifact(output_format, repaired, repaired_content)
        if output_format == "pptx" and repaired_quality.passed:
            repaired_quality = await asyncio.to_thread(review_rendered, repaired_content, repaired_quality)
            _require_template_render_check(repaired, repaired_quality)
        if repaired_quality.passed:
            repaired_quality.metrics["repair_attempts"] = 1
            return GeneratedArtifact(
                content=repaired_content,
                prepared_request=repaired,
                evidence=evidence,
                quality=repaired_quality,
            )
    if not quality.passed:
        errors = [issue.message for issue in quality.issues if issue.severity == "error"]
        raise ValueError("成果文件校验未通过：" + "；".join(errors[:5]))
    quality.metrics["repair_attempts"] = 0
    return GeneratedArtifact(content=content, prepared_request=prepared, evidence=evidence, quality=quality)


def _require_template_render_check(request, quality):
    if request.ppt_skill not in {"presenton-executive-v1", "blue-professional-v2"}:
        return
    quality.metrics["template_id"] = request.ppt_skill
    quality.metrics["content_policy"] = "intent-v2"
    if not quality.checks.get("rendered_visual_checked"):
        quality.passed = False
        quality.issues.append(QualityIssue(code="template_render_unverified", severity="error",
            message="新版企业模板需要完成实际渲染检查，请检查 LibreOffice、Poppler 和中文字体"))
