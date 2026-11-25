import asyncio
import os
import sys
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.browser_agent import input_compat as compat
from app.browser_agent.interaction import BrowserTaskFailure, unsuccessful_result


def test_installed_sdk_contract():
    from browser_use.browser.watchdogs.default_action_watchdog import DefaultActionWatchdog
    script = compat.framework_script(DefaultActionWatchdog._trigger_framework_events)
    assert "type: 'blur'" not in script
    assert "type: 'input'" in script and "type: 'change'" in script


def test_unknown_method_rejected():
    with pytest.raises(RuntimeError):
        compat.framework_script(test_unknown_method_rejected)


def test_terminal_failure_ignores_recovered_parser_errors():
    assert unsuccessful_result(["validation error", None], terminal_done=True).code == 'OUTCOME_NOT_VERIFIED'
    assert unsuccessful_result(["validation error", None]).code == 'OUTCOME_NOT_VERIFIED'
    assert unsuccessful_result([None, "validation error"]).code == 'MODEL_OUTPUT_INVALID'


def test_lost_input_stops_without_exposing_values():
    async def run():
        token = compat.begin_checks()
        session = SimpleNamespace(session_id='test', cdp_client=SimpleNamespace(send=SimpleNamespace(
            Runtime=SimpleNamespace(callFunctionOn=AsyncMock(return_value={'result': {'value': True}})))))
        try:
            compat._checks.get().append((session, 'node'))
            with pytest.raises(BrowserTaskFailure) as error:
                await compat.verify_inputs()
            assert error.value.code == 'INPUT_VALUE_LOST'
        finally:
            compat.end_checks(token)
    asyncio.run(run())


def test_install_is_guarded_and_preserves_native_input(monkeypatch):
    from browser_use.browser.watchdogs.default_action_watchdog import DefaultActionWatchdog
    original_event = DefaultActionWatchdog._trigger_framework_events
    original_input = DefaultActionWatchdog._input_text_element_node_impl
    monkeypatch.setattr(compat, '_installed', False)
    monkeypatch.setattr(compat, 'version', lambda name: 'unsupported')
    with pytest.raises(RuntimeError, match='Unsupported'):
        compat.install()
    monkeypatch.setattr(compat, 'version', lambda name: '0.13.14')
    monkeypatch.setattr(DefaultActionWatchdog, '_trigger_framework_events', original_event)
    monkeypatch.setattr(DefaultActionWatchdog, '_input_text_element_node_impl', original_input)
    compat.install()
    patched = DefaultActionWatchdog._trigger_framework_events
    compat.install()
    assert DefaultActionWatchdog._trigger_framework_events is patched

    async def run():
        token = compat.begin_checks()
        call = AsyncMock(return_value={'result': {'value': True}})
        session = SimpleNamespace(session_id='test', cdp_client=SimpleNamespace(
            send=SimpleNamespace(Runtime=SimpleNamespace(callFunctionOn=call))))
        try:
            await patched(None, 'node', session)
            assert call.await_count == 2
            assert len(compat._checks.get()) == 1
            assert "type: 'blur'" not in call.call_args_list[0].kwargs['params']['functionDeclaration']
        finally:
            compat.end_checks(token)
    asyncio.run(run())


def test_real_browser_events():
    # Local deterministic page only: no account, network navigation or submission.
    import browser_use
    sys.path.append(str(Path(__file__).resolve().parents[1] / '.venv312/lib/python3.12/site-packages'))
    from playwright.async_api import async_playwright
    from dotenv import dotenv_values
    from browser_use.browser.watchdogs.default_action_watchdog import DefaultActionWatchdog
    script = compat.framework_script(DefaultActionWatchdog._trigger_framework_events)

    async def run():
        async with async_playwright() as pw:
            executable = os.environ.get('BROWSER_EXECUTABLE_PATH') or dotenv_values(
                Path(__file__).resolve().parents[2] / '.env').get('BROWSER_EXECUTABLE_PATH')
            browser = await pw.chromium.launch(headless=True, executable_path=executable)
            try:
                page = await browser.new_page()
                await page.set_content('''<input id="subject"><button>Next</button><input type="password">
                    <script>
                    const field=document.querySelector('#subject'); let invalid=false;
                    field.addEventListener('blur',e=>{
                        if(!e.isTrusted) invalid=true;
                        else if(invalid) field.value='';
                    });
                    </script>''')
                field = page.locator('#subject')
                await field.fill('Subject')
                await field.evaluate('(el, fn) => (' + script + ').call(el)')
                assert await field.evaluate('(el) => (' + compat.WATCH + ').call(el)')
                await field.press('Tab')
                await page.wait_for_timeout(50)
                assert await field.input_value() == 'Subject'
                assert not await field.evaluate('(el) => (' + compat.CHECK + ').call(el)')
                assert not await page.locator('[type=password]').evaluate('(el) => (' + compat.WATCH + ').call(el)')
                await field.fill('Another subject')
                await field.evaluate('(el) => (' + compat.WATCH + ').call(el)')
                await field.evaluate("el => el.dispatchEvent(new Event('blur'))")
                await field.press('Tab')
                await page.wait_for_timeout(50)
                assert await field.input_value() == ''
                assert await field.evaluate('(el) => (' + compat.CHECK + ').call(el)')
            finally:
                await browser.close()
    asyncio.run(run())
