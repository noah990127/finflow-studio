# FinBTP Studio / FinFlow

面向个人工作的 Agent 工作台：用户提出目标、提供资料并检查成果，Agent 安排业务工序、调用工具、生成文件。“我的工作”是业务工作流，技术调用与执行记录按需展开，不要求用户配置技术节点。

## 当前能力

- 项目来源目录、原生 Markdown 预览与编辑、网页资料阅读和在线 Office 文件查看。
- 业务工序按用户检查的成果组织，技术片段与辅助材料不抢占主成果展示。
- Codex CLI / OpenCode CLI 任务运行时切换，结构化工具契约、审批、审计、流式进展与中止。
- 固定版本重复执行：输入可以更新，已发布 SQL、提示词或工具逻辑不因重跑自动改写；改逻辑时生成新版本。
- 已发布实现归档为固定版本资源，执行前校验内容；逐步骤记录输入版本、代码校验值和结果，技术资源默认隐藏，可在技术详情下载。浏览器脚本从资源库加载，后台只补归档、不重做外部操作。
- SearxNG 搜索、网页读取与快照、结构化数据处理，以及报告、文档和演示文稿输出。
- 独立浏览器执行服务：用户登录、Agent 操作、对外操作确认与结果核验。定时执行尚未实现。

## 文档导航

| 模块 | 说明 |
| --- | --- |
| [前端](frontend/README.md) | 简约交互、人工操作条、构建与验证 |
| [Java 后端](backend-java/README.md) | 工具契约、业务工序、授权与密钥 |
| [Python Worker](worker-python/README.md) | CLI 运行时、搜索、文档与测试 |
| [浏览器执行](worker-python/app/browser_runner/README.md) | 授权边界、登录接管、编辑器和诊断 |
| [独立浏览器 Agent](worker-python/app/browser_agent/README.md) | browser-use + 官方 A2A SDK，任务委派、原地接管与取消 |
| [PPT 渲染](worker-python/app/ppt_skills/README.md) | 内容规划、模板和格式限制 |
| [部署指南](docs/Docker%20部署指南.md) | Docker、持久卷与离线部署 |
| [演示案例](examples/cases/README.md) | 脱敏数据与历史兼容性样例 |

## 本地启动

准备 JDK 21、Maven、Node.js/npm、Python 3.12，以及 Docker 或 Colima。按 `.env.example` 创建本地 `.env`，替换默认密码与所有示例密钥；不要提交 `.env`。

```bash
python3.12 -m venv worker-python/.venv312
worker-python/.venv312/bin/pip install -e './worker-python[dev]'
worker-python/.venv312/bin/python -m playwright install chromium
npm --prefix frontend install
```

配置所选 CLI 的可执行路径、登录或模型提供方后启动：

```bash
./scripts/start-local.sh
```

| 服务 | 本地地址 |
| --- | --- |
| 工作台 | http://127.0.0.1:5174/ |
| Java API | http://127.0.0.1:8080/ |
| Python Worker | http://127.0.0.1:8001/ |
| Browser Runner（可选） | http://127.0.0.1:8002/ |
| ONLYOFFICE | http://127.0.0.1:8082/ |
| SearxNG | http://127.0.0.1:8888/ |

日志与进程号位于 `.run/`。脚本复用健康服务；修改代码或环境后需重启对应服务，健康不等于已更新。停止本地应用用 `./scripts/stop-local.sh`。浏览器服务重启会丢失内存登录会话，先核验未完成的对外提交再重启。

## 模型与运行时

`AGENT_RUNTIME` 选择任务框架，与工具内部生成内容使用的 `FINFLOW_LLM_PROVIDER` 分开：

```dotenv
AGENT_RUNTIME=codex-cli
CODEX_CLI_PATH=codex
# 公司部署可改为：
# AGENT_RUNTIME=opencode
# OPENCODE_CLI_PATH=opencode
# OPENCODE_MODEL=提供方/模型
```

CLI 必须预先安装并能由服务运行账号调用。公司环境须提供批准的 CLI、模型配置与依赖镜像；桌面登录态不会自动进入容器。CLI 不可用时排查具体错误，不依赖自动切回旧助手。工具内部生成任务仍需配置对应模型。

自定义模型设置、数据源凭据由后端管理。默认密钥文件包括 `backend-java/data/agent-model.key` 和 `backend-java/data/data-connection.key`；迁移时和数据库一起安全备份，不能推送 GitHub。默认账号和示例密码仅供本地演示。

## 浏览器人工操作

本机可见窗口需设置 `BROWSER_RUNNER_ENABLED=true`、`BROWSER_INTERACTIVE_HEADED=true`，并配置同一个足够长的随机 `BROWSER_RUNNER_TOKEN`。它是服务间令牌，不是用户授权或网站登录凭据。

1. 在项目对话中提出任务，首次连接网站由用户授权。
2. 在打开的网站窗口完成登录或人工操作。
3. 回到当前对话，在输入框上方点击 **“我已完成，继续执行”**。平台交还控制并继续原任务，无需另外输入消息。
4. Agent 检查页面并处理内容；发送、提交等对外操作仍需确认。
5. 提交后核验结果。结果不明确时允许查看记录，但禁止盲目重发。

按钮只显示当前对话关联且处于人工接管状态的连接。网站授权弹窗用于连接管理、撤销和审计，不是正常继续任务的主要入口。可见浏览器接管目前面向本机单用户；远程部署需另建受保护的人工操作通道。

## 验证

```bash
./scripts/verify.sh
node --test frontend/tests/*.test.mjs
```

统一脚本运行 Java、Python、前端构建和空白错误检查。浏览器测试使用本地模拟网站，不向真实邮箱发送邮件；依赖外部模型或系统软件的可选测试可能跳过。点击成功或 Agent 声称完成，不能代替真实产物与业务结果校验。

## 部署与数据安全

### 本次开发快照验证（2026-11-19）

本次是 `dev` 开发快照，不是全量验收通过的发布版本：

| 检查 | 结果 |
| --- | --- |
| Java 全量 | 306 项，7 失败、1 错误、1 跳过 |
| Python 全量 | 258 通过、2 失败、1 跳过 |
| 前端单元测试 | 78 通过、3 失败 |
| 前端类型检查及构建 | 通过 |
| Git 空白检查 | 通过 |

待处理项包括 Java 工序标题断言、任务中止状态、搜索测试和批量删除事务锁；Python 技能边界说明断言与 PPT 页数预期；前端审批模板、文档复核和旧执行详情文案断言。失败原因仍需逐项复核，不能一概视为测试过时。浏览器局部回归和模拟交互通过不代表这些全量问题已经解决。

```bash
docker compose up --build -d
```

Compose 与桌面环境不是等价运行时，详见部署指南。离线环境提前提供批准的包、CLI、镜像、字体、浏览器与模型服务，并配置企业数据源和网络策略。不要直接公开 Worker、Browser Runner、调试端口或默认凭据。

`.run/`、`artifacts/`、本地数据库、浏览器状态、密钥及个人资料不属于源码发布内容。保留所有模板与示例的上游许可证。
