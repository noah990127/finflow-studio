"""Opt-in live subject-only diagnosis. Never populate recipients or submit mail."""
import asyncio
import json
import os
import sys
from pathlib import Path
from dotenv import dotenv_values

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "worker-python"))
os.environ.update(ANONYMIZED_TELEMETRY="false", BROWSER_USE_CLOUD_SYNC="false", NO_PROXY="localhost,127.0.0.1,::1", no_proxy="localhost,127.0.0.1,::1")
from browser_use import Browser, Tools
from browser_use.browser.events import NavigateToUrlEvent
sys.path.append(str(ROOT / "worker-python/.venv312/lib/python3.12/site-packages"))
from playwright.async_api import async_playwright


async def main():
    config = dotenv_values(ROOT / ".env")
    profile = ROOT / ".local-runtime/browser-agent/profiles" / sys.argv[1]
    if not profile.is_dir() or profile.parent != ROOT / ".local-runtime/browser-agent/profiles":
        raise ValueError("Existing managed profile required")
    browser = Browser(headless=False, keep_alive=True, user_data_dir=str(profile), enable_default_extensions=False,
        executable_path=config.get("BROWSER_EXECUTABLE_PATH") or "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
        args=["--use-mock-keychain"], allowed_domains=["wx.mail.qq.com", "mail.qq.com", "xui.ptlogin2.qq.com", "ssl.ptlogin2.qq.com"])
    report = []
    await browser.start()
    try:
        event = browser.event_bus.dispatch(NavigateToUrlEvent(url="https://wx.mail.qq.com/"))
        await event
        await event.event_result(raise_if_any=True, raise_if_none=False)
        async with async_playwright() as pw:
            connection = await pw.chromium.connect_over_cdp(browser.cdp_url)
            page = next(p for c in connection.contexts for p in c.pages if "mail.qq.com" in p.url)
            compose = page.get_by_text("写信", exact=True)
            await compose.first.wait_for(timeout=30000)
            await compose.first.click()
            subject = page.locator('input[aria-label="主题"]')
            await subject.wait_for(timeout=15000)
            if await subject.input_value():
                raise RuntimeError("Nonempty subject: refusing to modify an existing draft")
            await page.evaluate('''() => {
                window.__subjectEvents=[];
                const target=document.querySelector('input[aria-label="主题"]');
                for(const type of ['focus','blur','input','change','keydown','keyup','compositionstart','compositionend'])
                    target.addEventListener(type,e=>window.__subjectEvents.push({type:e.type,trusted:e.isTrusted,
                        value:target.value,data:e.data??null,key:e.key??null,time:performance.now()}),true);
            }''')
            async def snapshot(label):
                value=await subject.input_value()
                focus=await page.evaluate("document.activeElement?.getAttribute('aria-label')")
                result={"stage":label,"value":value,"focus":focus}
                report.append(result)
                print(json.dumps(result,ensure_ascii=False),flush=True)
            async def checkpoints(label):
                await snapshot(label+":immediate")
                await page.wait_for_timeout(1000)
                await snapshot(label+":after1s")
                await subject.press('Tab')
                await page.wait_for_timeout(1000)
                await snapshot(label+":afterRealBlur")
                await page.wait_for_timeout(2000)
                await snapshot(label+":after3s")
            try:
                state=await browser.get_browser_state_summary(include_screenshot=False)
                index=next(i for i,n in state.dom_state.selector_map.items() if n.attributes.get('aria-label')=='主题')
                tools=Tools()
                model=tools.registry.create_action_model()
                result=await tools.act(model(input={"index":index,"text":"主题诊断-仅测试不发送-20261124","clear":True}),browser_session=browser)
                report.append({"nativeResult":result.extracted_content,"error":result.error})
                await checkpoints('browser-use')
                await subject.fill('')
                await subject.fill('主题诊断-仅测试不发送-20261124')
                await checkpoints('playwright-fill')
                await subject.fill('')
                await subject.press_sequentially('主题诊断-仅测试不发送-20261124',delay=60)
                await checkpoints('keyboard')
                from browser_use.browser.watchdogs.default_action_watchdog import DefaultActionWatchdog
                original_events = DefaultActionWatchdog._trigger_framework_events
                async def no_extra_events(self, **kwargs):
                    pass
                for clear, extra in [(False,True),(True,False),(False,False)]:
                    await subject.fill('')
                    await subject.press('Tab')
                    await page.wait_for_timeout(500)
                    state=await browser.get_browser_state_summary(include_screenshot=False)
                    index=next(i for i,n in state.dom_state.selector_map.items() if n.attributes.get('aria-label')=='主题')
                    try:
                        if not extra:
                            DefaultActionWatchdog._trigger_framework_events=no_extra_events
                        result=await tools.act(model(input={"index":index,"text":"主题诊断-仅测试不发送-20261124","clear":clear}),browser_session=browser)
                        report.append({"nativeResult":result.extracted_content,"error":result.error})
                        await checkpoints(f'native-clear{clear}-extra{extra}')
                    finally:
                        DefaultActionWatchdog._trigger_framework_events=original_events
                for event_type in ['input','change','blur']:
                    await subject.fill('')
                    await subject.press('Tab')
                    await page.wait_for_timeout(500)
                    state=await browser.get_browser_state_summary(include_screenshot=False)
                    index=next(i for i,n in state.dom_state.selector_map.items() if n.attributes.get('aria-label')=='主题')
                    async def one_event(self, object_id, cdp_session, selected=event_type):
                        script="""function(kind) {
                            this.focus();
                            const event = kind==='input'
                              ? new InputEvent('input',{bubbles:true,cancelable:true,data:this.value,inputType:'insertText'})
                              : new Event(kind,{bubbles:true,cancelable:true});
                            this.dispatchEvent(event);
                        }"""
                        await cdp_session.cdp_client.send.Runtime.callFunctionOn(params={
                            'objectId':object_id,'functionDeclaration':script,'arguments':[{'value':selected}]},session_id=cdp_session.session_id)
                    try:
                        DefaultActionWatchdog._trigger_framework_events=one_event
                        await tools.act(model(input={"index":index,"text":"主题诊断-仅测试不发送-20261124","clear":True}),browser_session=browser)
                        await checkpoints('only-synthetic-'+event_type)
                    finally:
                        DefaultActionWatchdog._trigger_framework_events=original_events
            finally:
                await subject.fill('')
                await subject.press('Tab')
                await page.wait_for_timeout(1000)
                await snapshot('restored-empty')
                report.append({"events":await page.evaluate('window.__subjectEvents')})
                (ROOT / '.run/subject-diagnosis.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    finally:
        await browser.kill()


asyncio.run(main())
