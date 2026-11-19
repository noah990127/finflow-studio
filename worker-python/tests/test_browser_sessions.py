import asyncio
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from app.browser_runner.sessions import Browsers, SessionRequest


@pytest.fixture
def portal(monkeypatch):
    state = {"sent": 0, "loaded": 0, "saved": 0}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            if self.path == "/workbench":
                self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8"); self.end_headers()
                self.wfile.write(b'''<html><body>
<aside role="status">Unread: 1</aside>
<button type="button" onclick="document.querySelector('form').hidden=false;setTimeout(()=>fetch('/data',{method:'POST'}),80)">Compose</button>
<form hidden onsubmit="event.preventDefault()">
<label>Recipient<input aria-label="Recipient"></label>
<div role="textbox" aria-label="Body" contenteditable="true" oninput="setTimeout(()=>fetch('/autosave',{method:'POST'}),80)"></div>
<button type="button" onclick="fetch('/send',{method:'POST'}).then(()=>document.querySelector('[role=status]').textContent='Sent successfully')">Send</button>
<button type="submit">Search</button></form>
<button type="button">Proceed</button>
<a href="/delete?id=1">View</a>
</body></html>''')
                return
            if self.path == "/auto-login":
                self.send_response(200); self.end_headers()
                self.wfile.write(b'<html><body><form action="/authenticate" method="POST"></form><script>document.forms[0].submit()</script></body></html>')
                return
            if self.path == "/account":
                self.send_response(200); self.end_headers()
                self.wfile.write(b"Authenticated" if "login=ok" in self.headers.get("Cookie", "") else b"Login required")
                return
            if self.path == "/login":
                self.send_response(200); self.end_headers()
                self.wfile.write(b'<html><body><form action="/authenticate" method="POST"><button>Sign in</button></form></body></html>')
                return
            content = b'''<html><body><label>Recipient<input aria-label="Recipient"></label>
<label>Body<textarea aria-label="Body"></textarea></label>
<button onclick="fetch('/send',{method:'POST'}).then(r=>r.text()).then(t=>document.querySelector('[role=status]').textContent=t)">Send</button>
<div role="status" aria-label="Result">Draft</div></body></html>'''
            self.send_response(200)
            self.send_header("Content-Type", "text/html")
            self.end_headers()
            self.wfile.write(content)

        def do_POST(self):
            if self.path in {"/data", "/autosave"}:
                state["loaded" if self.path == "/data" else "saved"] += 1
                self.send_response(200); self.end_headers(); self.wfile.write(b"OK")
                return
            if self.path == "/authenticate":
                self.send_response(303)
                self.send_header("Set-Cookie", "login=ok; Path=/; HttpOnly")
                self.send_header("Location", "/account")
                self.end_headers()
                return
            state["sent"] += 1
            self.send_response(200)
            self.end_headers()
            self.wfile.write(b"Sent successfully")

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    monkeypatch.setenv("BROWSER_INTERACTIVE_HEADED", "false")
    monkeypatch.setenv("BROWSER_SITES_JSON", json.dumps({"mail-test": {
        "url": f"http://127.0.0.1:{server.server_port}/", "allowedOrigins": [f"http://localhost:{server.server_port}"],
        "fields": ["Recipient", "Body"], "buttons": []}}))
    yield state
    server.shutdown()
    server.server_close()
    thread.join()


def request(session="", **kwargs):
    return SessionRequest(owner="alice", session=session, **kwargs)


@pytest.mark.asyncio
async def test_user_journey_normal_network_navigation_editing_and_one_send_approval(portal, monkeypatch):
    import os
    config = json.loads(os.environ["BROWSER_SITES_JSON"])
    config["mail-test"]["url"] += "workbench"
    monkeypatch.setenv("BROWSER_SITES_JSON", json.dumps(config))
    browsers = Browsers()
    try:
        sid = (await browsers.operate("open", request(site="mail-test", user_authorized=True)))["session"]
        session = browsers.sessions[sid]
        async with session.page.expect_response(lambda r: r.url.endswith('/data')) as response:
            result = await browsers.operate("act", request(sid, target={"by": "text", "name": "Compose"}))
            assert result["actionEffect"] == "NAVIGATE"
            assert not result["resultUncertain"]
        assert (await response.value).status == 200
        assert portal["loaded"] == 1
        await browsers.operate("act", request(sid, action="fill", target={"by": "role", "role": "textbox", "name": "Recipient"}, value="example@example.invalid"))
        async with session.page.expect_response(lambda r: r.url.endswith('/autosave')):
            result = await browsers.operate("act", request(sid, action="fill", target={"by": "role", "role": "textbox", "name": "Body"}, value="Draft body"))
        assert result["actionEffect"] == "PREPARE" and portal["saved"] == 1
        assert portal["sent"] == 0
        pending = await browsers.operate("act", request(sid, target={"by": "role", "name": "Send"}))
        assert pending["actionEffect"] == "EXTERNAL" and pending["status"] == "NEEDS_APPROVAL"
        first = await browsers.operate("preview", request(sid, pending=pending["pending"]))
        await session.page.locator('aside').evaluate("el => el.textContent='Unread: 37'")
        second = await browsers.operate("preview", request(sid, pending=pending["pending"]))
        assert first["scopeHash"] == second["scopeHash"]
        async with session.page.expect_response(lambda r: r.url.endswith('/send')):
            await browsers.operate("commit", request(sid, pending=pending["pending"]))
        assert portal["sent"] == 1
        verified = await browsers.operate("act", request(sid, action="assert_text", target={"by":"text", "name":"Sent successfully"}, value="Sent successfully"))
        assert verified["businessSuccessVerified"]
        with pytest.raises(ValueError, match="已使用"):
            await browsers.operate("commit", request(sid, pending=pending["pending"]))
    finally:
        await browsers.reap(all_sessions=True)


@pytest.mark.asyncio
async def test_unknown_and_deceptive_controls_still_require_approval(portal):
    browsers = Browsers()
    try:
        sid = (await browsers.operate("open", request(site="mail-test", user_authorized=True)))["session"]
        session = browsers.sessions[sid]
        await session.page.goto(session.config["url"] + 'workbench')
        await browsers.operate("act", request(sid, target={"by":"text", "name":"Compose"}))
        for target in [{"by":"text", "name":"Proceed"}, {"by":"role", "name":"Search"}, {"by":"role", "role":"link", "name":"View"}]:
            assert (await browsers.operate("act", request(sid, target=target)))["status"] == "NEEDS_APPROVAL"
        session.config["buttons"] = ["Send"]
        assert (await browsers.operate("act", request(sid, target={"by":"role", "name":"Send"})))["status"] == "NEEDS_APPROVAL"
        assert portal["sent"] == 0
    finally:
        await browsers.reap(all_sessions=True)


@pytest.mark.asyncio
async def test_rich_text_changes_invalidate_pending_action(portal):
    browsers = Browsers()
    try:
        sid = (await browsers.operate("open", request(site="mail-test", user_authorized=True)))["session"]
        session = browsers.sessions[sid]
        await session.page.goto(session.config["url"] + 'workbench')
        await browsers.operate("act", request(sid, target={"by":"text", "name":"Compose"}))
        editor = session.page.get_by_role('textbox', name='Body')
        await editor.fill('a' * 12000)
        pending = await browsers.operate("act", request(sid, target={"by":"role", "name":"Send"}))
        await editor.fill('a' * 11999 + 'b')
        with pytest.raises(ValueError, match="页面已变化"):
            await browsers.operate("commit", request(sid, pending=pending["pending"]))
        assert portal["sent"] == 0
    finally:
        await browsers.reap(all_sessions=True)


@pytest.mark.asyncio
async def test_authorized_websocket_connects_but_private_destination_stays_blocked(portal, monkeypatch):
    from websockets.asyncio.server import serve
    async def echo(socket):
        async for message in socket:
            await socket.send(message)
    browsers = Browsers()
    async with serve(echo, '127.0.0.1', 0) as server:
        port = server.sockets[0].getsockname()[1]
        try:
            sid = (await browsers.operate("open", request(site="mail-test", user_authorized=True)))["session"]
            session = browsers.sessions[sid]
            # A private address not explicitly configured must still be blocked.
            result = await asyncio.wait_for(session.page.evaluate("""port => new Promise(resolve => {
                const ws = new WebSocket(`ws://127.0.0.1:${port}/`);
                ws.onclose = () => resolve('closed'); ws.onopen = () => resolve('opened');
            })""", port), 10)
            assert result == 'closed'
        finally:
            await browsers.reap(all_sessions=True)

        # Start a new context with this test server explicitly authorized.
        import os
        config = json.loads(os.environ['BROWSER_SITES_JSON'])
        config['mail-test']['allowedOrigins'].append(f'http://127.0.0.1:{port}')
        monkeypatch.setenv('BROWSER_SITES_JSON', json.dumps(config))
        try:
            sid = (await browsers.operate("open", request(site="mail-test", user_authorized=True)))["session"]
            session = browsers.sessions[sid]
            result = await asyncio.wait_for(session.page.evaluate("""port => new Promise(resolve => {
                const ws = new WebSocket(`ws://127.0.0.1:${port}/`);
                ws.onopen = () => ws.send('heartbeat');
                ws.onmessage = e => {resolve(e.data); ws.close()};
            })""", port), 10)
            assert result == 'heartbeat'
            assert any(e['kind'] == 'websocket_allowed' for e in session.diagnostics.events)
        finally:
            await browsers.reap(all_sessions=True)


@pytest.fixture
def visible_mode_without_window(monkeypatch):
    from playwright.async_api import BrowserType
    launch = BrowserType.launch

    async def headless_launch(self, **kwargs):
        return await launch(self, **dict(kwargs, headless=True))

    monkeypatch.setattr(BrowserType, "launch", headless_launch)
    monkeypatch.setenv("BROWSER_INTERACTIVE_HEADED", "true")


@pytest.mark.asyncio
async def test_takeover_precedes_first_request_and_automatic_login_post(portal, visible_mode_without_window, monkeypatch):
    import os
    config = json.loads(os.environ["BROWSER_SITES_JSON"])
    config["mail-test"]["url"] += "auto-login"
    monkeypatch.setenv("BROWSER_SITES_JSON", json.dumps(config))
    browsers = Browsers()
    try:
        result = await browsers.operate("open", request(site="mail-test", human_login=True))
        session = browsers.sessions[result["session"]]
        await session.page.wait_for_url("**/account")
        assert result["status"] == "HUMAN_CONTROL"
        events = session.diagnostics.events
        takeover = next(e["sequence"] for e in events if e["kind"] == "human_takeover_started")
        assert all(takeover < e["sequence"] for e in events if e["kind"] == "response")
        assert not session.blocked_requests
        assert "Authenticated" in await session.page.inner_text("body")
        with pytest.raises(ValueError, match="接管"):
            await browsers.operate("act", request(result["session"], action="click"))
        reused = await browsers.operate("open", request(site="mail-test", human_login=True))
        assert reused["session"] == result["session"]
        assert session.page.url.endswith("/account")
        await browsers.operate("resume", request(result["session"]))
        assert (await browsers.operate("observe", request(result["session"])))["status"] == "READY"
        epoch = session.account_epoch
        reused = await browsers.operate("open", request(site="mail-test", human_login=True))
        assert reused["status"] == "READY"
        assert session.account_epoch == epoch
    finally:
        await browsers.reap(all_sessions=True)


@pytest.mark.asyncio
async def test_restart_login_get_entry_not_failed_post(portal, visible_mode_without_window, monkeypatch):
    import os
    config = json.loads(os.environ["BROWSER_SITES_JSON"])
    config["mail-test"]["url"] += "login"
    monkeypatch.setenv("BROWSER_SITES_JSON", json.dumps(config))
    browsers = Browsers()
    try:
        sid = (await browsers.operate("open", request(site="mail-test")))["session"]
        session = browsers.sessions[sid]
        async with session.page.expect_event("framenavigated"):
            async with session.page.expect_event("requestfailed"):
                await session.page.get_by_role("button", name="Sign in").click()
        session.pending["stale"] = {}
        session.config["url"] += "?sid=secret#callback"
        result = await browsers.operate("restart_login", request(sid))
        assert result["status"] == "HUMAN_CONTROL"
        assert session.page.url.endswith("/login")
        assert not session.pending
        assert not session.blocked_requests
        assert not any(c["name"] == "login" for c in await session.page.context.cookies())
        await session.page.get_by_role("button", name="Sign in").click()
        await session.page.wait_for_url("**/account")
        assert "Authenticated" in await session.page.inner_text("body")
        assert portal["sent"] == 0
        with pytest.raises(ValueError, match="不属于"):
            await browsers.operate("restart_login", SessionRequest(owner="bob", session=sid))
    finally:
        await browsers.reap(all_sessions=True)


@pytest.mark.asyncio
async def test_human_login_fails_closed_when_no_visible_browser(portal):
    with pytest.raises(ValueError, match="可见浏览器"):
        await Browsers().operate("open", request(site="mail-test", human_login=True))


@pytest.mark.asyncio
async def test_network_failure_diagnostics_survive_human_takeover(portal, monkeypatch):
    browsers = Browsers()
    try:
        sid = (await browsers.operate("open", request(site="mail-test")))["session"]
        session = browsers.sessions[sid]
        async with session.page.expect_event("requestfailed"):
            await session.page.evaluate("fetch('/send?secret=test-token', {method:'POST'}).catch(() => null)")
        monkeypatch.setenv("BROWSER_INTERACTIVE_HEADED", "true")
        result = await browsers.operate("login", request(sid))
        events = result["diagnostics"]
        assert any(e["kind"] == "platform_block" for e in events)
        assert any(e["kind"] == "request_failed" and e["error"] == "ERR_BLOCKED_BY_CLIENT" for e in events)
        assert events[-1]["kind"] == "human_takeover_started"
        assert "test-token" not in json.dumps(events)
        assert "snapshot" not in result
        assert portal["sent"] == 0
    finally:
        await browsers.reap(all_sessions=True)


@pytest.mark.asyncio
async def test_interactive_journey_requires_approval_and_verifies_result(portal):
    browsers = Browsers()
    try:
        opened = await browsers.operate("open", request(site="mail-test"))
        sid = opened["session"]
        assert opened["diagnosticId"]
        assert any(event["kind"] == "response" for event in opened["diagnostics"])
        assert any(event["kind"] == "navigation" for event in opened["diagnostics"])
        for name, value in [("Recipient", "test@example.invalid"), ("Body", "Congratulations")]:
            result = await browsers.operate("act", request(sid, action="fill", target={"by": "label", "name": name}, value=value))
            assert result["actionExecuted"]
        pending = await browsers.operate("act", request(sid, action="click", target={"by": "role", "name": "Send"}))
        assert pending["status"] == "NEEDS_APPROVAL"
        assert portal["sent"] == 0
        with pytest.raises(ValueError, match="不属于"):
            await browsers.operate("observe", SessionRequest(owner="bob", session=sid))
        preview = await browsers.operate("preview", request(sid, pending=pending["pending"]))
        assert preview["target"]["name"] == "Send"
        await browsers.operate("commit", request(sid, pending=pending["pending"]))
        with pytest.raises(ValueError, match="结果不确定"):
            await browsers.operate("act", request(sid, action="click", target={"by": "role", "name": "Send"}))
        verified = await browsers.operate("act", request(sid, action="assert_text", target={"by": "role", "role": "status", "name": "Result"}, value="Sent successfully"))
        assert verified["businessSuccessVerified"]
        assert portal["sent"] == 1
        with pytest.raises(ValueError, match="已使用"):
            await browsers.operate("commit", request(sid, pending=pending["pending"]))
        await browsers.operate("close", request(sid))
        with pytest.raises(ValueError, match="不存在"):
            await browsers.operate("observe", request(sid))
    finally:
        await browsers.reap(all_sessions=True)


@pytest.mark.asyncio
async def test_page_changes_invalidate_approval(portal):
    browsers = Browsers()
    try:
        sid = (await browsers.operate("open", request(site="mail-test")))["session"]
        pending = await browsers.operate("act", request(sid, action="click", target={"by": "role", "name": "Send"}))
        await browsers.operate("act", request(sid, action="fill", target={"by": "label", "name": "Recipient"}, value="changed@example.invalid"))
        with pytest.raises(ValueError, match="页面已变化"):
            await browsers.operate("commit", request(sid, pending=pending["pending"]))
        assert portal["sent"] == 0
        with pytest.raises(ValueError, match="可见浏览器"):
            await browsers.operate("login", request(sid))
    finally:
        await browsers.reap(all_sessions=True)


@pytest.mark.asyncio
async def test_unknown_site_rejected(portal):
    with pytest.raises(ValueError, match="未获授权"):
        await Browsers().operate("open", request(site="unknown"))


@pytest.mark.asyncio
async def test_authorization_hash_tracks_full_content_and_account(portal):
    browsers = Browsers()
    try:
        sid = (await browsers.operate("open", request(site="mail-test", user_authorized=True)))["session"]
        session = browsers.sessions[sid]
        await session.page.get_by_label("Body", exact=True).fill("a" * 12000)
        async def prepare():
            pending = await browsers.operate("act", request(sid, action="click", target={"by": "role", "name": "Send"}))
            return await browsers.operate("preview", request(sid, pending=pending["pending"]))
        first = await prepare()
        assert (await prepare())["scopeHash"] == first["scopeHash"]
        await session.page.get_by_label("Body", exact=True).fill("a" * 11999 + "b")
        assert (await prepare())["scopeHash"] != first["scopeHash"]
        await session.page.get_by_label("Body", exact=True).fill("a" * 12000)
        session.account_epoch += 1
        assert (await prepare())["scopeHash"] != first["scopeHash"]
        assert portal["sent"] == 0
    finally:
        await browsers.reap(all_sessions=True)


@pytest.mark.asyncio
async def test_user_grant_cannot_open_unconfigured_private_network(portal):
    with pytest.raises(ValueError, match="私有网络"):
        await Browsers().operate("open", request(url="http://127.0.0.1:1/", user_authorized=True))


@pytest.mark.asyncio
async def test_human_takeover_pauses_agent_and_expiry_blocks_reuse(portal, monkeypatch):
    browsers = Browsers()
    try:
        sid = (await browsers.operate("open", request(site="mail-test")))["session"]
        # Exercise the state machine without requiring a display on CI.
        monkeypatch.setenv("BROWSER_INTERACTIVE_HEADED", "true")
        assert (await browsers.operate("login", request(sid)))["status"] == "HUMAN_CONTROL"
        assert (await browsers.operate("observe", request(sid)))["status"] == "HUMAN_CONTROL"
        session = browsers.sessions[sid]
        session.blocked_navigation = "https://login.example.invalid"
        session.blocked_origins.add(session.blocked_navigation)
        observed = await browsers.operate("observe", request(sid))
        assert observed["blockedNavigationOrigin"] == "https://login.example.invalid"
        assert "管理员" not in observed["message"]
        assert "snapshot" not in observed
        session.human_until -= 301
        assert (await browsers.operate("observe", request(sid)))["loginWindowExpired"]
        with pytest.raises(ValueError, match="接管"):
            await browsers.operate("act", request(sid, action="click", target={"by": "role", "name": "Send"}))
        await browsers.operate("resume", request(sid))
        assert (await browsers.operate("observe", request(sid)))["status"] == "NAVIGATION_BLOCKED"
        browsers.sessions[sid].touched -= 1900
        with pytest.raises(ValueError, match="过期"):
            await browsers.operate("observe", request(sid))
        await browsers.reap()
        assert not browsers.sessions
    finally:
        await browsers.reap(all_sessions=True)


@pytest.mark.asyncio
@pytest.mark.parametrize("cross_origin", [False, True])
async def test_connection_reuse_retains_login_and_isolates_users(portal, monkeypatch, cross_origin):
    browsers = Browsers()
    try:
        first = await browsers.operate("open", request(site="mail-test", user_authorized=True))
        sid = first["session"]
        session = browsers.sessions[sid]
        base = session.config["url"].rstrip("/")
        assert (await browsers.operate("status", request(sid)))["origin"] == base
        if cross_origin:
            base = base.replace("127.0.0.1", "localhost")
        await session.page.goto(base + "/login")
        # Transport is independent of agent action approval on a user-authorized site.
        async with session.page.expect_event("framenavigated", predicate=lambda frame: frame == session.page.main_frame):
            await session.page.get_by_role("button", name="Sign in").click()
        await session.page.wait_for_url("**/account")
        assert not session.blocked_requests
        monkeypatch.setenv("BROWSER_INTERACTIVE_HEADED", "true")
        human = await browsers.operate("login", request(sid))
        assert not human["blockedRequests"]
        assert not human["blockedNavigationOrigin"]
        await session.page.goto(base + "/login")
        await session.page.get_by_role("button", name="Sign in").click()
        await session.page.wait_for_url("**/account")
        reused = await browsers.operate("open", request(site="mail-test", user_authorized=True))
        assert reused["session"] == sid and reused["reused"]
        assert reused["status"] == "HUMAN_CONTROL" and "snapshot" not in reused
        result = await browsers.operate("resume", request(sid))
        assert "Authenticated" in str(result["snapshot"])
        assert result["loginVerified"] is False
        await session.page.reload()
        assert "Authenticated" in await session.page.inner_text("body")
        assert (await browsers.operate("open", request(site="mail-test", user_authorized=True)))["session"] == sid
        # Another user receives a separate context with no inherited login cookie.
        monkeypatch.setenv("BROWSER_INTERACTIVE_HEADED", "false")
        other = await browsers.operate("open", SessionRequest(owner="bob", site="mail-test", user_authorized=True))
        assert other["session"] != sid
        other_page = browsers.sessions[other["session"]].page
        await other_page.goto(base + "/account")
        assert "Login required" in await other_page.inner_text("body")
        assert portal["sent"] == 0
    finally:
        await browsers.reap(all_sessions=True)
