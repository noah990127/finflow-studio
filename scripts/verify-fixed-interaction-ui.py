"""Mock-only direct procedure handoff check. No external actions."""
import asyncio
import re
from playwright.async_api import async_playwright


async def main():
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True)
        page = await browser.new_page()
        base = "http://127.0.0.1:5174"
        module = await (await page.request.get(base + "/src/stores/assistant.ts")).text()
        pinia = re.search(r'from "([^"]*pinia[^"]*)"', module).group(1)
        module = await (await page.request.get(base + "/src/components/WorkExecution.vue")).text()
        vue = re.search(r'from "([^"]*/vue.js[^"]*)"', module).group(1)
        run = {"id": "run-1", "status": "WAITING_INPUT", "stages": [],
               "interaction": {"provider": "test-provider", "stepId": "step-1", "taskId": "task-1",
                               "promptId": 2, "status": "WAITING_INPUT", "canFocus": True,
                               "message": "请在操作窗口完成登录，然后继续。"}}
        actions = []
        async def api(route):
            if "/interaction/" in route.request.url:
                actions.append((route.request.url, route.request.post_data_json))
                if route.request.url.endswith(("/resume", "/approve")):
                    run["status"] = "RUNNING"
                await route.fulfill(status=200, body="")
            else:
                await route.fulfill(json={"definition": None, "runs": [run]})
        await page.route(re.compile(base + r"/api/"), api)
        html = f'''<html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><body><div id="app"></div><script type="module">
import {{createApp,h}} from "{vue}";import {{createPinia}} from "{pinia}";
import Panel from "/src/components/WorkExecution.vue";import "/src/style.css";
createApp({{render:()=>h(Panel,{{projectId:"project-1",resources:[],work:{{id:"work-1",sessionId:"session-1",status:"SUCCEEDED",stages:[]}}}},{{heading:()=>"浏览器自动化验证"}})}}).use(createPinia()).mount("#app");</script></body></html>'''
        await page.route("**/__fixed-check", lambda r: r.fulfill(body=html, content_type="text/html"))
        await page.goto(base + "/__fixed-check")
        button = page.get_by_role("button", name="我已完成，继续", exact=True)
        await button.wait_for()
        for width in (1280, 390):
            await page.set_viewport_size({"width": width, "height": 844})
            assert await button.is_visible()
            assert await page.evaluate("document.documentElement.scrollWidth <= innerWidth")
            await page.screenshot(path=f".run/fixed-interaction-{width}.png")
        await page.get_by_role("button", name="打开操作窗口", exact=True).click()
        await button.click()
        await button.wait_for(state="hidden")
        assert await page.get_by_role("alert").count() == 0
        assert [url.rsplit("/", 1)[-1] for url, _ in actions] == ["focus", "resume"]
        assert all("/runs/run-1/interaction/" in url and body == {"taskId": "task-1", "promptId": 2} for url, body in actions)
        for kind, label, action in [("LOGIN", "我已登录，继续", "resume"),
                                    ("APPROVAL", "确认提交", "approve"),
                                    ("VERIFICATION", "结果正确，完成", "resume")]:
            run['status'] = 'WAITING_INPUT'
            run['interaction']['kind'] = kind
            run['interaction']['promptId'] += 1
            await page.reload()
            button = page.get_by_role('button', name=label, exact=True)
            await button.wait_for()
            await button.click()
            await button.wait_for(state='hidden')
            assert actions[-1][0].endswith('/' + action)
            assert actions[-1][1]['promptId'] == run['interaction']['promptId']
            assert await page.get_by_role('alert').count() == 0
        print("PASS: direct procedure handoff; exact run/task/prompt binding; desktop/mobile")
        await browser.close()


asyncio.run(main())
