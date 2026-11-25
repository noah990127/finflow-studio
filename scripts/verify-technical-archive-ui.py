"""Exercise archive links and execution details without dispatching business tools."""
import asyncio
import re
from pathlib import Path
from playwright.async_api import async_playwright


async def main():
    async with async_playwright() as pw:
        browser = await pw.chromium.launch(headless=True)
        try:
            page = await browser.new_page()
            base = 'http://127.0.0.1:5174'
            source = await (await page.request.get(base + '/src/components/WorkExecution.vue')).text()
            vue = re.search(r'from "([^"]*/vue.js[^\"]*)"', source).group(1)
            source = await (await page.request.get(base + '/src/stores/assistant.ts')).text()
            pinia = re.search(r'from "([^"]*pinia[^\"]*)"', source).group(1)
            artifact = {'part': '@manifest', 'resourceId': 'manifest', 'version': 1, 'hash': 'sha256:fixed'}
            state = {'definition': {'id': 'definition', 'version': 1, 'hash': 'sha256:fixed', 'verified': True,
                      'implementation': {'title': '固定执行', 'steps': [], 'modelSnapshot': {}}},
                     'artifacts': [artifact], 'runs': [{'id': 'run-1', 'definitionId': 'definition',
                      'createdAt': '2026-11-25T01:00:00Z', 'status': 'SUCCEEDED', 'stages': [], 'error': ''}]}
            requests = []
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))

            async def api(route):
                assert route.request.method == 'GET', 'Fixture must not perform business operations'
                url = route.request.url
                requests.append(url)
                if url.endswith('/implementation'):
                    await route.fulfill(json=state)
                elif url.endswith('/runs/run-1/details'):
                    await route.fulfill(json={'steps': [{'step_id': 'fetch', 'status': 'SUCCEEDED', 'code_hash': 'sha256:fixed'}],
                                              'artifacts': [{**artifact, 'resourceId': 'journal'}], 'implementationArtifacts': [artifact]})
                else:
                    await route.fulfill(json=[])

            await page.route(re.compile(base + r'/api/'), api)
            html = '''<html><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><body><div id="app"></div><script type="module">
import {createApp,h} from 'VUE'; import {createPinia} from 'PINIA';
import Panel from '/src/components/WorkExecution.vue'; import '/src/style.css';
const work={id:'agent-run',sessionId:'session',status:'SUCCEEDED',stages:[],result:{},goal:'test'};
createApp({render:()=>h(Panel,{work,projectId:'project',resources:[]})}).use(createPinia()).mount('#app');
</script></body></html>'''.replace('VUE', vue).replace('PINIA', pinia)
            await page.route('**/__technical-archive-check', lambda route: route.fulfill(body=html, content_type='text/html'))
            await page.goto(base + '/__technical-archive-check')
            assert await page.get_by_text('已归档技术资源', exact=True).count() == 0
            await page.get_by_role('button', name='技术执行详情').click()
            await page.locator('summary', has_text='已归档技术资源').click()
            link = page.get_by_role('link', name='实现清单 · 资源版本 1')
            assert await link.get_attribute('href') == '/api/files/manifest/download?version=1'
            await page.locator('summary', has_text='run-1').click()
            assert not any('/runs/run-1/details' in url for url in requests)
            await page.get_by_role('button', name='查看步骤执行记录').click()
            await page.get_by_role('link', name='下载执行记录').wait_for()
            for width in (1280, 390):
                await page.set_viewport_size({'width': width, 'height': 844})
                assert await page.evaluate('document.documentElement.scrollWidth <= innerWidth')
                await page.screenshot(path=str(Path('.run') / f'technical-archive-{width}.png'))
            assert not errors, errors
            print('PASS: archives hidden by default, pinned download URLs, on-demand journal, desktop/mobile layout')
        finally:
            await browser.close()


asyncio.run(main())
