"""Mock-only conversation layout check; never executes a browser task."""
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
        module = await (await page.request.get(base + "/src/components/AssistantPanel.vue")).text()
        vue = re.search(r'from "([^"]*/vue.js[^"]*)"', module).group(1)
        task = {"id": "test", "status": {"state": "input-required", "message": {"parts": [{"text": "请在网站完成登录，然后继续。"}]}}, "metadata": {"promptId": 1, "canFocus": True}}
        await page.route(re.compile(r"http://127.0.0.1:5174/api/"), lambda r: r.fulfill(json=[]))
        await page.route("**/api/browser/agent-tasks?*", lambda r: r.fulfill(json=[task]))
        canceled = []
        async def cancel(route):
            canceled.append(route.request.url)
            task['status']['state'] = 'canceled'
            await route.fulfill(json=task)
        await page.route("**/api/browser/agent-tasks/test/cancel", cancel)
        html = f'''<html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><body><div id="app"></div><script type="module">
import {{createApp,h}} from "{vue}";import {{createPinia,setActivePinia}} from "{pinia}";
import Panel from "/src/components/AssistantPanel.vue";import {{useAssistantStore}} from "/src/stores/assistant.ts";
import "/src/style.css";import "/src/styles/assistant.css";
const pinia=createPinia();setActivePinia(pinia);const s=useAssistantStore();s.open=true;s.sessionId="test";s.currentRequest="登录网站并核对结果";s.run={{status:"SUCCEEDED",result:{{workStatus:"DELEGATED"}}}};
createApp({{render:()=>h(Panel,{{project:null}})}}).use(pinia).mount("#app");</script></body></html>'''
        await page.route("**/__conversation-check", lambda r: r.fulfill(body=html, content_type="text/html"))
        await page.goto(base + "/__conversation-check")
        button = page.get_by_role("button", name="我已完成，继续", exact=True)
        await button.wait_for()
        await page.get_by_role("button", name="打开操作窗口", exact=True).wait_for()
        assert await page.locator(".assistant-conversation .browser-tasks").count() == 1
        assert await page.locator(".assistant-composer .browser-tasks").count() == 0
        for width in (1280, 390):
            await page.set_viewport_size({"width": width, "height": 844})
            await button.scroll_into_view_if_needed()
            assert (await button.bounding_box())["height"] <= 30
            await page.screenshot(path=f".run/conversation-browser-{width}.png")
        assert await page.get_by_role('button', name='中止浏览器任务', exact=True).count() == 0
        async with page.expect_response('**/api/browser/agent-tasks/test/cancel'):
            await page.get_by_role('button', name='中断当前任务', exact=True).click()
        await page.get_by_role('button', name='正在中断', exact=True).wait_for(state='hidden')
        assert len(canceled) == 1
        print("PASS: main conversation placement; desktop/mobile handoff controls")
        await page.evaluate('''async () => {
          const {useAssistantStore}=await import('/src/stores/assistant.ts');
          const s=useAssistantStore();s.interrupted=false;s.busy=false;s.streaming=false;
          s.executionMode='AUTO';s.activeExecutionMode='AUTO';
          s.run={id:'domain-check',status:'WAITING_CONFIRMATION'};
          s.plan={id:'domain-plan',planHash:'domain-hash',status:'WAITING_CONFIRMATION',steps:[
            {id:'domain-step',tool:'browser.delegate',title:'授权网站访问',description:'允许访问：mail.example.com',
             requiresConfirmation:true,status:'PENDING',arguments:{url:'https://mail.example.com'}}]};
        }''')
        await page.get_by_role('button', name='允许访问', exact=True).wait_for()
        scope = page.get_by_role('region', name='待确认操作')
        assert 'mail.example.com' in await scope.inner_text()
        assert await scope.get_by_role('checkbox').count() == 0
        for width in (1280,390):
            await page.set_viewport_size({'width':width,'height':844})
            button=page.get_by_role('button',name='允许访问',exact=True)
            await button.scroll_into_view_if_needed()
            box=await button.bounding_box()
            assert box and box['x']>=0 and box['x']+box['width']<=width
            await page.screenshot(path=f'.run/domain-approval-{width}.png')
        print('PASS: AUTO still shows account-domain consent, desktop/mobile')
        await browser.close()


asyncio.run(main())
