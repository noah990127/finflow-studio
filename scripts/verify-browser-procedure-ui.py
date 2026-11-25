"""Read-only UI check: real work data, no Start execution or external submissions."""
import asyncio
import os
import re
from pathlib import Path
from dotenv import dotenv_values
from playwright.async_api import async_playwright


async def main():
    root = Path(__file__).resolve().parents[1]
    config = dotenv_values(root / ".env")
    project = os.environ["VERIFY_PROJECT_ID"]
    session = os.environ["VERIFY_SESSION_ID"]
    async with async_playwright() as playwright:
        browser = await playwright.chromium.launch(headless=True, args=["--use-mock-keychain"])
        context = await browser.new_context()
        response = await context.request.post("http://127.0.0.1:5174/api/auth/login", data={
            "username": config.get("FINFLOW_AUTH_DEFAULT_USERNAME") or "default",
            "password": config.get("FINFLOW_AUTH_DEFAULT_PASSWORD") or "Pr0d1234"})
        assert response.ok
        page = await context.new_page()
        page.on("pageerror", lambda error: print("UI error:", str(error)))
        module = await (await context.request.get("http://127.0.0.1:5174/src/stores/assistant.ts")).text()
        pinia_url = re.search(r'from "([^"]*pinia[^\"]*)"', module).group(1)
        component = await (await context.request.get("http://127.0.0.1:5174/src/components/WorkExecution.vue")).text()
        vue_url = re.search(r'from "([^"]*/vue.js[^\"]*)"', component).group(1)
        html = '''<html><head><meta name="viewport" content="width=device-width, initial-scale=1"><style>body{margin:16px;font-family:Arial,sans-serif}*{box-sizing:border-box}</style></head><body><div id="app"></div><script type="module">
import {createApp,h} from '/node_modules/.vite/deps/vue.js';
import {createPinia} from '/node_modules/.vite/deps/pinia.js';
import WorkExecution from '/src/components/WorkExecution.vue';
const works=await fetch('/api/projects/PROJECT/works').then(r=>r.json());
const work=works.find(w=>w.sessionId==='SESSION');
createApp({render:()=>h(WorkExecution,{work,projectId:'PROJECT',resources:[]})}).use(createPinia()).mount('#app');
</script></body></html>'''.replace("PROJECT", project).replace("SESSION", session).replace('/node_modules/.vite/deps/pinia.js', pinia_url).replace('/node_modules/.vite/deps/vue.js', vue_url)
        await page.route("**/__procedure-ui-check", lambda route: route.fulfill(body=html, content_type="text/html"))
        await page.goto("http://127.0.0.1:5174/__procedure-ui-check")
        await page.get_by_role("button", name="技术执行详情", exact=True).click()
        section = page.locator("details").filter(has=page.locator("summary", has_text="浏览器技术产物")).first
        await section.locator("summary").first.click()
        await page.get_by_role("link", name="下载固定浏览器脚本").wait_for()
        for width, height, name in [(1280, 800, "desktop"), (390, 844, "mobile")]:
            await page.set_viewport_size({"width": width, "height": height})
            assert await page.evaluate("document.documentElement.scrollWidth <= innerWidth")
            await page.screenshot(path=str(root / ".run" / f"browser-procedure-{name}.png"))
        print("Technical artifact visible; script download present; desktop/mobile have no horizontal overflow")
        await browser.close()


asyncio.run(main())
