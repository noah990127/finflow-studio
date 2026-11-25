"""Opt-in real-model journey, restricted to an ephemeral local reporting page."""
import asyncio
import base64
import json
import os
import sys
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'worker-python'))
from dotenv import dotenv_values
from app.browser_agent.runtime import BrowserGoal, BrowserUseRuntime


class Page(BaseHTTPRequestHandler):
    quantity = 12
    def do_GET(self):
        html = f'''<!doctype html><html><meta charset="utf-8"><title>本地库存验证</title>
<h1>库存日报</h1><p>当前库存数量：{self.quantity}</p><p>单位：件</p>
<label>报告内容<textarea id="report" aria-label="报告内容" style="width:700px;height:180px"></textarea></label>
<button onclick="document.querySelector('#receipt').textContent='已保存：'+document.querySelector('#report').value">保存本地报告</button>
<p id="receipt"></p></html>'''
        self.send_response(200); self.send_header('Content-Type','text/html; charset=utf-8'); self.end_headers(); self.wfile.write(html.encode())
    def log_message(self, *args): pass


async def main():
    config = dotenv_values(ROOT / '.env')
    for key, value in config.items():
        if value and (key.startswith('BROWSER_') or key.startswith('DEEPSEEK_')):
            os.environ.setdefault(key, value)
    os.environ.update(ANONYMIZED_TELEMETRY='false', BROWSER_USE_CLOUD_SYNC='false', NO_PROXY='localhost,127.0.0.1,::1')
    server = ThreadingHTTPServer(('127.0.0.1',0), Page)
    threading.Thread(target=server.serve_forever,daemon=True).start()
    spec = BrowserGoal(url=f'http://127.0.0.1:{server.server_port}/', approval_mode='AUTO', allowed_domains=['127.0.0.1'],
        goal='读取当前页面的库存数量和单位，生成简短库存日报，报告抬头为Team。把报告填入报告内容框，点击一次保存本地报告，核验页面已保存内容，并导出Markdown。抬头作为可修改输入，库存必须绑定本轮页面数据，留下可重复执行的固定工序。不访问其他网站。')
    async def progress(message): print(message, flush=True)
    async def human(message, kind='INPUT'):
        if kind == 'APPROVAL': return {'decision':'approved','source':'local_fixture_policy'}
        raise RuntimeError('Local fixture unexpectedly requested human intervention')
    try:
        with tempfile.TemporaryDirectory() as root:
            first = Path(root)/'first'; first.mkdir()
            result = await BrowserUseRuntime().run(spec, first, progress, human)
            bundle = result.get('procedure')
            assert bundle and bundle['version'] == 2, result.get('procedureError')
            params = dict(bundle['parameters'])
            key = next(k for k,v in params.items() if v == 'Team')
            params[key] = 'Operations'
            Page.quantity = 39
            second = Path(root)/'second'; second.mkdir()
            replay = await BrowserUseRuntime().run(spec.model_copy(update={'parameters':params}), second, progress, human, replay=bundle)
            documents = [base64.b64decode(f['bytes']).decode() for f in replay['files'] if f['name'].endswith('.md')]
            assert documents and any('39' in text and 'Operations' in text for text in documents), documents
            report = {'passed':True, 'version':bundle['version'], 'parameterNames':list(params),
                      'firstExecution':'succeeded', 'replay':'succeeded', 'externalSubmissions':0}
            (ROOT/'.run/browser-flow-agent-result.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
            print(json.dumps(report, ensure_ascii=False),flush=True)
    finally:
        server.shutdown()


asyncio.run(main())
