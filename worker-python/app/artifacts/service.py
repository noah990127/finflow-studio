from dataclasses import dataclass

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
from .models import ArtifactFormat, ArtifactQualityReport, EvidencePack
from .planning import prepare_for_format
from .quality import evaluate_artifact


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
    content = RENDERERS[output_format](prepared)
    quality = evaluate_artifact(output_format, prepared, content)
    if not quality.passed:
        errors = [issue.message for issue in quality.issues if issue.severity == "error"]
        raise ValueError("成果质量检查未通过：" + "；".join(errors[:5]))
    return GeneratedArtifact(content=content, prepared_request=prepared, evidence=evidence, quality=quality)
