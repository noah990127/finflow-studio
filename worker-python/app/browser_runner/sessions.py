"""Short-lived, owner-scoped interactive browsers. No credentials enter tool arguments."""
import asyncio
import hashlib
import os
import secrets
import re
import time
import socket
import ipaddress
import json
from dataclasses import dataclass, field
from typing import Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, ConfigDict, Field

from .engine import Target, locate, origin, sites
from .diagnostics import BrowserDiagnostics
from .interaction_policy import classify, approval_context
from .editors import discover, resolve, fill_verified


def display_origin(url):
    parsed = urlsplit(url)
    return f"{parsed.scheme}://{parsed.netloc.rsplit('@', 1)[-1]}"


class SessionRequest(BaseModel):
    model_config = ConfigDict(extra="forbid")
    owner: str = Field(min_length=1)
    session: str = Field(default="", max_length=128)
    site: str = Field(default="", max_length=64)
    action: Literal["fill", "select", "click", "assert_text"] = "click"
    target: Target | None = None
    value: str = Field(default="", max_length=10000)
    pending: str = Field(default="", max_length=128)
    frame: int = Field(default=0, ge=0, le=20)
    url: str = Field(default="", max_length=2000)
    user_authorized: bool = False
    human_login: bool = False


@dataclass
class Session:
    owner: str
    site: str
    config: dict
    runtime: object
    browser: object
    page: object
    touched: float = field(default_factory=time.monotonic)
    lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    pending: dict = field(default_factory=dict)
    revision: int = 0
    writes: bool = False
    blocked: bool = False
    uncertain: bool = False
    human_until: float = 0
    blocked_origins: set = field(default_factory=set)
    blocked_navigation: str = ""
    user_authorized: bool = False
    account_epoch: int = 0
    blocked_requests: list = field(default_factory=list)
    diagnostics: BrowserDiagnostics = field(default_factory=BrowserDiagnostics)
    editor_refs: dict = field(default_factory=dict)


class Browsers:
    def __init__(self):
        self.sessions = {}
        self.open_lock = asyncio.Lock()

    def get(self, request):
        session = self.sessions.get(request.session)
        if not session or session.owner != request.owner:
            raise ValueError("浏览器会话不存在或不属于当前用户，请重新打开")
        if time.monotonic() - session.touched > 1800:
            raise ValueError("浏览器会话已过期，请重新打开并登录")
        session.touched = time.monotonic()
        return session

    async def reap(self, all_sessions=False):
        for key, session in list(self.sessions.items()):
            if not session.lock.locked() and (all_sessions or time.monotonic() - session.touched > 1800):
                self.sessions.pop(key, None)
                await session.browser.close()
                await session.runtime.stop()

    async def open(self, request):
        from playwright.async_api import async_playwright
        async with self.open_lock:
            await self.reap()
            configured = sites().get(request.site)
            config = dict(configured) if configured else {"url": request.url} if request.user_authorized and request.url else None
            if not config:
                raise ValueError("站点未获授权，请提供网站地址并申请用户连接授权")
            url = urlsplit(config["url"])
            if url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password:
                raise ValueError("站点配置无效")
            # A connection belongs to the user, not to an individual model turn.
            for key, existing in list(self.sessions.items()):
                if existing.owner != request.owner or origin(existing.config["url"]) != origin(config["url"]):
                    continue
                async with existing.lock:
                    if existing.browser.is_connected() and not existing.page.is_closed():
                        existing.touched = time.monotonic()
                        return dict(await self.snapshot(existing), session=key, site=existing.site, reused=True)
                    self.sessions.pop(key, None)
                    await existing.browser.close()
                    await existing.runtime.stop()
            if len(self.sessions) >= 8:
                raise ValueError("浏览器会话已满，请关闭不再使用的会话")
            if request.human_login and os.environ.get("BROWSER_INTERACTIVE_HEADED") != "true":
                raise ValueError("人工登录需要启用本机可见浏览器；不能降级为Agent代为登录")
            runtime = await async_playwright().start()
            browser = None
            try:
                executable = os.environ.get("BROWSER_EXECUTABLE_PATH")
                browser = await runtime.chromium.launch(
                    headless=os.environ.get("BROWSER_INTERACTIVE_HEADED") != "true", chromium_sandbox=True,
                    **({"executable_path": executable} if executable else {}))
                context = await browser.new_context(service_workers="block", accept_downloads=False)
                page = await context.new_page()
                context.set_default_timeout(10000)
                session = Session(request.owner, request.site, config, runtime, browser, page)
                session.diagnostics.attach(context)
                session.user_authorized = request.user_authorized
                # Establish user control before the very first website request.
                if request.human_login:
                    self.begin_takeover(session)
                allowed = {origin(config["url"]), *(origin(u) for u in config.get("allowedOrigins", []))}

                async def public_destination(address):
                    parsed = urlsplit(address)
                    if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
                        return False
                    try:
                        resolved = await asyncio.wait_for(asyncio.get_running_loop().getaddrinfo(
                            parsed.hostname, parsed.port or (443 if parsed.scheme == "https" else 80), type=socket.SOCK_STREAM), 3)
                        return bool(resolved) and all(ipaddress.ip_address(item[4][0]).is_global for item in resolved)
                    except (ValueError, OSError, asyncio.TimeoutError):
                        return False

                async def guard(route):
                    req = route.request
                    destination_allowed = origin(req.url) in allowed if configured else origin(req.url) == origin(config["url"]) and await public_destination(req.url)
                    if request.user_authorized and not destination_allowed:
                        # Public login frames/assets need no administrator-maintained domain list.
                        destination_allowed = await public_destination(req.url) and (
                            not req.is_navigation_request() or req.frame.parent_frame is not None or time.monotonic() < session.human_until)
                    # User-authorized sites need their normal transport, including delayed
                    # XHR and draft autosave. Business approval belongs to the UI action.
                    if not destination_allowed or (not session.user_authorized and req.method not in {"GET", "HEAD"} and not session.writes and time.monotonic() >= session.human_until):
                        session.blocked = True
                        reason = "DESTINATION_RESTRICTED" if not destination_allowed else "BACKGROUND_WRITE_REQUIRES_TAKEOVER"
                        session.diagnostics.record("platform_block", req.url, reason=reason,
                                                   navigation=req.is_navigation_request())
                        session.blocked_requests.append({"origin": display_origin(req.url), "method": req.method,
                                                         "navigation": req.is_navigation_request(), "reason": reason})
                        session.blocked_requests[:] = session.blocked_requests[-20:]
                        if origin(req.url) not in allowed and len(session.blocked_origins) < 20:
                            parsed = urlsplit(req.url)
                            session.blocked_origins.add(f"{parsed.scheme}://{parsed.netloc}")
                        if req.is_navigation_request():
                            parsed = urlsplit(req.url)
                            session.blocked_navigation = f"{parsed.scheme}://{parsed.netloc}"
                        await route.abort("blockedbyclient")
                    else:
                        await route.continue_()

                await context.route("**/*", guard)
                async def guard_socket(ws):
                    address = re.sub(r"^ws:", "http:", re.sub(r"^wss:", "https:", ws.url))
                    permitted = session.user_authorized and (
                        (bool(configured) and origin(address) in allowed) or await public_destination(address))
                    if permitted:
                        ws.connect_to_server()
                        session.diagnostics.record("websocket_allowed", address)
                    else:
                        session.diagnostics.record("platform_websocket_block", address)
                        await ws.close()
                await context.route_web_socket("**/*", guard_socket)
                if not configured and not await public_destination(config["url"]):
                    raise ValueError("不能连接本机、私有网络或无效地址；用户授权不会绕过基础网络隔离")
                await page.goto(config["url"], wait_until="domcontentloaded")
                key = secrets.token_urlsafe(24)
                result = dict(await self.snapshot(session), session=key, site=request.site)
                self.sessions[key] = session
                return result
            except BaseException as exc:
                if browser:
                    await browser.close()
                await runtime.stop()
                if "Executable doesn't exist" in str(exc):
                    raise ValueError("缺少与当前Playwright匹配的浏览器，请管理员安装Chromium后重试；可见登录窗口需要完整浏览器而非headless-shell") from exc
                raise

    def connection_status(self, session):
        return {"status": "CLOSED" if not session.browser.is_connected() or session.page.is_closed() else
                "HUMAN_CONTROL" if session.human_until else "AGENT_CONTROL",
                "loginWindowExpired": bool(session.human_until and time.monotonic() >= session.human_until),
                "origin": display_origin(session.config["url"]), "loginVerified": False,
                **session.diagnostics.summary()}

    def begin_takeover(self, session):
        session.pending.clear()
        session.editor_refs.clear()
        session.human_until = time.monotonic() + 300
        session.account_epoch += 1
        session.diagnostics.record("human_takeover_started")
        session.blocked = False
        session.blocked_origins.clear()
        session.blocked_requests.clear()
        session.blocked_navigation = ""

    async def snapshot(self, session):
        # Do not return URL queries (often login credentials), cookies, or password values.
        page = session.page
        if session.human_until:
            expired = time.monotonic() >= session.human_until
            return {"status": "HUMAN_CONTROL", "loginWindowExpired": expired,
                    **session.diagnostics.summary(),
                    "blockedOrigins": sorted(session.blocked_origins),
                    "blockedRequests": list(session.blocked_requests),
                    "blockedNavigationOrigin": session.blocked_navigation,
                    "message": "人工登录窗口已过期，请重新申请登录接管" if expired else
                    "请在原浏览器窗口完成登录后交还Agent；若页面已被阻断，请使用重新进入登录页。期间Agent不读取或操作页面"}
        needs_login = any([await frame.locator('input[type="password"]:visible').count() > 0 for frame in page.frames])
        url = urlsplit(page.url)
        frames = []
        if not needs_login:
            for index, frame in enumerate(page.frames[:21]):
                if not session.user_authorized and origin(frame.url) not in {origin(session.config["url"]), *(origin(u) for u in session.config.get("allowedOrigins", []))}:
                    continue
                snapshot = (await frame.locator("body").aria_snapshot())[:8000 if index == 0 else 2000]
                snapshot = re.sub(r'(https?://[^\s?"#]+)[?#][^\s"]*', r'\1', snapshot)
                frames.append({"frame": index, "snapshot": snapshot,
                               "editors": await discover(frame, session.editor_refs)})
        return {"url": f"{url.scheme}://{url.netloc}{url.path}",
                **session.diagnostics.summary(),
                "snapshot": "需要用户在浏览器窗口完成登录" if needs_login else frames,
                "status": "NEEDS_LOGIN" if needs_login else "NAVIGATION_BLOCKED" if session.blocked_navigation else "READY",
                "loginVerified": False,
                "blockedRequests": list(session.blocked_requests),
                "revision": session.revision, "blockedRequest": session.blocked,
                "blockedOrigins": sorted(session.blocked_origins),
                "blockedNavigationOrigin": session.blocked_navigation,
                "resultUncertain": session.uncertain}

    async def fingerprint(self, session, action):
        # Hash complete editing values (including rich text), not display excerpts.
        # Unrelated page text is excluded when a real form/dialog identifies the scope.
        element = await resolve(session.page.frames[action.frame], action.target, session.editor_refs)
        if await element.count() != 1:
            raise ValueError("页面已变化，操作目标不再唯一，请重新准备操作")
        frames = []
        for frame in session.page.frames:
            frames.append({"url": frame.url,
                           "fields": await frame.locator('input,textarea,select,[contenteditable],body').evaluate_all(
                               "els => els.map(e => ({name:e.name,id:e.id,type:e.type,value:e.value,checked:e.checked,richText:(e.isContentEditable || (e.tagName==='BODY' && document.designMode==='on'))?e.innerHTML:null}))")})
        context = {"version": 2, "frames": frames, "accountEpoch": session.account_epoch,
                   "targetContext": await approval_context(element)}
        return hashlib.sha256(json.dumps(context, sort_keys=True).encode()).hexdigest()

    async def operate(self, operation, request):
        if operation == "open":
            return await self.open(request)
        session = self.get(request)
        async with session.lock:
            if operation == "status":
                return self.connection_status(session)
            if operation in {"login", "restart_login"}:
                if os.environ.get("BROWSER_INTERACTIVE_HEADED") != "true":
                    raise ValueError("本服务未启用本机可见浏览器；远程部署需先接入安全的人工接管界面")
                self.begin_takeover(session)
                if operation == "restart_login":
                    # A fresh GET to the entry, never reload a failed POST or replay an OAuth callback.
                    entry = urlsplit(session.config["url"])
                    address = f"{entry.scheme}://{entry.netloc}{entry.path or '/'}"
                    session.diagnostics.record("login_entry_reopened", address)
                    await session.page.goto(address, wait_until="domcontentloaded")
                await session.page.bring_to_front()
                return await self.snapshot(session)
            if operation == "resume":
                if not session.human_until:
                    raise ValueError("当前浏览器不处于人工接管状态，请先查看原会话")
                session.human_until = 0
                session.diagnostics.record("human_takeover_ended")
                # Preserve login popups opened by the user instead of creating a new context.
                pages = [p for p in session.page.context.pages if not p.is_closed()]
                matching = [p for p in pages if origin(p.url) == origin(session.config["url"])]
                if matching:
                    session.page = matching[-1]
                session.account_epoch += 1
                session.uncertain = False
                session.revision += 1
                session.pending.clear()
                return await self.snapshot(session)
            if operation == "observe":
                return await self.snapshot(session)
            if operation == "close":
                self.sessions.pop(request.session, None)
                await session.browser.close()
                await session.runtime.stop()
                return {"status": "CLOSED"}
            if session.human_until:
                raise ValueError("用户正在接管浏览器，请等待用户完成并确认交还控制权")
            if operation == "preview":
                pending = session.pending.get(request.pending)
                if not pending or pending[2] < time.monotonic():
                    raise ValueError("待审批操作已过期，请重新查看页面并准备操作")
                action = pending[0]
                state = await self.snapshot(session)
                scope = {"session": request.session, "accountEpoch": session.account_epoch,
                         "action": action.action, "target": action.target.model_dump(), "frame": action.frame,
                         "value": action.value, "bindingVersion": 2}
                scope["fingerprint"] = await self.fingerprint(session, action)
                if pending[1] != scope["fingerprint"]:
                    raise ValueError("页面已变化，原审批失效；请重新查看页面并准备操作")
                return {"site": session.site or display_origin(session.config["url"]), "action": action.action,
                        "target": action.target.model_dump(), "frame": action.frame, "value": action.value,
                        "page": state, "scopeHash": hashlib.sha256(json.dumps(scope, sort_keys=True).encode()).hexdigest()}
            if session.uncertain and operation == "commit":
                raise ValueError("上次操作结果不确定，请人工核查；禁止自动重试提交")
            if operation == "commit":
                pending = session.pending.pop(request.pending, None)
                if not pending or pending[2] < time.monotonic():
                    raise ValueError("该审批已使用或过期，不能再次执行")
                action, fingerprint, _ = pending
                if fingerprint != await self.fingerprint(session, action):
                    raise ValueError("页面已变化，原审批失效；请重新查看页面并准备操作")
                return await self.perform(session, action, writes=True)
            if operation != "act" or request.target is None:
                raise ValueError("浏览器动作或目标缺失")
            target = request.target
            if request.frame >= len(session.page.frames):
                raise ValueError("页面框架已变化，请重新查看页面")
            element = await resolve(session.page.frames[request.frame], target, session.editor_refs)
            if await element.count() != 1:
                raise ValueError("TARGET_NOT_UNIQUE：目标不存在或匹配多个控件，请重新观察页面并使用editors中的编号")
            effect, reason = await classify(element, request, session)
            session.diagnostics.record("action_policy", effect=effect, reason=reason)
            if session.uncertain and effect not in {"VERIFY", "NAVIGATE"}:
                raise ValueError("上次操作结果不确定；可以查看记录并核验，但禁止修改或重复提交")
            if effect in {"VERIFY", "PREPARE", "NAVIGATE"}:
                result = await self.perform(session, request, writes=False)
                return dict(result, actionEffect=effect, policyReason=reason)
            # Unknown mutations are frozen before execution; the main backend owns approval.
            key = secrets.token_urlsafe(24)
            session.pending.clear()
            session.pending[key] = (request.model_copy(deep=True), await self.fingerprint(session, request), time.monotonic() + 600)
            return {"status": "NEEDS_APPROVAL", "pending": key, "session": request.session,
                    "action": request.action, "target": target.model_dump(), "value": request.value,
                    "actionEffect": effect, "policyReason": reason,
                    "message": "操作尚未执行；调用browser.commit申请用户确认"}

    async def perform(self, session, request, writes):
        from playwright.async_api import expect
        session.writes = writes
        session.blocked = False
        try:
            target = await resolve(session.page.frames[request.frame], request.target, session.editor_refs)
            await expect(target).to_have_count(1)
            if request.action == "fill":
                await fill_verified(target, request.value)
            elif request.action == "select":
                await target.select_option(request.value)
            elif request.action == "assert_text":
                if not request.value.strip():
                    raise ValueError("校验内容不能为空")
                await expect(target).to_contain_text(request.value)
            else:
                await target.click()
            session.revision += 1
            if writes and request.action == "click":
                session.uncertain = True
            elif request.action == "assert_text":
                session.uncertain = False
            result = await self.snapshot(session)
            result["actionExecuted"] = True
            result["businessSuccessVerified"] = request.action == "assert_text"
            return result
        except BaseException:
            if writes:
                session.uncertain = True
            raise
        finally:
            session.writes = False


browsers = Browsers()
