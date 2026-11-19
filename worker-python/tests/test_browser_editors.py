import pytest

from app.browser_runner.sessions import Browsers, SessionRequest
from test_browser_sessions import portal


@pytest.mark.asyncio
async def test_uncertain_send_allows_sent_navigation_but_not_resending(portal):
    browsers = Browsers()
    try:
        sid = (await browsers.operate('open', SessionRequest(owner='alice', site='mail-test', user_authorized=True)))['session']
        session = browsers.sessions[sid]
        await session.page.set_content('<button type="button">已发送</button><button type="button">发送</button><input aria-label="Body">')
        session.uncertain = True
        def req(name, **kwargs):
            return SessionRequest(owner='alice', session=sid, target={'by':'text','name':name}, **kwargs)
        result = await browsers.operate('act', req('已发送'))
        assert result['actionEffect'] == 'NAVIGATE'
        assert result['resultUncertain']
        with pytest.raises(ValueError, match='结果不确定'):
            await browsers.operate('act', req('发送'))
        session.uncertain = False
        result = await browsers.operate('act', req('发送'))
        assert result['status'] == 'NEEDS_APPROVAL'
        await session.page.set_content('<form><button type="submit">已发送</button></form>')
        result = await browsers.operate('act', req('已发送'))
        assert result['status'] == 'NEEDS_APPROVAL'
    finally:
        await browsers.reap(all_sessions=True)


@pytest.mark.asyncio
async def test_unnamed_editors_frames_readback_and_stale_reference(portal):
    browsers = Browsers()
    def req(session='', **kwargs):
        return SessionRequest(owner='alice', session=session, **kwargs)
    try:
        sid = (await browsers.operate('open', req(site='mail-test', user_authorized=True)))['session']
        page = browsers.sessions[sid].page
        await page.set_content('''<div contenteditable="true" data-placeholder="Body"></div>
            <iframe srcdoc='<body contenteditable="true"></body>'></iframe>''')
        await page.frames[1].wait_for_load_state()
        state = await browsers.operate('observe', req(sid))
        for frame in state['snapshot']:
            editor = frame['editors'][0]
            result = await browsers.operate('act', req(sid, action='fill', frame=frame['frame'], target=editor['target'], value='First line\n第二行'))
            assert result['actionExecuted']
            assert result['actionEffect'] == 'PREPARE'
            assert await page.frames[frame['frame']].locator('[contenteditable]').inner_text() == 'First line\n第二行'
        target = state['snapshot'][0]['editors'][0]['target']
        await page.locator('div[contenteditable]').evaluate('(e) => e.outerHTML = e.outerHTML')
        with pytest.raises(ValueError, match='EDITOR_REFERENCE_EXPIRED'):
            await browsers.operate('act', req(sid, action='fill', target=target, value='Wrong node'))
        await page.set_content('<iframe srcdoc="<body></body>"></iframe>')
        await page.frames[1].wait_for_load_state()
        await page.frames[1].evaluate("document.designMode = 'on'")
        state = await browsers.operate('observe', req(sid))
        editor = state['snapshot'][1]['editors'][0]
        result = await browsers.operate('act', req(sid, action='fill', frame=1, target=editor['target'], value='Design mode body'))
        assert result['actionEffect'] == 'PREPARE'
        assert await page.frames[1].locator('body').inner_text() == 'Design mode body'
    finally:
        await browsers.reap(all_sessions=True)


@pytest.mark.asyncio
async def test_takeover_hides_editors_and_invalidates_refs(portal):
    browsers = Browsers()
    try:
        sid = (await browsers.operate('open', SessionRequest(owner='alice', site='mail-test', user_authorized=True)))['session']
        session = browsers.sessions[sid]
        assert session.editor_refs
        browsers.begin_takeover(session)
        assert not session.editor_refs
        state = await browsers.snapshot(session)
        assert state['status'] == 'HUMAN_CONTROL'
        assert 'snapshot' not in state
        with pytest.raises(ValueError, match='用户正在接管'):
            await browsers.operate('act', SessionRequest(owner='alice', session=sid, action='fill', target={'by':'role','role':'textbox','name':'Body'}, value='No'))
    finally:
        await browsers.reap(all_sessions=True)
