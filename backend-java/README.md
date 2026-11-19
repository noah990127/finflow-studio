# FinFlow Java API

JDK 21 / Spring Boot 主服务，负责用户身份、项目资源、工具契约、任务状态、固定工序与授权审计。

```bash
mvn spring-boot:run
mvn test
```

默认端口 `8080`，健康检查 `/actuator/health`。完整环境从根目录 `scripts/start-local.sh` 启动；手动启动需注入环境配置。本地 H2 数据位于 `data/`，生产配置见 `.env.example` 和部署指南。

## 关键模块

- `assistant/AssistantToolContracts`：参数类型、长度和格式约束。
- `assistant/AssistantExecutionService`：工具执行、审批、事件和错误传播。
- `assistant/AgentWorkService`、`BusinessWorkProjection`：业务工序与成果投影。
- `assistant/FixedWorkService`、`FixedToolExecutor`：固定版本执行。
- `assistant/BrowserAuthorizationService`：网站授权、会话归属、审批范围与审计。
- `worker/WorkerClient`：Worker 和 Browser Runner 调用。
- `knowledge/NativeDocumentController` / `workspace/ResourceVisibilityService`：原生文档与资源可见性。

## 安全与持久化

网站连接授权不同于具体对外提交。保存授权不允许任意收件人、任意内容或跨用户复用。提交审批绑定操作与页面上下文，普通导航和编辑由动作策略判定。

用户通过 `/api/browser/authorizations/connections/{session}/{action}` 切换控制权；服务验证归属并记录审计。模型声称用户授权不能跳过校验。

数据库、对象存储和加密密钥一起制定备份策略。密钥不提交仓库，运行中不直接打开 H2 数据文件。审批描述使用有长度限制的摘要，不能塞入完整页面快照。
