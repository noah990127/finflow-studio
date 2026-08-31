from pathlib import Path

from app.deliverables import create_docx, create_pptx
from app.document_preview import preview_document
from app.office_preview import _render_structured_html
from app.models import DeliverableRequest, DeliverableSection


def _request() -> DeliverableRequest:
    return DeliverableRequest(
        title="Monthly Review",
        subtitle="Online preview",
        sections=[DeliverableSection(heading="Key findings", paragraphs=["Revenue increased."], bullets=["Review costs."])],
    )


def test_previews_generated_presentation(tmp_path: Path) -> None:
    path = tmp_path / "review.pptx"
    path.write_bytes(create_pptx(_request()))

    preview = preview_document(path, path.name)

    assert preview.kind == "presentation"
    assert len(preview.pages) == 2
    assert preview.pages[0].title == "Monthly Review"
    assert preview.pages[1].title == "Key findings"
    assert "Revenue increased." in preview.pages[1].blocks[0].text


def test_previews_generated_document(tmp_path: Path) -> None:
    path = tmp_path / "review.docx"
    path.write_bytes(create_docx(_request()))

    preview = preview_document(path, path.name)

    assert preview.kind == "document"
    assert any(block.type == "heading" and block.text == "Monthly Review" for block in preview.pages[0].blocks)
    assert any(block.text == "Revenue increased." for block in preview.pages[0].blocks)


def test_office_fallback_returns_readable_presentation_html(tmp_path: Path) -> None:
    path = tmp_path / "review.pptx"
    path.write_bytes(create_pptx(_request()))

    output = _render_structured_html(path, path.name, tmp_path)
    content = output.read_text(encoding="utf-8")

    assert "当前显示可读预览" in content
    assert "Monthly Review" in content
    assert "Key findings" in content
    assert "class=\"page presentation\"" in content
