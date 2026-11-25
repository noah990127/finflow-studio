"""Render the real task component against fixtures; no task execution."""
import asyncio
import json
import re
from pathlib import Path
from playwright.async_api import async_playwright

ROOT = Path(__file__).resolve().parents[1]


async def main():
    async with async_playwright() as pw:
        browser = await pw.chromium.launch(headless=True)
        try:
            page = await browser.new_page()
            module = await (await page.request.get('http://127.0.0.1:5174/src/components/BrowserAgentTasks.vue')).text()
            vue = re.search(r'from "([^"]*/vue.js[^\"]*)"', module).group(1)
            task = {'id':'fixture', 'stepId':'first-step', 'status':{'state':'completed', 'message':{'parts':[{'text':'First turn result'}]}}, 'procedureState':'NEEDS_REVIEW',
                    'procedureIssue':'输入字段缺少来源绑定，实际操作已成功，但不能把本次页面数据当作下次固定输入。', 'files':[]}
            await page.route('**/api/browser/agent-tasks?*', lambda route: route.fulfill(json=[task]))
            html = '''<html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{font:14px Arial;margin:16px}*{box-sizing:border-box}</style></head><body><div id="app"></div><script type="module">
import {createApp,h,reactive} from 'VUE';
import Tasks from '/src/components/BrowserAgentTasks.vue';
window.scope=reactive({sessionId:'fixture',stepIds:['first-step']});
window.present=false; window.active=false;
createApp({render:()=>h(Tasks,{...window.scope,onPresent:v=>window.present=v,onActive:v=>window.active=v})}).mount('#app');
</script></body></html>'''.replace('VUE',vue)
            await page.route('**/__procedure-status-check', lambda route: route.fulfill(body=html,content_type='text/html'))
            await page.goto('http://127.0.0.1:5174/__procedure-status-check')
            await page.get_by_text('网页操作已完成', exact=True).wait_for()
            await page.locator('summary', has_text='尚未固化').click()
            await page.get_by_text(task['procedureIssue'], exact=True).wait_for()
            for width in (1280,390):
                await page.set_viewport_size({'width':width,'height':844})
                assert await page.evaluate('document.documentElement.scrollWidth <= innerWidth')
                await page.screenshot(path=str(ROOT / '.run' / f'procedure-status-{width}.png'))
            task.pop('procedureIssue'); task['procedureState']='PUBLISHED'
            await page.reload()
            await page.locator('summary',has_text='工序已固化').wait_for()
            await page.evaluate("window.scope.stepIds=[]")
            await page.get_by_text('First turn result',exact=True).wait_for(state='hidden')
            assert not await page.evaluate('window.present')
            await page.evaluate("window.scope.stepIds=['second-step']")
            assert await page.locator('.browser-task').count() == 0
            task['stepId']='second-step'
            task['status']['message']['parts'][0]['text']='Second turn result'
            await page.get_by_text('Second turn result',exact=True).wait_for(timeout=10000)
            assert await page.get_by_text('First turn result',exact=True).count() == 0
            task['status']['state']='input-required'
            await page.get_by_role('button',name='我已完成，继续',exact=True).wait_for(timeout=10000)
            await page.evaluate("window.scope.stepIds=['third-step']")
            assert await page.get_by_role('button',name='我已完成，继续',exact=True).is_visible()
            assert await page.evaluate('window.active')
            task['status']['state']='completed'
            await page.locator('.browser-task').wait_for(state='hidden',timeout=10000)
            assert not await page.evaluate('window.present || window.active')
            print('PASS: previous answers hidden on next turn; current result and pending handoff retained')
            print('PASS: execution/publication states separate; desktop/mobile do not overflow')
        finally:
            await browser.close()


asyncio.run(main())
