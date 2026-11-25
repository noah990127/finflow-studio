"""Local native browser-use journey: observe -> freeze -> changed input -> replay.

No account, external website, real submission or model service is used.
The extraction model is deterministic; browser interactions and matching are real.
"""
import asyncio
import base64
import json
import os
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'worker-python'))
os.environ.update(ANONYMIZED_TELEMETRY='false', BROWSER_USE_CLOUD_SYNC='false', NO_PROXY='localhost,127.0.0.1,::1')
from dotenv import dotenv_values
from browser_use import Browser, Tools, Agent, ChatOpenAI
from browser_use.agent.views import AgentHistoryList, AgentOutput
from browser_use.dom.views import DOMInteractedElement
from app.browser_agent.flow_tools import register, StructuredPage
from app.browser_agent.procedure import compile_procedure, bind_procedure
from app.browser_agent.input_compat import install, begin_checks, end_checks, verify_inputs
from app.browser_agent.recovery import RecoveryReplayMixin


class Page(BaseHTTPRequestHandler):
    def do_GET(self):
        html = '<!doctype html><title>Local workflow fixture</title><h1>Operations report</h1><label>Report<textarea id="report" aria-label="Report"></textarea></label><button id="save" onclick="document.querySelector(\'#receipt\').textContent=document.querySelector(\'#report\').value">Save locally</button><output id="receipt"></output>'
        self.send_response(200); self.send_header('Content-Type', 'text/html; charset=utf-8'); self.end_headers(); self.wfile.write(html.encode())
    def log_message(self, *args): pass


class ReplayAgent(RecoveryReplayMixin, Agent):
    async def recovery_human(self, message): raise AssertionError(message)
    def recovery_checkpoint(self, status): pass


async def main():
    server = ThreadingHTTPServer(('127.0.0.1', 0), Page)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    url = f'http://127.0.0.1:{server.server_port}/'
    config = dotenv_values(ROOT / '.env')
    install()
    token = begin_checks()
    summaries = []
    try:
        with tempfile.TemporaryDirectory() as directory:
            browser = Browser(headless=True, enable_default_extensions=False,
                              executable_path=config.get('BROWSER_EXECUTABLE_PATH'),
                              user_data_dir=directory, allowed_domains=['127.0.0.1'], args=['--use-mock-keychain'])
            await browser.start()
            try:
                for iteration, (amount, audience) in enumerate([(12, 'Team'), (39, 'Operations')]):
                    tools = Tools()
                    files = []
                    extraction = SimpleNamespace(ainvoke=AsyncMock(return_value=SimpleNamespace(completion=StructuredPage(
                        json_value=json.dumps({'amount':amount, 'unit':'units'})))))
                    register(tools, browser, extraction, AsyncMock(return_value={'decision':'approved'}), files, 'AUTO')
                    model = tools.registry.create_action_model()
                    await tools.act(model(navigate={'url':url}), browser_session=browser)
                    state = await browser.get_browser_state_summary(include_screenshot=False)
                    indices = {n.attributes.get('id'): i for i,n in state.dom_state.selector_map.items()}
                    if iteration == 0:
                        actions = [
                            {'workflow_input':{'name':'audience','value':audience}},
                            {'workflow_extract':{'name':'record','instruction':'Read amount and unit', 'output_schema_json':json.dumps({
                                'type':'object','properties':{'amount':{'type':'number'},'unit':{'type':'string'}},'required':['amount','unit']})}},
                            {'workflow_render':{'name':'report','template':'${who}: ${n} ${u}', 'bindings_json':json.dumps({
                                'who':{'source':'audience'},'n':{'source':'record','path':['amount']},'u':{'source':'record','path':['unit']}})}},
                            {'workflow_fill':{'index':indices['report'],'binding_json':'{"source":"report"}','clear':True}},
                            {'workflow_click':{'index':indices['save'],'effect':'write','operation':'save_record'}},
                            {'workflow_artifact':{'name':'report','format':'md','binding_json':'{"source":"report"}'}}]
                        history = {'history':[]}
                        for action in actions:
                            state = await browser.get_browser_state_summary(include_screenshot=False)
                            model_action = model(**action)
                            node = state.dom_state.selector_map.get(model_action.get_index())
                            element = DOMInteractedElement.load_from_enhanced_dom_tree(node).to_dict() if node else None
                            result = await tools.act(model_action, browser_session=browser)
                            assert not result.error, result.error
                            await verify_inputs()
                            record = result.model_dump(mode='json')
                            record['metadata'] = {'executedAction':action}
                            history['history'].append({'model_output':{'action':[action]},'state':{'url':url,'title':'fixture',
                                'tabs':[],'interacted_element':[element]},'result':[record]})
                        history['history'].append({'model_output':{'action':[{'done':{'success':True}}]},'state':{},'result':[{'success':True}]})
                        bundle = compile_procedure(history, SimpleNamespace(url=url,goal='Fill and save a local operations report',allowed_domains=['127.0.0.1'],approval_mode='AUTO'))
                    else:
                        agent = ReplayAgent(task='Replay fixture', browser=browser, tools=tools,
                            llm=ChatOpenAI(model='test', api_key='not-used'), use_vision=False,
                            file_system_path=str(Path(directory) / 'agent-files'), use_judge=False)
                        output_model = AgentOutput.type_with_custom_actions(model)
                        replay = AgentHistoryList.load_from_dict(bind_procedure(bundle, {'audience':audience}), output_model)
                        for item in replay.history:
                            await agent._execute_history_step(item, 0)
                            await verify_inputs()
                    assert len(files) == 1
                    text = base64.b64decode(files[0]['bytes']).decode()
                    assert text == f'{audience}: {amount} units', text
                    page = await browser.get_current_page()
                    receipt = await page.evaluate('() => document.querySelector("#receipt").textContent')
                    assert receipt == text, receipt
                    summaries.append({'run':iteration+1, 'artifact':text, 'receipt':receipt})
            finally:
                await browser.kill()
    finally:
        end_checks(token)
        server.shutdown()
    print(json.dumps({'passed':True,'scenarios':summaries,'externalSubmissions':0}, ensure_ascii=False))


asyncio.run(main())
