# FinFlow Studio

面向个人专注工作的财经数据工作流平台。代码目录即当前目录：

- `frontend`：Vue 3 业务工作台；
- `backend-java`：JDK 21 / Spring Boot 主服务；
- `worker-python`：FastAPI 文件、表格和输出计算服务；
- `docker-compose.yml`：生产依赖和前后端服务编排。

## 本地启动

推荐使用统一启动脚本。它会启动或复用 ONLYOFFICE Document Server，等待健康检查通过，再依次启动 Python、Java 和 Vue：

```bash
./scripts/start-local.sh
```

打开 `http://127.0.0.1:5174/`。ONLYOFFICE 使用 `http://127.0.0.1:8082/`，服务日志和进程号保存在 `.run/`。停止整套本地服务：

```bash
./scripts/stop-local.sh
```

首次启动需要下载 ONLYOFFICE 镜像，所以会比后续启动慢。不配置模型 API Key 时，资料解析、数据抽取、表格加工和文件输出仍可用，智能分析使用本地提取式降级。

## 生产部署

```bash
docker compose up --build -d
```

生产环境通过 `DATABASE_PASSWORD`、`DEEPSEEK_API_KEY` 和各数据源密码环境变量注入密钥。数据库连接配置中的密码字段只能填写 `env:变量名`，不会保存明文。

FinFlow Studio 是一个面向个人深度工作的智能工作流平台。当前仓库包含三个可独立运行的应用：

- `frontend`：Vue 3 + TypeScript 的蓝白业务界面；
- `backend-java`：JDK 21、Spring Boot 4、Spring AI 的主后端与 AI 安全执行控制面；
- `worker-python`：FastAPI 资料解析、Ref 检索和文件分析 Worker。

## 已实现的第一版能力

- 创建和读取个人项目；
- 全局 AI 助手会话；
- 当前页面和选中对象的上下文快照；
- 自动生成可读任务步骤；
- `READ_ONLY`、`DRAFT_ONLY`、`CREATE_VERSION` 风险分级；
- 修改型计划的版本、哈希、过期和确认校验；
- 幂等任务创建、后台执行、事件历史、SSE、取消与撤销；
- Codex Responses API 模型网关，保留 DeepSeek 适配；没有 API Key 时自动使用本地安全计划器和提取式分析；
- Python 本地摘要、Ref 检索和样本数据概况；
- Vue 项目工作台和完整 AI 任务面板。
- Vue Flow 可视化工作流编排，支持选择已有文件、数据连接、表格、Ref、AI 分析和成果生成；
- 工作流草稿、一键检查、不可变版本、逐步运行记录、停止和从失败处续跑。
- CSV 大文件游标分页预览，以及 PDF、PowerPoint、Word 和文本的在线查看。
- 内置 ECharts 财务报告和 Perspective 自助分析，无需额外部署 BI 或报告设计服务。

## 本机启动

### 1. Python Worker

```bash
cd worker-python
python3 -m venv .venv
source .venv/bin/activate
pip install '.[dev]'
python -m uvicorn app.main:app --reload --port 8001
```

### 2. Java 主后端

需要 JDK 21：

```bash
cd backend-java
mvn spring-boot:run
```

默认使用文件型 H2，数据保存在 `backend-java/data`。切换 PostgreSQL 或 openGauss 时设置 `DATABASE_URL`、`DATABASE_USERNAME`、`DATABASE_PASSWORD` 和 JDBC Driver。

### 3. Vue 前端

```bash
cd frontend
npm install
npm run dev
```

打开 `http://127.0.0.1:5173`。若端口被占用，可执行 `npm run dev -- --port 5174`。

## 配置 Codex 分析

取得 API Key 后：

```bash
export FINFLOW_LLM_PROVIDER=codex
export OPENAI_API_KEY='你的 OpenAI API Key'
export OPENAI_MODEL=gpt-5.6-sol
export OPENAI_REASONING_EFFORT=medium
```

API Key 只在 Python 服务端读取，不会进入浏览器，也不会复用桌面 Codex 登录态。没有 Key 时项目、计划、确认、任务和手动页面仍然可用。

## 测试

```bash
cd backend-java && mvn test
cd worker-python && .venv/bin/python -m pytest
cd frontend && npm run build
```

## Docker Compose

仓库包含 `docker-compose.yml`。平台全部服务、ONLYOFFICE、MinIO、环境变量、两套案例初始化和离线镜像部署步骤见 [`docs/Docker 部署指南.md`](docs/Docker%20部署指南.md)。

## 可部署示范案例

仓库在 `examples/cases` 中提供两个经过脱敏的可移植案例：英伟达近五年经营状况分析和 2026 年全球税务政策趋势。资源包包含源文件、脱敏连接、工作流、模拟数据库/API 和初始成果规格，部署与导入步骤见 `examples/cases/README.md`。
