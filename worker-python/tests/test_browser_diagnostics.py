import json
from types import SimpleNamespace

from app.browser_runner.diagnostics import BrowserDiagnostics, safe_origin


def test_origins_strip_credentials_paths_and_tokens():
    assert safe_origin("https://user:secret@example.com/private/token?sid=secret#code") == "https://example.com"
    assert safe_origin("data:text/html,secret") == "internal"
    assert safe_origin("https://example.com:invalid") == "invalid"


def test_failures_and_headers_do_not_leak_secrets(caplog):
    diag = BrowserDiagnostics()
    request = SimpleNamespace(url="https://example.com/secret?sid=secret", resource_type="document",
                              failure="net::ERR_BLOCKED_BY_RESPONSE secret", is_navigation_request=lambda: True)
    with caplog.at_level("INFO", logger="uvicorn.error"):
        diag.request_failed(request)
        diag.response(SimpleNamespace(request=request, url=request.url, status=302, headers={
            "location": "https://user:secret@login.example.com/secret?code=secret",
            "set-cookie": "secret", "x-frame-options": "SAMEORIGIN",
            "content-security-policy": "frame-ancestors https://example.com/secret"}))
        diag.console(SimpleNamespace(text="Refused to frame https://secret because of Content Security Policy"))
    assert "secret" not in json.dumps(diag.summary()) + caplog.text
    assert diag.events[0]["error"] == "ERR_BLOCKED_BY_RESPONSE"
    assert diag.events[1]["frameAncestors"] is True
    assert diag.events[1]["redirectOrigin"] == "https://login.example.com"
    assert diag.events[2]["reason"] == "FRAME_OR_CSP_REJECTION"


def test_bounded_events_and_separate_connections():
    diag = BrowserDiagnostics()
    for _ in range(100):
        diag.record("navigation")
    assert len(diag.events) == 80
    assert diag.events[0]["sequence"] == 21
    assert diag.id != BrowserDiagnostics().id


def test_attaches_existing_pages_and_future_popups():
    class Emitter:
        def __init__(self, pages=()):
            self.pages, self.handlers = pages, {}

        def on(self, event, handler):
            self.handlers[event] = handler

    original, popup = Emitter(), Emitter()
    context = Emitter([original])
    diag = BrowserDiagnostics()
    diag.attach(context)
    context.handlers["page"](popup)
    popup.handlers["framenavigated"](SimpleNamespace(url="https://example.com/?sid=secret", parent_frame=None))
    assert "console" in original.handlers and "console" in popup.handlers
    assert diag.events[-1]["origin"] == "https://example.com"
