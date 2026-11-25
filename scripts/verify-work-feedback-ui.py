"""Check the actual feedback form with fixture APIs, without submitting a task."""
import asyncio
import re
from playwright.async_api import async_playwright


async def main():
    async with async_playwright() as pw:
        browser = await pw.chromium.launch(headless=True)
        try:
            page = await browser.new_page()
            base = 'http://127.0.0.1:5174'
            source = await (await page.request.get(base + '/src/components/AgentWorkOverview.vue')).text()
            vue = re.search(r'from "([^"]*/vue.js[^\"]*)"', source).group(1)
            source = await (await page.request.get(base + '/src/stores/assistant.ts')).text()
            pinia = re.search(r'from "([^"]*pinia[^\"]*)"', source).group(1)
            work = {'id': 'work', 'sessionId': 'session', 'title': '黄金现价查询与邮件通知', 'goal': 'test',
                    'status': 'FAILED', 'stages': [{'id': 'step', 'title': '黄金现价核对与邮件通知',
                    'status': 'FAILED', 'summary': '', 'inputs': [], 'outputs': []}], 'result': {}}
            async def api(route):
                assert route.request.method == 'GET', 'Must not submit a real task'
                url = route.request.url
                await route.fulfill(json=[work] if url.endswith('/works') else
                                    {'definition': None, 'runs': []} if url.endswith('/implementation') else [])
            await page.route(re.compile(base + r'/api/'), api)
            html = '''<html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><body><div id="app"></div><script type="module">
import {createApp,h} from 'VUE'; import {createPinia,setActivePinia} from 'PINIA';
import Panel from '/src/components/AgentWorkOverview.vue'; import {useAssistantStore} from '/src/stores/assistant.ts';
import '/src/style.css'; const p=createPinia(); setActivePinia(p); window.store=useAssistantStore();
createApp({render:()=>h(Panel,{project:{id:'project',name:'验证'},resources:[]})}).use(p).mount('#app');
</script></body></html>'''.replace('VUE', vue).replace('PINIA', pinia)
            await page.route('**/__feedback-check', lambda r: r.fulfill(body=html, content_type='text/html'))
            await page.goto(base + '/__feedback-check')
            await page.get_by_role('button', name='提出修改').click()
            send = page.get_by_role('button', name='发送', exact=True)
            assert await send.is_disabled()
            await page.get_by_role('textbox', name='修改要求').fill('调整邮件标题，保留原有查询和核验逻辑。')
            assert await send.is_enabled()
            for width in (1280, 390):
                await page.set_viewport_size({'width': width, 'height': 844})
                assert await page.evaluate('document.documentElement.scrollWidth <= innerWidth')
                cancel = await page.get_by_role('button', name='取消', exact=True).bounding_box()
                submit = await send.bounding_box()
                assert submit['height'] == 32 and abs(submit['y'] - cancel['y']) < 1
                assert cancel['x'] + cancel['width'] + 7 <= submit['x']
                await page.screenshot(path=f'.run/work-feedback-{width}.png')
            await page.get_by_role('button', name='取消', exact=True).click()
            await page.get_by_role('textbox', name='修改要求').wait_for(state='hidden')
            await page.get_by_role('button', name='提出修改').click()
            assert await page.get_by_role('textbox', name='修改要求').input_value() != ''
            print('PASS: compact aligned actions, empty-input guard, cancel retains draft, desktop/mobile layout')
        finally:
            await browser.close()


asyncio.run(main())
