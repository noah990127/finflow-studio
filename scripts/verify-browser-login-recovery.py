"""Native browser-use replay against a loopback login fixture; no model or external writes."""
import asyncio
import os
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parents[1]
CONFIG = {**dotenv_values(ROOT / "worker-python/.env"), **dotenv_values(ROOT / ".env")}
sys.path.insert(0, str(ROOT / "worker-python"))
os.environ["ANONYMIZED_TELEMETRY"] = "false"
os.environ["BROWSER_USE_CLOUD_SYNC"] = "false"
os.environ["NO_PROXY"] = os.environ["no_proxy"] = "localhost,127.0.0.1,::1"
from browser_use import Agent, Browser, ChatOpenAI
from browser_use.agent.views import AgentHistory, AgentOutput
from browser_use.browser.views import BrowserStateHistory
from browser_use.browser.events import NavigateToUrlEvent
from app.browser_agent.recovery import RecoveryReplayMixin, login_required

submissions = []


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        if self.path == "/auth":
            self.send_response(302)
            self.send_header("Set-Cookie", "session=fixture; Max-Age=3600; Path=/")
            self.send_header("Location", "/work")
            self.end_headers()
            return
        if self.path == "/expire":
            self.send_response(302)
            self.send_header("Set-Cookie", "session=; Max-Age=0; Path=/")
            self.send_header("Location", "/login")
            self.end_headers()
            return
        logged = "session=fixture" in self.headers.get("Cookie", "")
        if not logged and self.path != "/login":
            self.send_response(302)
            self.send_header("Location", "/login")
            self.end_headers()
            return
        if self.path.startswith("/commit"):
            submissions.append(self.path)
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.end_headers()
        self.wfile.write(("<input type=password><a href='/auth'>Login</a>" if not logged else
            "<h1>Fixture</h1><a id='submit' href='/commit'>Submit once</a>").encode())


async def navigate(browser, url):
    event = browser.event_bus.dispatch(NavigateToUrlEvent(url=url))
    await event
    await event.event_result(raise_if_any=True, raise_if_none=False)


async def main():
    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{server.server_port}"
    with tempfile.TemporaryDirectory(prefix="browser-use-user-data-dir-") as directory:
        def make_browser():
            return Browser(headless=True, user_data_dir=directory, enable_default_extensions=False,
                           executable_path=CONFIG.get("BROWSER_EXECUTABLE_PATH") or "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                           args=["--use-mock-keychain"], allowed_domains=["127.0.0.1"])
        browser = make_browser()
        await browser.start()
        try:
            await navigate(browser, base + "/auth")
        finally:
            await browser.kill()
        browser = make_browser()
        await browser.start()
        try:
            await navigate(browser, base + "/work")
            state = await browser.get_browser_state_summary(include_screenshot=False)
            assert not login_required(state), "Persistent login did not survive browser restart"

            class Replay(RecoveryReplayMixin, Agent):
                prompts = 0
                completed = 0

                async def recovery_human(self, message):
                    self.prompts += 1
                    # First Continue does not log in: guard must wait again.
                    if self.prompts > 1:
                        await navigate(browser, base + "/auth")

                def recovery_checkpoint(self, status):
                    if status == "completed":
                        self.completed += 1

            agent = Replay(task="Local fixture", browser=browser, llm=ChatOpenAI(model="gpt-4o-mini", api_key="unused"),
                           directly_open_url=False, file_system_path=directory + "/files")
            index = next(i for i, e in state.dom_state.selector_map.items() if e.attributes.get("id") == "submit")
            output = AgentOutput.model_construct(action=[agent.ActionModel(click={"index": index})])
            history = AgentHistory(model_output=output, result=[], state=BrowserStateHistory(
                url=state.url, title=state.title, tabs=[], interacted_element=AgentHistory.get_interacted_element(output, state.dom_state.selector_map)))
            await agent._execute_history_step(history, 0)
            assert len(submissions) == 1
            await navigate(browser, base + "/expire")
            await agent._execute_history_step(history, 0)
            assert len(submissions) == 2, "A completed action was repeated"
            assert agent.prompts == 2 and agent.completed == 2
            print("PASS: persistent login, expired-session pause, premature Continue rejected, native replay resumes without repeating prior action")
        finally:
            await browser.kill()
            server.shutdown()


asyncio.run(main())
