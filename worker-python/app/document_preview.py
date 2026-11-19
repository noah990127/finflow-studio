import json
from pathlib import Path
from typing import List

from docx import Document
from pptx import Presentation
from pypdf import PdfReader
from PIL import Image

from .models import DocumentPreview, PreviewBlock, PreviewPage
from .research import _json_tables


MAX_PAGES = 300
MAX_BLOCKS = 3000
MAX_TABLE_ROWS = 200
MAX_TEXT_CHARS = 20_000
MAX_DIAGRAM_CHARS = 5_000_000


def preview_document(path: Path, file_name: str) -> DocumentPreview:
    suffix = Path(file_name).suffix.lower()
    if suffix in {".png", ".jpg", ".jpeg", ".webp"}:
        with Image.open(path) as image:
            width, height = image.size
            image_format = image.format
            image.verify()
        return DocumentPreview(
            file_name=file_name, kind="image", title=Path(file_name).stem,
            pages=[PreviewPage(number=1, title="", blocks=[PreviewBlock(
                type="text", text=f"图片文件校验通过：{image_format}，{width} x {height} 像素。")])],
            warnings=["仅校验图片文件结构，未识别图片中的文字或业务内容；可在平台打开原图查看。"],
        )
    if suffix == ".pptx":
        return _preview_pptx(path, file_name)
    if suffix == ".docx":
        return _preview_docx(path, file_name)
    if suffix == ".pdf":
        return _preview_pdf(path, file_name)
    if suffix == ".json":
        payload = json.loads(path.read_text(encoding="utf-8-sig", errors="replace"))
        research = preview_research(payload, file_name)
        if research is not None:
            return research
        declared_tables = payload.get("tables") if isinstance(payload, dict) else payload
        blocks: list[PreviewBlock] = []
        if isinstance(declared_tables, list) and declared_tables and all(
                isinstance(table, dict) and isinstance(table.get("rows"), list) for table in declared_tables):
            for table in declared_tables:
                blocks.append(PreviewBlock(type="heading", text=str(table.get("title", "数据表"))))
                blocks.append(PreviewBlock(type="table", rows=[
                    [str(cell) for cell in row] for row in table["rows"] if isinstance(row, list)
                ]))
        else:
            blocks = [PreviewBlock(type="table", rows=[[str(cell) for cell in row] for row in table["rows"]])
                      for table in _json_tables(payload)]
        if not blocks:
            blocks = [PreviewBlock(type="text", text=json.dumps(payload, ensure_ascii=False, indent=2)[:MAX_TEXT_CHARS])]
        return DocumentPreview(file_name=file_name, kind="data", title=Path(file_name).stem,
                               pages=[PreviewPage(number=1, title="", blocks=blocks)])
    if suffix in {".txt", ".md", ".mermaid", ".mmd", ".excalidraw"}:
        limit = MAX_DIAGRAM_CHARS if suffix in {".mermaid", ".mmd", ".excalidraw"} else MAX_TEXT_CHARS
        text = path.read_text(encoding="utf-8-sig", errors="replace")
        if file_name.lower().endswith(".json.md"):
            try:
                research = preview_research(json.loads(text), file_name)
                if research is not None:
                    return research
            except ValueError:
                pass
        text = text[:limit]
        return DocumentPreview(
            file_name=file_name,
            kind="text",
            title=Path(file_name).stem,
            pages=[PreviewPage(number=1, title="", blocks=[PreviewBlock(type="text", text=text)])],
        )
    raise ValueError("该文件请使用 PDF 或表格预览方式")


def preview_research(payload, file_name: str) -> DocumentPreview | None:
    def string(value):
        return value if isinstance(value, str) else ""

    if not isinstance(payload, dict):
        return None
    sources = payload.get("sources", [payload] if "text" in payload else [])
    if not isinstance(sources, list) or not sources or not all(
        isinstance(s, dict) and isinstance(s.get("url"), str)
        and any(isinstance(s.get(k), str) for k in ("text", "snippet", "why_relevant"))
        for s in sources
    ):
        return None
    warnings = []
    failures = payload.get("failedSources", [])
    if isinstance(failures, list):
        warnings = [f"未能读取：{s.get('url', '')}（{s.get('error', '读取失败')}）"
                    for s in failures if isinstance(s, dict)]
    return DocumentPreview(file_name=file_name, kind="research", title=string(payload.get("topic")) or Path(file_name).stem,
                           pages=[], research_sources=[{
                               "title": string(s.get("title")) or s["url"], "url": s["url"],
                               "text": string(s.get("text")),
                               "summary": string(s.get("snippet")) or string(s.get("why_relevant")),
                               "capturedAt": string(s.get("captured_at")),
                               "embedAllowed": s.get("embed_allowed") is True,
                           } for s in sources], warnings=warnings)


def _preview_pdf(path: Path, file_name: str) -> DocumentPreview:
    reader = PdfReader(str(path))
    pages: List[PreviewPage] = []
    warnings: List[str] = []
    for number, page in enumerate(reader.pages[:MAX_PAGES], start=1):
        text = (page.extract_text() or "").strip()[:MAX_TEXT_CHARS]
        pages.append(PreviewPage(number=number, title="", blocks=[PreviewBlock(type="text", text=text)]))
    if len(reader.pages) > MAX_PAGES:
        warnings.append("PDF 页数较多，在线预览仅显示前 %d 页" % MAX_PAGES)
    return DocumentPreview(file_name=file_name, kind="document", title=Path(file_name).stem,
                           pages=pages, warnings=warnings)


def _preview_pptx(path: Path, file_name: str) -> DocumentPreview:
    presentation = Presentation(str(path))
    pages: List[PreviewPage] = []
    warnings: List[str] = []
    for slide_number, slide in enumerate(presentation.slides, start=1):
        if slide_number > MAX_PAGES:
            warnings.append("幻灯片较多，在线预览仅显示前 %d 页" % MAX_PAGES)
            break
        text_shapes = []
        for shape in slide.shapes:
            value = getattr(shape, "text", "").strip()
            if value:
                text_shapes.append((shape, value[:MAX_TEXT_CHARS]))
        title_shape = _presentation_title_shape(text_shapes, slide_number, presentation.slide_height)
        title = title_shape[1] if title_shape else "幻灯片 %d" % slide_number
        body_shapes = [item for item in text_shapes
                       if (title_shape is None or item[0] is not title_shape[0]) and not _presentation_chrome(*item)]
        body_shapes.sort(key=lambda item: (-_largest_font_size(item[0]), item[0].top, item[0].left))
        blocks = [PreviewBlock(type="text", text=value) for _, value in body_shapes]
        pages.append(PreviewPage(number=slide_number, title=title, blocks=blocks))
    return DocumentPreview(
        file_name=file_name,
        kind="presentation",
        title=Path(file_name).stem,
        pages=pages,
        warnings=warnings,
    )


def _presentation_title_shape(text_shapes, slide_number: int, slide_height: int):
    if not text_shapes:
        return None
    candidates = text_shapes if slide_number == 1 else [
        item for item in text_shapes if item[0].top < slide_height * 0.22
    ]
    candidates = candidates or text_shapes
    return max(candidates, key=lambda item: (_largest_font_size(item[0]), len(item[1])))


def _largest_font_size(shape) -> float:
    sizes = [
        run.font.size.pt for paragraph in shape.text_frame.paragraphs for run in paragraph.runs
        if run.text.strip() and run.font.size is not None
    ]
    return max(sizes) if sizes else 0.0


def _presentation_chrome(shape, value: str) -> bool:
    compact = value.strip().upper()
    if compact in {
        "FINBTP STUDIO", "STUDIO PROFESSIONAL", "MANAGEMENT REPORT",
        "结论先行 · 证据驱动", "企业汇报 · CLASSIC RED",
    }:
        return True
    return bool(compact.isdigit() and _largest_font_size(shape) <= 18)


def _preview_docx(path: Path, file_name: str) -> DocumentPreview:
    document = Document(str(path))
    blocks: List[PreviewBlock] = []
    warnings: List[str] = []
    for paragraph in document.paragraphs:
        text = paragraph.text.strip()
        if not text:
            continue
        style_name = (paragraph.style.name if paragraph.style else "").lower()
        block_type = "heading" if "heading" in style_name or "title" in style_name else "text"
        blocks.append(PreviewBlock(type=block_type, text=text[:MAX_TEXT_CHARS]))
        if len(blocks) >= MAX_BLOCKS:
            warnings.append("文档内容较多，在线预览已截断")
            break
    if len(blocks) < MAX_BLOCKS:
        for table in document.tables:
            rows = [[cell.text.strip()[:2000] for cell in row.cells] for row in table.rows[:MAX_TABLE_ROWS]]
            blocks.append(PreviewBlock(type="table", rows=rows))
            if len(table.rows) > MAX_TABLE_ROWS:
                warnings.append("文档表格较长，在线预览仅显示前 %d 行" % MAX_TABLE_ROWS)
    return DocumentPreview(
        file_name=file_name,
        kind="document",
        title=Path(file_name).stem,
        pages=[PreviewPage(number=1, title="", blocks=blocks)],
        warnings=warnings,
    )
