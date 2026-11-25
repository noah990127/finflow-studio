"""Conversation regression with fixture APIs; never starts a browser task."""
import asyncio
import re
from playwright.async_api import async_playwright


async def main():
    async with async_playwright() as pw:
        browser = await pw.chromium.launch(headless=True)
        try:
            page = await browser.new_page()
            base = 'http://127.0.0.1:5174'
            source = await (await page.request.get(base + '/src/components/AssistantPanel.vue')).text()
            vue = re.search(r'from "([^"]*/vue.js[^\"]*)"', source).group(1)
            source = await (await page.request.get(base + '/src/stores/assistant.ts')).text()
            pinia = re.search(r'from "([^"]*pinia[^\"]*)"', source).group(1)
            task = {'id': 'old-task', 'stepId': 'old-step', 'conversationTraceIds': ['old-plan', 'old-run'],
                    'status': {'state': 'completed', 'message': {'parts': [{'text': 'Archived browser result'}]}},
                    'procedureState': 'PUBLISHED', 'files': [{'name': 'report.md', 'url': '/fixture-report'}]}
            calls = []
            await page.route(re.compile(base + r'/api/'), lambda r: r.fulfill(json=[]))
            async def tasks(route):
                calls.append(route.request.url)
                await route.fulfill(json=[task] if 'sessionId=fixture' in route.request.url else [])
            await page.route('**/api/browser/agent-tasks?*', tasks)
            html = '''<html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><body><div id="app"></div><script type="module">
import {createApp,h} from 'VUE'; import {createPinia,setActivePinia} from 'PINIA';
import Panel from '/src/components/AssistantPanel.vue';
import {useAssistantStore} from '/src/stores/assistant.ts';
import '/src/style.css'; import '/src/styles/assistant.css';
const p=createPinia();setActivePinia(p);const s=useAssistantStore();window.store=s;
s.open=true;s.sessionId='fixture';s.currentRequest='New question';
s.plan={id:'new-plan',steps:[]};s.run={id:'new-run',status:'SUCCEEDED',result:{}};
s.assistantMessage='New answer';s.history=[
{id:'u1',role:'USER',content:'Old question',traceId:'request-1',createdAt:'2026-11-24T01:00:00Z'},
{id:'a1',role:'ASSISTANT',content:'Task delegated',traceId:'old-run',createdAt:'2026-11-24T01:01:00Z'}];
createApp({render:()=>h(Panel,{project:null})}).use(p).mount('#app');
</script></body></html>'''.replace('VUE', vue).replace('PINIA', pinia)
            await page.route('**/__browser-history-check', lambda r: r.fulfill(body=html, content_type='text/html'))
            await page.goto(base + '/__browser-history-check')
            for reload in (False, True):
                if reload:
                    await page.reload()
                history = page.locator('.assistant-history-group')
                await history.get_by_text('Archived browser result', exact=True).wait_for()
                assert await page.get_by_text('Archived browser result', exact=True).count() == 1
                assert await page.locator('.assistant-agent-turn .browser-task').count() == 0
                assert await history.get_by_role('link', name='report.md').count() == 1
                assert await history.locator('summary', has_text='工序已固化').count() == 1
                assert await page.get_by_text('New answer', exact=True).is_visible()
            start = len(calls)
            await page.wait_for_timeout(6500)
            assert 1 <= len(calls) - start <= 3, 'History rows must not each start polling'
            for width in (1280, 390):
                await page.set_viewport_size({'width': width, 'height': 844})
                await page.screenshot(path=f'.run/browser-history-{width}.png')
            await page.evaluate("window.store.sessionId='other'; window.store.history=[]")
            await page.get_by_text('Archived browser result', exact=True).wait_for(state='hidden')
            print('PASS: historical result/files/publication belong to original turn, survive reload, single poller, session isolation')
        finally:
            await browser.close()


asyncio.run(main())
