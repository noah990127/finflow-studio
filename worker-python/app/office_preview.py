import os
import base64
import html
import logging
import re
import shutil
import subprocess
import sys
import zipfile
import xml.etree.ElementTree as ET
from pathlib import Path

from .document_preview import preview_document


SUPPORTED_OFFICE_EXTENSIONS = {
    ".doc", ".docx", ".odt", ".rtf",
    ".xls", ".xlsx", ".xlsm", ".ods",
    ".ppt", ".pptx", ".odp",
}

logger = logging.getLogger(__name__)


def render_office_pdf(source: Path, original_name: str, working_directory: Path) -> Path:
    suffix = Path(original_name).suffix.lower()
    if suffix not in SUPPORTED_OFFICE_EXTENSIONS:
        raise ValueError("当前文件格式不支持原样预览")

    binary = os.getenv("LIBREOFFICE_BINARY") or shutil.which("soffice") or shutil.which("libreoffice")
    if not binary:
        raise RuntimeError("原样预览服务尚未安装")

    input_path = working_directory / f"source{suffix}"
    _prepare_source(source, input_path, suffix)
    profile = working_directory / "profile"
    profile.mkdir(parents=True, exist_ok=True)
    environment = os.environ.copy()
    environment["LANG"] = "zh_CN.UTF-8"
    environment["LC_ALL"] = "zh_CN.UTF-8"
    font_cache = working_directory / "font-cache"
    font_cache.mkdir(parents=True, exist_ok=True)
    environment["XDG_CACHE_HOME"] = str(font_cache)
    mac_font_paths = [
        path for path in (
            Path("/Library/Fonts"),
            Path("/System/Library/Fonts"),
            Path("/System/Library/Fonts/Supplemental"),
        ) if path.exists()
    ]
    if mac_font_paths:
        configured_fonts = environment.get("SAL_FONTPATH", "")
        font_path = ":".join(str(path) for path in mac_font_paths)
        environment["SAL_FONTPATH"] = f"{font_path}:{configured_fonts}" if configured_fonts else font_path
    result = subprocess.run(
        [binary, "--headless", f"-env:UserInstallation={profile.as_uri()}",
         "--convert-to", "pdf", "--outdir", str(working_directory), str(input_path)],
        capture_output=True,
        text=True,
        timeout=180,
        check=False,
        env=environment,
    )
    output = working_directory / "source.pdf"
    if result.returncode != 0 or not output.exists() or output.stat().st_size == 0:
        detail = (result.stderr or result.stdout or "").strip()
        raise RuntimeError(f"文件版式转换失败{f'：{detail}' if detail else ''}")
    return output


def _prepare_source(source: Path, destination: Path, suffix: str) -> None:
    if sys.platform != "darwin" or suffix not in {".docx", ".xlsx", ".xlsm", ".pptx"}:
        shutil.copyfile(source, destination)
        return

    replacements = {
        b'typeface="Arial"': b'typeface="Arial Unicode MS"',
        b'typeface="Calibri"': b'typeface="Arial Unicode MS"',
        b'<a:ea typeface=""/>': b'<a:ea typeface="Arial Unicode MS"/>',
        "宋体".encode(): "Arial Unicode MS".encode(),
        "新細明體".encode(): "Arial Unicode MS".encode(),
    }
    with zipfile.ZipFile(source, "r") as source_zip, zipfile.ZipFile(destination, "w") as target_zip:
        for item in source_zip.infolist():
            content = source_zip.read(item.filename)
            if item.filename.endswith(".xml"):
                for old, new in replacements.items():
                    content = content.replace(old, new)
            if suffix == ".pptx" and item.filename.startswith("ppt/slides/slide") and item.filename.endswith(".xml"):
                content = _apply_presentation_font(content)
            target_zip.writestr(item, content)


def _apply_presentation_font(content: bytes) -> bytes:
    drawing_namespace = "http://schemas.openxmlformats.org/drawingml/2006/main"
    ET.register_namespace("a", drawing_namespace)
    ET.register_namespace("p", "http://schemas.openxmlformats.org/presentationml/2006/main")
    ET.register_namespace("r", "http://schemas.openxmlformats.org/officeDocument/2006/relationships")
    root = ET.fromstring(content)
    run_tag = f"{{{drawing_namespace}}}r"
    run_properties_tag = f"{{{drawing_namespace}}}rPr"
    latin_tag = f"{{{drawing_namespace}}}latin"
    east_asian_tag = f"{{{drawing_namespace}}}ea"
    for run in root.iter(run_tag):
        properties = run.find(run_properties_tag)
        if properties is None:
            properties = ET.Element(run_properties_tag, {"lang": "zh-CN"})
            run.insert(0, properties)
        if properties.find(latin_tag) is None:
            ET.SubElement(properties, latin_tag, {"typeface": "Arial Unicode MS"})
        if properties.find(east_asian_tag) is None:
            ET.SubElement(properties, east_asian_tag, {"typeface": "Arial Unicode MS"})
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def render_office_html(source: Path, original_name: str, working_directory: Path) -> Path:
    if sys.platform == "darwin" and shutil.which("qlmanage"):
        try:
            return _render_quicklook_html(source, original_name, working_directory)
        except (RuntimeError, subprocess.SubprocessError) as error:
            logger.warning("macOS native Office preview unavailable: %s", error)

    try:
        pdf = render_office_pdf(source, original_name, working_directory)
    except (RuntimeError, subprocess.SubprocessError) as error:
        logger.warning("Office page conversion unavailable, using readable preview: %s", error)
        return _render_structured_html(source, original_name, working_directory)
    binary = os.getenv("PDFTOPPM_BINARY") or shutil.which("pdftoppm")
    if not binary:
        logger.warning("PDF page renderer unavailable, using readable preview")
        return _render_structured_html(source, original_name, working_directory)

    prefix = working_directory / "page"
    result = subprocess.run(
        [binary, "-png", "-r", "130", str(pdf), str(prefix)],
        capture_output=True,
        text=True,
        timeout=180,
        check=False,
    )
    pages = sorted(working_directory.glob("page-*.png"), key=lambda item: int(item.stem.split("-")[-1]))
    if result.returncode != 0 or not pages:
        detail = (result.stderr or result.stdout or "").strip()
        logger.warning("PDF page rendering failed, using readable preview: %s", detail)
        return _render_structured_html(source, original_name, working_directory)

    images = "\n".join(
        f'<img src="data:image/png;base64,{base64.b64encode(page.read_bytes()).decode("ascii")}" '
        f'alt="第 {index} 页" loading="lazy">'
        for index, page in enumerate(pages, start=1)
    )
    title = html.escape(Path(original_name).name)
    output = working_directory / "preview.html"
    output.write_text(
        "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
        f"<title>{title}</title><style>html,body{{margin:0;background:#e7e9ed}}"
        "body{padding:24px;box-sizing:border-box}img{display:block;width:min(100%,1200px);height:auto;"
        "margin:0 auto 22px;background:#fff;box-shadow:0 2px 14px rgba(26,42,66,.16)}"
        "img:last-child{margin-bottom:0}@media(max-width:720px){body{padding:10px}img{margin-bottom:10px}}"
        f"</style></head><body>{images}</body></html>",
        encoding="utf-8",
    )
    return output


def _render_structured_html(source: Path, original_name: str, working_directory: Path) -> Path:
    preview = preview_document(source, original_name)
    pages: list[str] = []
    for page in preview.pages:
        blocks: list[str] = []
        for block in page.blocks:
            if block.type == "table":
                rows = "".join(
                    "<tr>" + "".join(f"<td>{html.escape(str(cell))}</td>" for cell in row) + "</tr>"
                    for row in block.rows
                )
                blocks.append(f"<div class=\"table-wrap\"><table>{rows}</table></div>")
            elif block.text.strip():
                tag = "h3" if block.type == "heading" else "p"
                blocks.append(f"<{tag}>{html.escape(block.text)}</{tag}>")
        title = html.escape(page.title) if page.title else ""
        pages.append(
            f'<article class="page {html.escape(preview.kind)}">'
            f'<span class="page-number">{page.number}</span>'
            f'{f"<h2>{title}</h2>" if title else ""}{"".join(blocks)}</article>'
        )
    warnings = "".join(f"<p>{html.escape(value)}</p>" for value in preview.warnings)
    output = working_directory / "preview.html"
    output.write_text(
        "<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">"
        f"<title>{html.escape(Path(original_name).name)}</title><style>"
        "*{box-sizing:border-box}html,body{margin:0;background:#e9edf2;color:#202733}"
        "body{padding:18px;font-family:-apple-system,BlinkMacSystemFont,'PingFang SC','Microsoft YaHei',sans-serif}"
        ".notice{max-width:1180px;margin:0 auto 14px;padding:10px 14px;border:1px solid #bfd4ea;"
        "border-radius:6px;background:#eef7ff;color:#315d86;font-size:13px}"
        ".page{position:relative;max-width:1180px;margin:0 auto 20px;padding:38px 46px;background:#fff;"
        "box-shadow:0 2px 14px rgba(26,42,66,.14);overflow:hidden}"
        ".page.presentation{aspect-ratio:16/9;min-height:520px}.page-number{position:absolute;right:20px;bottom:14px;color:#8793a2;font-size:12px}"
        "h2{margin:0 0 24px;font-size:28px;line-height:1.35}h3{margin:20px 0 8px;font-size:18px}"
        "p{margin:0 0 14px;font-size:17px;line-height:1.65;white-space:pre-wrap}"
        ".table-wrap{overflow:auto}table{border-collapse:collapse;width:100%}td{padding:8px;border:1px solid #d9e1ea;font-size:14px}"
        "@media(max-width:720px){body{padding:8px}.page{padding:24px 22px}.page.presentation{min-height:0}h2{font-size:21px}p{font-size:15px}}"
        "</style></head><body><div class=\"notice\">当前显示可读预览。原文件版式将在在线 Office 服务恢复后自动显示。</div>"
        f"{warnings}{''.join(pages)}</body></html>",
        encoding="utf-8",
    )
    return output


def _render_quicklook_html(source: Path, original_name: str, working_directory: Path) -> Path:
    suffix = Path(original_name).suffix.lower()
    input_path = working_directory / f"source{suffix}"
    shutil.copyfile(source, input_path)
    preview_directory = working_directory / "quicklook"
    preview_directory.mkdir(parents=True, exist_ok=True)
    result = subprocess.run(
        ["qlmanage", "-p", "-o", str(preview_directory), str(input_path)],
        capture_output=True,
        text=True,
        timeout=180,
        check=False,
    )
    previews = list(preview_directory.glob("*.qlpreview/Preview.html"))
    if result.returncode != 0 or not previews:
        detail = (result.stderr or result.stdout or "").strip()
        raise RuntimeError(f"系统原生预览失败{f'：{detail}' if detail else ''}")

    preview = previews[0]
    preview_html = preview.read_text(encoding="utf-8")
    pdf_binary = os.getenv("PDFTOPPM_BINARY") or shutil.which("pdftoppm")
    if not pdf_binary:
        raise RuntimeError("页面渲染服务尚未安装")

    attachment_pattern = re.compile(r'src="(Attachment\d+\.pdf)"')
    embedded: dict[str, str] = {}
    for attachment_name in set(attachment_pattern.findall(preview_html)):
        attachment = preview.parent / attachment_name
        if not attachment.exists():
            continue
        image_prefix = working_directory / Path(attachment_name).stem
        conversion = subprocess.run(
            [pdf_binary, "-f", "1", "-singlefile", "-png", "-r", "130", str(attachment), str(image_prefix)],
            capture_output=True,
            text=True,
            timeout=60,
            check=False,
        )
        image_path = image_prefix.with_suffix(".png")
        if conversion.returncode == 0 and image_path.exists():
            embedded[attachment_name] = (
                "data:image/png;base64," + base64.b64encode(image_path.read_bytes()).decode("ascii")
            )
    preview_html = attachment_pattern.sub(
        lambda match: f'src="{embedded.get(match.group(1), "")}"',
        preview_html,
    )
    preview_html = preview_html.replace(
        "</head>",
        "<style>body{margin:0;padding:16px 0 20px;font-family:-apple-system,BlinkMacSystemFont,"
        "\"PingFang SC\",sans-serif}div.slide{margin-left:auto!important;margin-right:auto!important}"
        "@media(max-width:980px){body{zoom:.88}}</style></head>",
        1,
    )
    output = working_directory / "preview.html"
    output.write_text(preview_html, encoding="utf-8")
    return output
