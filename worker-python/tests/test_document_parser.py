from pathlib import Path
import pytest
from PIL import Image, UnidentifiedImageError

import duckdb
from docx import Document
from pptx import Presentation

from app.document_parser import parse_document
from app.models import FormulaColumn, SpreadsheetTransformRequest
from app.spreadsheet_files import transform_spreadsheet


def test_screenshot_is_valid_resource_without_invented_ocr(tmp_path: Path) -> None:
    path = tmp_path / "evidence.png"
    Image.new("RGB", (32, 32), "white").save(path)
    parsed = parse_document(path, path.name, "image/png")
    assert parsed.text_length == 0
    assert parsed.chunks == []
    assert parsed.warnings


def test_invalid_image_is_not_accepted(tmp_path: Path) -> None:
    path = tmp_path / "invalid.png"
    path.write_bytes(b"not an image")
    with pytest.raises(UnidentifiedImageError):
        parse_document(path, path.name, "image/png")


def test_docx_preserves_paragraph_location(tmp_path: Path) -> None:
    path = tmp_path / "经营报告.docx"
    document = Document()
    document.add_paragraph("本月收入同比增长 12%。")
    document.add_paragraph("毛利率下降主要受到原材料成本影响。")
    document.save(path)

    parsed = parse_document(path, path.name, "application/vnd.openxmlformats-officedocument.wordprocessingml.document")

    assert parsed.text_length > 10
    assert parsed.chunks[0].location["paragraph"] == 1
    assert parsed.chunks[0].content_hash.startswith("sha256:")


def test_pptx_preserves_slide_location(tmp_path: Path) -> None:
    path = tmp_path / "复盘.pptx"
    presentation = Presentation()
    slide = presentation.slides.add_slide(presentation.slide_layouts[1])
    slide.shapes.title.text = "经营复盘"
    slide.placeholders[1].text = "收入达成预算，费用率需要继续关注。"
    presentation.save(path)

    parsed = parse_document(path, path.name, "application/vnd.openxmlformats-officedocument.presentationml.presentation")

    assert parsed.chunks[0].location["slide"] == 1
    assert "经营复盘" in parsed.chunks[0].text


def test_parquet_exposes_schema_and_bounded_rows_for_knowledge_search(tmp_path: Path) -> None:
    path = tmp_path / "经营结果.parquet"
    with duckdb.connect() as connection:
        connection.execute(
            "COPY (SELECT range AS id, 'S' || range AS store FROM range(250)) TO ? (FORMAT PARQUET)",
            [str(path)],
        )

    parsed = parse_document(path, path.name, "application/vnd.apache.parquet")

    assert parsed.chunks[0].location["type"] == "parquet"
    assert "id store" in parsed.chunks[0].text
    assert any("仅解析前 200 行" in warning for warning in parsed.warnings)


def test_transform_workbook_preserves_formulas_and_creates_new_column(tmp_path: Path) -> None:
    import io
    from openpyxl import Workbook, load_workbook

    path = tmp_path / "finance.xlsx"
    workbook = Workbook()
    sheet = workbook.active
    sheet.append(["收入", "成本"])
    sheet.append([100, 40])
    sheet.append([100, 40])
    workbook.save(path)
    content = transform_spreadsheet(path, path.name, SpreadsheetTransformRequest(
        rename_headers={"收入": "营业收入"},
        formula_columns=[FormulaColumn(name="利润", formula="=A{row}-B{row}")],
        remove_duplicates=True,
    ))
    result = load_workbook(io.BytesIO(content), data_only=False)
    assert result.active.max_row == 2
    assert result.active["A1"].value == "营业收入"
    assert result.active["C2"].value == "=A2-B2"
    result.close()


def test_pdf_retains_text_and_marks_scanned_pages_including_page_number(tmp_path: Path) -> None:
    from reportlab.pdfgen.canvas import Canvas

    image_path = tmp_path / "scan.png"
    Image.new("RGB", (50, 40), "blue").save(image_path)
    path = tmp_path / "policy.pdf"
    canvas = Canvas(str(path))
    canvas.drawString(80, 700, "Text policy")
    canvas.showPage()
    canvas.drawImage(str(image_path), 20, 20)
    canvas.showPage()
    canvas.drawString(80, 700, "Page 3")
    canvas.drawImage(str(image_path), 20, 20)
    canvas.save()
    parsed = parse_document(path, path.name, "application/pdf")
    assert [chunk.location["page"] for chunk in parsed.chunks] == [1, 3]
    assert all(chunk.location["text_extraction_version"] == 2 for chunk in parsed.chunks)
    assert any("第2页" in warning and "未提取到文字" in warning for warning in parsed.warnings)
    assert any("第3页" in warning and "文字层" in warning for warning in parsed.warnings)


def test_docx_mixed_image_is_not_counted_as_fully_parsed(tmp_path: Path) -> None:
    image_path = tmp_path / "scan.png"
    Image.new("RGB", (32, 32), "white").save(image_path)
    path = tmp_path / "policy.docx"
    document = Document()
    document.add_picture(str(image_path))
    document.save(path)
    parsed = parse_document(path, path.name, "")
    assert parsed.chunks == [] and any("嵌入图形" in w for w in parsed.warnings)
    document.add_paragraph("For internal review")
    document.save(path)
    mixed = parse_document(path, path.name, "")
    assert mixed.chunks[0].text == "For internal review" and mixed.warnings


def test_pptx_native_tables_and_image_only_slides(tmp_path: Path) -> None:
    from pptx.util import Inches

    image_path = tmp_path / "scan.png"
    Image.new("RGB", (32, 32), "white").save(image_path)
    deck = Presentation()
    rows = deck.slides.add_slide(deck.slide_layouts[6])
    rows.shapes.add_table(1, 1, 0, 0, Inches(1), Inches(1)).table.cell(0, 0).text = "Quarter"
    visual = deck.slides.add_slide(deck.slide_layouts[6])
    visual.shapes.add_picture(str(image_path), Inches(1), Inches(1))
    path = tmp_path / "policy.pptx"
    deck.save(path)
    parsed = parse_document(path, path.name, "")
    assert parsed.chunks[0].text == "Quarter"
    assert parsed.chunks[0].location["text_extraction_version"] == 2
    assert any("第2张幻灯片" in warning for warning in parsed.warnings)
