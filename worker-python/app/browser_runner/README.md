# Browser Runner

独立 Playwright 执行进程，默认端口 `8002`。通用交互使用结构化观察与受控动作；可选 browser-use 探索不能绕过主服务授权。

## 本地启用

```dotenv
BROWSER_RUNNER_ENABLED=true
BROWSER_RUNNER_URL=http://127.0.0.1:8002
BROWSER_RUNNER_TOKEN=<至少32字符的随机服务令牌>
BROWSER_INTERACTIVE_HEADED=true
```

用 `openssl rand -hex 32` 生成令牌，通过 `.env` 或密钥系统注入调用方，不能提交生成值。服务认证、用户授权与网站登录是三种独立机制。

```bash
# 从 worker-python 目录，注入环境配置后运行
.venv312/bin/python -m uvicorn app.browser_runner.app:app --host 127.0.0.1 --port 8002
```

## 交互与会话

首次人工登录先进入接管状态再加载网站，期间 Agent 不读取或操作页面。用户完成后点击当前对话输入框上方的“我已完成，继续执行”；平台交还控制，再由 Agent 核验页面并继续任务。

会话按用户隔离，同源连接优先复用。会话保存在内存，重启不保留登录；有未核验提交时先核验再重启。人工操作窗口超时不代表自动交还。共享或远程部署需另建隔离的人工操作通道；当前不包含定时调度。

## 编辑器

`sessions.py` 输出框架快照与 `editors` 列表。`editors.py` 分配会话内编号，支持普通输入、无标签富文本、iframe 和 designMode；按 `target={by:"ref",name:编号}` 加对应 `frame` 填写并回读验证。

编号绑定实际 DOM 节点。节点替换或人工接管后重新观察，不能猜编号或把临时编号写入固定脚本。密码和文件上传由用户处理。

## 安全与诊断

- 网站正常联网、自动保存不等于获得发送许可。
- `interaction_policy.py` 区分观察、准备、导航与对外操作，未知动作仍确认。
- “已发送”不因含有“发送”而误判；真实表单提交仍检查。
- 结果不明确时允许观察和安全导航以核验，但禁止修改或重复提交。
- 用户授权不绕过私网隔离，不能公开服务令牌或调试端口。
- `HUMAN_CONTROL`：等待输入框上方按钮交还，不要求输入聊天消息。
- `EDITOR_REFERENCE_EXPIRED` / `TARGET_NOT_UNIQUE`：重新观察，使用明确的编辑器编号。
- `DESTINATION_RESTRICTED`：查看被阻断来源，不直接关闭整个网络策略。
- 浏览器启动失败：检查 Playwright 对应 Chromium、可见窗口环境与 `BROWSER_EXECUTABLE_PATH`。
