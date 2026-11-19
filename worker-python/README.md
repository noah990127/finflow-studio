# FinFlow Python Worker

Python 3.12 / FastAPI，提供 Agent CLI 桥接、搜索、资料解析、数据处理和文档输出。浏览器执行器独立运行。

```bash
python3.12 -m venv .venv312
.venv312/bin/pip install -e '.[dev]'
.venv312/bin/python -m playwright install chromium
.venv312/bin/python -m uvicorn app.main:app --host 127.0.0.1 --port 8001
```

手动启动先注入环境配置；完整开发推荐 `../scripts/start-local.sh`。

## 模块与配置

- `app/agent/codex_runtime.py` / `opencode_runtime.py`：`AGENT_RUNTIME=codex-cli` 或 `opencode`；路径使用 `CODEX_CLI_PATH` / `OPENCODE_CLI_PATH`。
- `app/llm.py`：工具内部模型适配，与任务运行时开关分开配置。
- `app/integrations/search_provider.py` / `research.py`：搜索、网页读取和来源资料。服务不可用和空结果要明确报错。
- `app/fixed_work.py` / `transform_plan.py`：固定执行与数据处理。
- `app/semantic_presentation.py` / `presentation_templates.py`：内容协议和模板渲染，见 [PPT 文档](app/ppt_skills/README.md)。
- `app/browser_runner/`：独立执行器，见 [浏览器说明](app/browser_runner/README.md)。

`opencode.deepseek.json` 是无密钥的提供方配置样例，不保证目标环境提供该模型；按批准的模型与 CLI 配置调整，密钥安全注入。

## 测试

```bash
.venv312/bin/python -m pytest -q
.venv312/bin/python -m pytest tests/test_browser_editors.py tests/test_browser_sessions.py tests/test_browser_runner.py tests/test_browser_diagnostics.py -q
```

演示文稿测试可能依赖 Chromium、LibreOffice、Poppler 和 CJK 字体。离线部署提前准备批准的包和系统依赖，不能依赖运行时联网下载。生成文件与截图放入忽略目录 `artifacts/` 或 `.run/`。
