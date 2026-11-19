"""Verify the actual exported presentation, using the same Office conversion as previews."""
from collections import Counter
from io import BytesIO
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unicodedata

from PIL import Image
from pptx import Presentation
from pypdf import PdfReader
from pypdf.errors import PyPdfError

from ..office_preview import render_office_pdf
from .models import ArtifactQualityReport, QualityIssue


def text_coverage(expected: str, actual: str) -> float:
    def counts(text):
        text = unicodedata.normalize("NFKC", text).casefold()
        return Counter(c for c in text if c.isalnum())
    wanted = counts(expected)
    return (wanted & counts(actual)).total() / wanted.total() if wanted else 1.0


def render_pages(content: bytes, directory: Path) -> tuple[Path, list[Path]]:
    directory = directory.resolve()
    directory.mkdir(parents=True, exist_ok=True)
    poppler = os.getenv("PDFTOPPM_BINARY") or shutil.which("pdftoppm")
    if not poppler:
        raise RuntimeError("缺少 PDF 页面渲染程序 pdftoppm")
    # Separate generations so stale pages cannot count toward this review.
    work = Path(tempfile.mkdtemp(prefix="render-", dir=directory))
    source = work / "input.pptx"
    source.write_bytes(content)
    pdf = render_office_pdf(source, source.name, work)
    subprocess.run([poppler, "-png", "-r", "110", str(pdf), str(work / "page")],
                   capture_output=True, timeout=180, check=True)
    pages = sorted(work.glob("page-*.png"), key=lambda p: int(p.stem.rsplit("-", 1)[1]))
    if not pages:
        raise RuntimeError("PDF 未生成任何页面图像")
    return pdf, pages


def _slide_text(slide):
    parts = []
    for shape in slide.shapes:
        if shape.has_text_frame:
            parts.append(shape.text)
        if shape.has_table:
            parts.extend(cell.text for cell in shape.table.iter_cells() if not cell.is_spanned)
    return "\n".join(parts)


def review_rendered(content: bytes, quality: ArtifactQualityReport) -> ArtifactQualityReport:
    result = quality.model_copy(deep=True)
    mode = os.getenv("PRESENTATION_VISUAL_REVIEW", "auto").strip().lower()
    result.checks["rendered_visual_checked"] = False
    if mode == "off":
        result.metrics["visual_review_status"] = "disabled"
        return result

    def issue(code, message, location=""):
        result.passed = False
        result.issues.append(QualityIssue(code=code, severity="error", message=message, location=location))

    with tempfile.TemporaryDirectory(prefix="finflow-presentation-") as working_dir:
        try:
            pdf, images = render_pages(content, Path(working_dir))
            reader = PdfReader(pdf)
            pages = [page.extract_text() or "" for page in reader.pages]
            expected = [_slide_text(s) for s in Presentation(BytesIO(content)).slides]
            if len(expected) != len(pages) or len(pages) != len(images):
                issue("rendered_page_mismatch", f"原稿有 {len(expected)} 页，PDF 有 {len(pages)} 页，页面图像有 {len(images)} 页")
            for i, path in enumerate(images, start=1):
                with Image.open(path) as image:
                    if all(high - low < 5 for low, high in image.convert("RGB").getextrema()):
                        issue("rendered_blank_page", "渲染页面缺少可见内容", f"第 {i} 页")
            coverage = [text_coverage(text, pages[i] if i < len(pages) else "") for i, text in enumerate(expected)]
            bad_pages = [str(i) for i, value in enumerate(coverage, start=1) if value < .9]
            if bad_pages:
                issue("rendered_text_loss", "渲染文字覆盖不足 90%", "第 " + "、".join(bad_pages[:40]) + " 页")
            result.metrics.update(rendered_pages=len(images), minimum_text_coverage=min(coverage, default=0.0))
        except (OSError, RuntimeError, subprocess.SubprocessError, PyPdfError) as exc:
            result.metrics["visual_review_status"] = "unavailable"
            if mode == "required":
                issue("render_unavailable", "实际页面渲染不可用：" + str(exc)[:400])
            return result
    result.checks["rendered_visual_checked"] = True
    result.metrics["visual_review_status"] = "passed" if result.passed else "failed"
    return result
