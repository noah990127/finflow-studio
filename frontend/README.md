# FinFlow 前端

Vue 3 + TypeScript + Vite。设计约束见 [AGENTS.md](AGENTS.md)：主界面简约，技术信息按需展开，避免重复标题和永久工具栏。

```bash
npm install
npm run dev -- --port 5174
npm run build
node --test tests/*.test.mjs
```

完整本地环境使用根目录 `scripts/start-local.sh`。API 定义集中在 `src/api/client.ts`；服务令牌、模型密钥和网站凭据不可放进前端配置。

## 主要入口

- `views/ProjectWorkbench.vue`：项目资料、业务工作与 Agent 面板。
- `components/AgentWorkOverview.vue` / `WorkExecution.vue`：业务工序概览与固定执行。
- `components/NativeDocument.vue` / `ResearchReader.vue`：原生文档与网页资料阅读。
- `components/AssistantPanel.vue` / `stores/assistant.ts`：对话、事件流、审批、中止和续轮。
- `components/BrowserTaskHandoff.vue`：输入框上方的人工操作条。按当前对话事件关联网站，点击“我已完成，继续执行”后交还控制并触发原对话继续，保留输入草稿。
- `components/BrowserAuthorizations.vue`：连接管理、授权与审计。

## 人工操作交互

只有当前对话关联的网站处于人工控制时才显示操作条；正常运行隐藏。切换对话丢弃旧请求结果，不混入其他任务连接。状态读取失败展示重试，不能静默隐藏。

交还控制不代表登录成功，Agent 仍须观察并核验页面。发送仍走原授权规则；按钮回调不能直接重放提交，也不要求用户再输入“登录好了”。

验收覆盖窄面板、多个网站、切换对话、网络失败、重复点击、未登录和提交结果未确认。模拟接口测试不等同于真实第三方网站验证。
