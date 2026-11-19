import asyncio
import base64
import csv
import hashlib
import io
import json
import os
from pathlib import Path
from typing import Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Target(BaseModel):
    model_config = ConfigDict(extra="forbid")
    by: Literal["label", "role", "text", "ref"]
    name: str = Field(min_length=1, max_length=200)
    role: Literal["button", "link", "heading", "textbox", "combobox", "status", "checkbox", "radio", "tab", "menuitem", "option"] = "button"


class Step(BaseModel):
    model_config = ConfigDict(extra="forbid")
    action: Literal["fill", "select", "click", "assert_text", "download"]
    target: Target
    value: str | dict[str, str] | None = None


class Script(BaseModel):
    model_config = ConfigDict(extra="forbid")
    version: Literal[1] = 1
    site: str = Field(pattern=r"^[a-zA-Z0-9_-]{1,64}$")
    steps: list[Step] = Field(min_length=2, max_length=40)

    @model_validator(mode="after")
    def valid_steps(self):
        if sum(s.action == "download" for s in self.steps) != 1 or self.steps[-1].action != "download":
            raise ValueError("脚本必须以唯一的下载步骤结束")
        if not any(s.action == "assert_text" for s in self.steps):
            raise ValueError("下载前必须校验页面结果")
        for step in self.steps:
            if isinstance(step.value, dict) and (set(step.value) != {"$param"} or not step.value["$param"]):
                raise ValueError("参数绑定只能使用 {$param:名称}")
            if step.action in {"fill", "select", "assert_text"} and step.value is None:
                raise ValueError("输入或校验动作缺少value")
        return self


class RunRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    script: Script
    parameters: dict[str, str] = Field(default_factory=dict, max_length=30)


def sites():
    return json.loads(os.environ.get("BROWSER_SITES_JSON", "{}"))


def site_config(site):
    config = sites().get(site)
    if not config:
        raise ValueError("站点未获管理员授权")
    url = urlsplit(config["url"])
    if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password:
        raise ValueError("站点配置无效")
    if not config.get("columns"):
        raise ValueError("站点必须配置下载数据字段校验")
    return config


def origin(url):
    value = urlsplit(url)
    return value.scheme, value.hostname, value.port or (443 if value.scheme == "https" else 80)


def value_of(step, parameters):
    if isinstance(step.value, dict):
        key = step.value["$param"]
        if key not in parameters:
            raise ValueError(f"缺少执行参数：{key}")
        return parameters[key]
    return step.value


def validate_request(request, config):
    for step in request.script.steps:
        value = value_of(step, request.parameters)
        if value is not None and len(value) > 2000:
            raise ValueError("参数过长")
        if step.action in {"click", "download"}:
            if step.target.by != "role" or step.target.role != "button" or step.target.name not in config.get("buttons", []):
                raise ValueError("操作不在管理员授权的查询和下载按钮清单内")
        if step.action in {"fill", "select"} and (step.target.by != "label" or step.target.name not in config.get("fields", [])):
            raise ValueError("输入字段未获授权")


def validate_csv(content, config, parameters):
    if len(content) > 10 * 1024 * 1024:
        raise ValueError("下载文件超过10MB限制")
    try:
        reader = csv.DictReader(io.StringIO(content.decode("utf-8-sig")))
        if not set(config["columns"]).issubset(reader.fieldnames or []):
            raise ValueError("下载文件缺少预期字段，可能是登录页面或错误文件")
        rows = list(reader)
        if not rows:
            raise ValueError("查询结果为空，请确认日期和业务范围")
        if any(None in row or any(v is None for v in row.values()) for row in rows):
            raise ValueError("CSV行结构不完整")
        date_column = config.get("dateColumn")
        if date_column:
            from datetime import date
            start = date.fromisoformat(parameters["startDate"])
            end = date.fromisoformat(parameters["endDate"])
            if start > end or any(not start <= date.fromisoformat(row[date_column]) <= end for row in rows):
                raise ValueError("导出数据不在请求的日期范围内")
        return len(rows)
    except (UnicodeError, csv.Error, KeyError) as exc:
        raise ValueError("下载内容或日期参数无效") from exc


def locate(page, target):
    if target.by == "ref":
        raise ValueError("临时编辑器编号仅用于交互会话，固定脚本请使用稳定定位")
    if target.by == "label":
        return page.get_by_label(target.name, exact=True)
    if target.by == "text":
        return page.get_by_text(target.name, exact=True)
    return page.get_by_role(target.role, name=target.name, exact=True)


async def open_context(playwright, config):
    executable = os.environ.get("BROWSER_EXECUTABLE_PATH")
    browser = await playwright.chromium.launch(headless=True, chromium_sandbox=True,
                                                **({"executable_path": executable} if executable else {}))
    context = await browser.new_context(accept_downloads=True, service_workers="block", viewport={"width": 1280, "height": 800})

    async def guard(route):
        request = route.request
        if origin(request.url) != origin(config["url"]) or request.method not in {"GET", "HEAD"}:
            await route.abort("blockedbyclient")
        else:
            await route.continue_()

    await context.route("**/*", guard)
    await context.route_web_socket("**/*", lambda ws: ws.close())
    context.set_default_timeout(10000)
    return browser, context


async def inspect_site(site):
    from playwright.async_api import async_playwright
    config = site_config(site)
    async with asyncio.timeout(30), async_playwright() as p:
        browser, context = await open_context(p, config)
        try:
            page = await context.new_page()
            await page.goto(config["url"], wait_until="domcontentloaded")
            if await page.locator('input[type="password"]').count():
                raise ValueError("NEEDS_LOGIN：当前版本不接管个人登录态，请使用获授权的无登录测试站点")
            return {"site": site, "url": page.url, "snapshot": (await page.locator("body").aria_snapshot())[:16000],
                    "allowedFields": config.get("fields", []), "allowedButtons": config.get("buttons", []),
                    "expectedColumns": config["columns"], "scriptVersion": 1}
        finally:
            await browser.close()


async def execute(request):
    from playwright.async_api import async_playwright, expect
    config = site_config(request.script.site)
    validate_request(request, config)
    digest = hashlib.sha256(request.script.model_dump_json().encode()).hexdigest()
    audit = {"engine": "playwright", "scriptHash": digest, "site": request.script.site, "steps": []}
    async with asyncio.timeout(120), async_playwright() as p:
        browser, context = await open_context(p, config)
        try:
            page = await context.new_page()
            await page.goto(config["url"], wait_until="domcontentloaded")
            if await page.locator('input[type="password"]').count():
                raise ValueError("NEEDS_LOGIN：需要人工登录，未继续操作")
            content = None
            for index, step in enumerate(request.script.steps):
                target = locate(page, step.target)
                value = value_of(step, request.parameters)
                await expect(target).to_have_count(1)
                if step.action == "fill":
                    await target.fill(value)
                elif step.action == "select":
                    await target.select_option(value)
                elif step.action == "click":
                    await target.click()
                elif step.action == "assert_text":
                    await expect(target).to_contain_text(value)
                else:
                    async with page.expect_download() as download_info:
                        await target.click()
                    download = await download_info.value
                    if await download.failure():
                        raise ValueError("报表下载失败")
                    path = await download.path()
                    if Path(path).stat().st_size > 10 * 1024 * 1024:
                        raise ValueError("下载文件超过10MB限制")
                    content = Path(path).read_bytes()
                audit["steps"].append({"index": index + 1, "action": step.action, "target": step.target.model_dump(), "status": "SUCCEEDED"})
            count = validate_csv(content, config, request.parameters)
            audit.update(status="SUCCEEDED", rowCount=count, fileHash=hashlib.sha256(content).hexdigest())
            screenshot = await page.screenshot()
            return {"contentBase64": base64.b64encode(content).decode(), "mediaType": "text/csv", "audit": audit,
                    "screenshotBase64": base64.b64encode(screenshot).decode()}
        finally:
            await browser.close()
