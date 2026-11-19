"""Bounded browser diagnostics. Never log URLs, page content or credentials."""
import logging
import re
import secrets
from datetime import datetime, timezone
from urllib.parse import urlsplit
import json

logger = logging.getLogger("uvicorn.error")


def safe_origin(url):
    try:
        parsed = urlsplit(url)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname:
            return "internal"
        host = parsed.hostname
        if ":" in host:
            host = f"[{host}]"
        return f"{parsed.scheme}://{host}" + (f":{parsed.port}" if parsed.port else "")
    except ValueError:
        return "invalid"


class BrowserDiagnostics:
    def __init__(self):
        self.id = secrets.token_hex(8)
        self.events = []
        self.sequence = 0

    def record(self, kind, url="", **details):
        self.sequence += 1
        event = dict(sequence=self.sequence, time=datetime.now(timezone.utc).isoformat(),
                     kind=kind, origin=safe_origin(url), **details)
        self.events.append(event)
        self.events[:] = self.events[-80:]
        logger.info("browser_diagnostic %s", json.dumps(dict(diagnosticId=self.id, **event)))

    def summary(self):
        return {"diagnosticId": self.id, "diagnostics": list(self.events)}

    def request_failed(self, request):
        match = re.search(r"\bnet::(ERR_[A-Z0-9_]+)\b", request.failure or "")
        self.record("request_failed", request.url,
                    error=match.group(1) if match else "UNKNOWN_NETWORK_ERROR",
                    navigation=request.is_navigation_request(), resourceType=request.resource_type)

    def response(self, response):
        if not response.request.is_navigation_request() and response.status < 400:
            return
        headers = response.headers
        # Retain policy types, never raw policy values or redirect URLs.
        xframe = headers.get("x-frame-options", "").strip().upper()
        csp = headers.get("content-security-policy", "")
        self.record("response", response.url, status=response.status,
                    navigation=response.request.is_navigation_request(),
                    xFrameOptions=xframe if xframe in {"DENY", "SAMEORIGIN"} else "OTHER" if xframe else "",
                    frameAncestors=bool(re.search(r"(?:^|;)\s*frame-ancestors\s", csp, re.I)),
                    redirectOrigin=safe_origin(headers["location"]) if "location" in headers else "")

    def console(self, message):
        text = message.text.lower()
        if "refused to" in text and ("frame" in text or "content security policy" in text):
            self.record("browser_policy_error", reason="FRAME_OR_CSP_REJECTION")
        elif "err_blocked_by" in text:
            self.record("browser_policy_error", reason="BROWSER_BLOCKED_RESOURCE")

    def attach_page(self, page):
        self.record("page_opened")
        page.on("framenavigated", lambda frame: self.record(
            "navigation", frame.url, mainFrame=frame.parent_frame is None))
        page.on("console", self.console)
        page.on("crash", lambda _: self.record("page_crashed"))
        page.on("close", lambda _: self.record("page_closed"))

    def attach(self, context):
        context.on("requestfailed", self.request_failed)
        context.on("response", self.response)
        context.on("page", self.attach_page)
        for page in context.pages:
            self.attach_page(page)
