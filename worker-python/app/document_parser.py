import csv
import hashlib
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Tuple

import duckdb
from docx import Document
from openpyxl import load_workbook
from pypdf import PdfReader
from pptx import Presentation
from pptx.enum.shapes import MSO_SHAPE_TYPE
from pptx.oxml.ns import qn
from PIL import Image

from .config import settings
from .models import ParsedChunk, ParsedDocument


@dataclass
class LocatedText:
    text: str
    location: Dict[str, object]


def parse_document(path: Path, file_name: str, media_type: str) -> ParsedDocument:
    suffix = Path(file_name).suffix.lower()
    warnings: List[str] = []
    if suffix in {".png", ".jpg", ".jpeg", ".webp"}:
        with Image.open(path) as image:
            image.verify()
        located = []
        warnings.append("图片已保存，未进行文字识别")
    elif suffix == ".pdf" or media_type == "application/pdf":
        located, warnings = _parse_pdf(path)
    elif suffix == ".docx":
        located, warnings = _parse_docx(path)
    elif suffix == ".pptx":
        located, warnings = _parse_pptx(path)
    elif suffix in {".xlsx", ".xlsm"}:
        located, warnings = _parse_xlsx(path)
    elif suffix in {".csv", ".tsv"}:
        located, warnings = _parse_delimited(path, suffix == ".tsv")
    elif suffix == ".parquet" or media_type == "application/vnd.apache.parquet":
        located, warnings = _parse_parquet(path)
    elif suffix in {".txt", ".md", ".mermaid", ".mmd", ".excalidraw", ".srt", ".vtt", ".json"} or media_type.startswith("text/"):
        located = [LocatedText(_read_text(path), {"type": "text", "start": 0})]
    elif suffix in {".mp4", ".mov", ".mkv", ".mp3", ".wav", ".m4a"}:
        located = _transcribe_media(path)
    else:
        raise ValueError("暂不支持该文件格式")

    if suffix in {".docx", ".pptx", ".pdf"} or media_type == "application/pdf":
        # Persist alongside each old-style Ref so the server can distinguish
        # historical parses that silently skipped scanned pages.
        for item in located:
            item.location["text_extraction_version"] = 2
    chunks = _chunk_located_text(located)
    return ParsedDocument(
        file_name=file_name,
        media_type=media_type or "application/octet-stream",
        title=Path(file_name).stem,
        text_length=sum(len(item.text) for item in located),
        chunks=chunks,
        warnings=warnings,
    )


def _page_warning(pages: list[int], unit: str, reason: str) -> str:
    sample = "、".join(map(str, pages[:12]))
    listed = f"第{sample}{unit}" if len(pages) <= 12 else f"第{sample}{unit}等 {len(pages)} {unit}"
    return f"{listed}{reason}，请核对原件并补充可引用文本"


def _parse_pdf(path: Path) -> Tuple[List[LocatedText], List[str]]:
    reader = PdfReader(str(path), strict=False)
    if reader.is_encrypted:
        try:
            reader.decrypt("")
        except Exception as exception:
            raise ValueError("PDF 已加密，无法解析") from exception
    result: List[LocatedText] = []
    blank, mixed = [], []
    for index, page in enumerate(reader.pages, start=1):
        text = page.extract_text() or ""
        if text.strip():
            result.append(LocatedText(text, {"type": "pdf", "page": index}))
            if len(page.images):
                mixed.append(index)
        else:
            blank.append(index)
    warnings = []
    if blank:
        warnings.append(_page_warning(blank, "页", "未提取到文字，可能是扫描页或空白页"))
    if mixed:
        warnings.append(_page_warning(mixed, "页", "仅提取文字层，页内图片未经文字识别"))
    return result, warnings


def _parse_docx(path: Path) -> Tuple[List[LocatedText], List[str]]:
    document = Document(str(path))
    result: List[LocatedText] = []
    for index, paragraph in enumerate(document.paragraphs):
        if paragraph.text.strip():
            result.append(LocatedText(paragraph.text, {"type": "word", "paragraph": index + 1}))
    for table_index, table in enumerate(document.tables):
        rows = ["\t".join(cell.text for cell in row.cells) for row in table.rows]
        if rows:
            result.append(LocatedText("\n".join(rows), {"type": "word_table", "table": table_index + 1}))
    warnings = []
    if document.element.body.xpath(".//w:drawing | .//w:pict | .//w:object | .//w:txbxContent"):
        warnings.append("Word 仅提取正文段落及表格，嵌入图形或文本框未经核对，请补充可引用文本")
    if not any(row.text.strip() for row in result):
        warnings.append("Word 未提取到可引用的正文文字，请核对原件")
    return result, warnings


def _slide_text(shapes, parts: list[str]) -> bool:
    incomplete = False
    for shape in shapes:
        if shape.element.xpath(".//a:blip"):
            # Includes photographs placed into PowerPoint picture placeholders.
            incomplete = True
        if shape.shape_type == MSO_SHAPE_TYPE.GROUP:
            if _slide_text(shape.shapes, parts):
                incomplete = True
        elif shape.has_table:
            for row in shape.table.rows:
                parts.append("\t".join(cell.text for cell in row.cells))
        elif shape.has_text_frame and shape.text.strip():
            parts.append(shape.text)
        elif shape.element.tag in (qn("p:pic"), qn("p:graphicFrame")):
            # Can also be SmartArt, an image, or linked media.
            incomplete = True
    return incomplete


def _parse_pptx(path: Path) -> Tuple[List[LocatedText], List[str]]:
    presentation = Presentation(str(path))
    result: List[LocatedText] = []
    blank, mixed = [], []
    for slide_index, slide in enumerate(presentation.slides, start=1):
        text_parts: List[str] = []
        has_visual = _slide_text(slide.shapes, text_parts)
        text = "\n".join(text_parts)
        if text.strip():
            result.append(LocatedText(text, {"type": "powerpoint", "slide": slide_index}))
            if has_visual:
                mixed.append(slide_index)
        else:
            blank.append(slide_index)
    warnings = []
    if blank:
        warnings.append(_page_warning(blank, "张幻灯片", "未提取到文字"))
    if mixed:
        warnings.append(_page_warning(mixed, "张幻灯片", "仅提取文字和表格，图像、图表或嵌入对象未经核对"))
    return result, warnings


def _parse_xlsx(path: Path) -> Tuple[List[LocatedText], List[str]]:
    workbook = load_workbook(str(path), read_only=True, data_only=False, keep_links=False)
    result: List[LocatedText] = []
    warnings: List[str] = []
    max_rows = 10_000
    for sheet in workbook.worksheets:
        rows: List[str] = []
        for row_index, row in enumerate(sheet.iter_rows(values_only=True), start=1):
            if row_index > max_rows:
                warnings.append("工作表 %s 超过 %d 行，作为知识资料仅解析前 %d 行" % (sheet.title, max_rows, max_rows))
                break
            rows.append("\t".join("" if value is None else str(value) for value in row))
        if rows:
            result.append(LocatedText("\n".join(rows), {"type": "excel", "sheet": sheet.title, "rows": "1:%d" % len(rows)}))
    workbook.close()
    return result, warnings


def _parse_parquet(path: Path) -> Tuple[List[LocatedText], List[str]]:
    max_rows = 200
    max_columns = 50
    warnings: List[str] = []
    with duckdb.connect() as connection:
        cursor = connection.execute("SELECT * FROM read_parquet(?) LIMIT ?", [str(path), max_rows + 1])
        columns = [item[0] for item in cursor.description]
        rows = cursor.fetchall()
    visible_columns = columns[:max_columns]
    visible_rows = rows[:max_rows]
    lines = ["\t".join(visible_columns)]
    lines.extend("\t".join("" if value is None else str(value) for value in row[:max_columns]) for row in visible_rows)
    if len(columns) > max_columns:
        warnings.append(f"Parquet 共 {len(columns)} 列，作为资料仅解析前 {max_columns} 列；完整字段可在数据预览中查看")
    if len(rows) > max_rows:
        warnings.append(f"Parquet 超过 {max_rows} 行，作为资料仅解析前 {max_rows} 行；完整数据可分页预览")
    return [LocatedText("\n".join(lines), {
        "type": "parquet", "rows": f"1:{len(visible_rows)}", "columns": len(columns),
    })], warnings


def _parse_delimited(path: Path, tsv: bool) -> Tuple[List[LocatedText], List[str]]:
    delimiter = "\t" if tsv else ","
    rows: List[str] = []
    warnings: List[str] = []
    with path.open("r", encoding="utf-8-sig", errors="replace", newline="") as stream:
        reader = csv.reader(stream, delimiter=delimiter)
        for index, row in enumerate(reader, start=1):
            if index > 10_000:
                warnings.append("表格超过 10000 行，作为知识资料仅解析前 10000 行")
                break
            rows.append("\t".join(row))
    return [LocatedText("\n".join(rows), {"type": "table", "rows": "1:%d" % len(rows)})], warnings


def _read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8-sig", errors="replace")


def _transcribe_media(path: Path) -> List[LocatedText]:
    try:
        from faster_whisper import WhisperModel
    except ImportError as exception:
        raise ValueError("当前部署未安装音视频转写组件 faster-whisper") from exception
    try:
        model = WhisperModel(settings.whisper_model, device=settings.whisper_device,
                             compute_type=settings.whisper_compute_type)
        segments, _ = model.transcribe(str(path), vad_filter=True, beam_size=5)
        result = []
        for segment in segments:
            text = segment.text.strip()
            if text:
                result.append(LocatedText(text, {
                    "type": "media",
                    "start_seconds": round(segment.start, 3),
                    "end_seconds": round(segment.end, 3),
                }))
        if not result:
            raise ValueError("音视频中没有识别到可转写内容")
        return result
    except ValueError:
        raise
    except Exception as exception:
        raise ValueError("音视频转写失败，请检查模型文件和媒体编码") from exception


def _chunk_located_text(items: Iterable[LocatedText]) -> List[ParsedChunk]:
    result: List[ParsedChunk] = []
    for item in items:
        normalized = re.sub(r"[ \t]+", " ", item.text).strip()
        if not normalized:
            continue
        start = 0
        while start < len(normalized):
            end = min(len(normalized), start + settings.knowledge_chunk_chars)
            if end < len(normalized):
                boundary = max(normalized.rfind("\n", start, end), normalized.rfind("。", start, end))
                if boundary > start + settings.knowledge_chunk_chars // 2:
                    end = boundary + 1
            text = normalized[start:end].strip()
            if text:
                location = dict(item.location)
                location["char_start"] = start
                location["char_end"] = end
                result.append(
                    ParsedChunk(
                        index=len(result),
                        text=text,
                        location=location,
                        content_hash="sha256:" + hashlib.sha256(text.encode("utf-8")).hexdigest(),
                    )
                )
            if end >= len(normalized):
                break
            start = max(start + 1, end - settings.knowledge_chunk_overlap)
    return result
