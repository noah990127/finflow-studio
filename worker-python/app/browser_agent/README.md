# Browser Agent

独立的 browser-use Agent，通过官方 `a2a-sdk` 提供 JSON-RPC A2A 接口。平台不实现 DOM 定位、富文本编辑或点击循环。主 Agent 只使用 `browser.delegate` 和 `browser.task_status`。

## 运行

使用 Python 3.12 独立环境，不要把依赖安装到文档 worker。当前锁定 browser-use 0.13.14：该版本保留原生动作工具及步骤回调；1.x 改为运行 Python，不能不经隔离设计直接升级。

```sh
python3.12 -m venv .venv-browser-agent
.venv-browser-agent/bin/pip install -r browser-agent-requirements.txt
# 通过密钥管理注入以下配置后，从 worker-python 目录启动
.venv-browser-agent/bin/python -m uvicorn app.browser_agent.service:app --host 127.0.0.1 --port 8003
```

必填：`BROWSER_AGENT_TOKEN`（至少32字符，后端与独立服务一致）和 `BROWSER_MODEL_API_KEY`（也支持已有 `DEEPSEEK_API_KEY`）。默认 `BROWSER_MODEL_NAME=deepseek-v4-flash`、`BROWSER_MODEL_BASE_URL=https://api.deepseek.com`；DeepSeek 使用 browser-use 原生 ChatDeepSeek 适配器。模型必须支持图片与结构化工具输出；CLI 登录不是此模型 API 的凭证。显式指定本地浏览器，不使用 Browser Use Cloud。默认关闭 `ANONYMIZED_TELEMETRY` 和 `BROWSER_USE_CLOUD_SYNC`。

## 生命周期

- 委派本身无需人工确认，但网站安全授权独立于业务审批模式：启动前先检查入口、allowed_domains 及目标中明确URL对应的域名。未授权域名展示“允许访问”，按平台账号逐域名保存30天，可撤销；不是邮箱登录账号绑定，也不按整段任务目标计算授权。AUTO 不能绕过此检查。后端从任务读取审批策略，模型不能覆盖：AUTO 在任务范围内自动填写和提交；CONFIRM 在对外提交前请求确认。
- A2A `message/send` 创建任务，`tasks/get` 查询，`tasks/cancel` 中止。使用 SDK 的 Agent Card、请求校验和任务存储，不自造 A2A 协议。
- `input-required` 时前端显示继续按钮。按钮携带当前 `promptId`，恢复内存中的同一个 Agent/浏览器；不启动新 CLI，不重复登录。
- Agent 的 DOM、视觉、导航、输入和恢复全部使用 browser-use。平台扩展包含人工接管、任务状态、身份授权及显式数据绑定，不另写定位或点击引擎。
- 下载和PDF通过 A2A File artifact 返回，后端按用户鉴权下载；状态轮询不携带文件正文。单任务最多10个文件、合计20MB，避免大文件进入主 Agent 上下文。
- 请求键按用户绑定，重复请求不能启动第二个浏览器；相同键不得改变目标。任务读取/继续/取消按用户隔离。
- 人工等待上限24小时，不计入1小时有效执行时限。状态通过轮询刷新，当前没有宣称支持 A2A streaming。

## 当前边界

当前是本机单用户可见浏览器部署。共享服务器需要为每个用户部署独立浏览器容器和受认证的远程接管入口后才能开放。域名限制不是网络沙箱，生产部署必须另外配置网络出口策略。`allowed_domains` 可以包含明确的登录跳转域名，完整展示在任务审批中；不会默默放宽到全部网站。

任务授权是完整目标级别，不沿用旧系统的每次点击审批；模型仍可能偏离目标，因此高风险财务或不可撤销提交不应在没有业务专用校验的情况下开放。任意 JS、上传、文件读写和未知工具不向模型开放。

A2A运行态暂存在内存；重启不恢复正在运行的任务，不自动重放外部提交。Java保留任务映射、已获取的终态结果与原审批记录。成功和失败历史保存在私有数据目录；部署方应设置保留周期。旧 `browser_runner/engine.py` 仅服务已有查询导出工序。没有定时调度。

## 登录与执行恢复

人工待办使用 `LOGIN`（登录）、`INPUT`（人工填写）、`APPROVAL`（具体提交）、`VERIFICATION`（结果核验）类型。每次回执包含任务、待办版本、类型、确认时间和原请求。提交待办只能通过 `approve` 确认；普通 `resume` 不构成提交授权，旧待办不能确认新请求。Java 对人工控制事件和确认类型记录审计。此机制明确用户意思，不等于已经建立独立于模型的每次点击内容校验器。

启动时先检查页面登录状态，不再无条件要求已登录用户重复登录。二维码等不能确定的页面由 Agent 申请登录接管；不会从交还控制自动推断登录成功。`get_current_time` 提供指定 IANA 时区的系统时间，无需访问授时网站；其准确性依赖部署主机时钟。

`browser.delegate` 不再消费旧的整任务授权，而是检查 `browser_user_grant` 中当前 owner 的 DOMAIN 授权，重新启动服务后仍有效。修改日期、正文或任务描述不会重新申请同一域名；新增域名、过期或撤销后重新确认。完整任务所需网站必须由主 Agent 在调用前列出，禁止通配符隐式扩大范围；发现未知登录跳转域名仍会阻止访问，不会自动放开。固定重跑同样检查已保存脚本的网站范围，缺失授权时停止，不会绕过。AUTO 的业务提交回执标记 `source=execution_policy`，不是伪造人工点击。LOGIN/INPUT 仍暂停；CONFIRM 人工确认仍绑定待办版本并记录审计。运行中追加网站授权及实时撤销传播仍未实现，需中止原任务后重新授权启动；不能把域名拦截解释为登录问题。

登录配置按平台用户和入口域名隔离，保存在私有数据目录的 `profiles/` 下，目录权限0700，不进入资源产物或Git。默认每个用户每个域名使用一个账号环境；同站点多账号选择/删除连接的管理界面尚未提供，可在目标网站退出登录后换账号。保留的是自动化专用环境，不读取用户日常浏览器。SDK 0.13.14 会复制普通Chrome配置目录，因此托管目录使用其识别的前缀，升级SDK必须复测持久化行为。

`recovery.py` 扩展原生重放：每个动作执行前检查登录跳转、密码框和原生元素匹配；不满足时保持同一任务、窗口与输入，发布 `input-required`。主对话可“打开操作窗口”，该动作只激活原标签页、不恢复执行；“我已完成，继续”后重新检查页面，仍未登录就继续等待。二维码等页面未必能直接识别，找不到目标元素也会暂停，不伪称登录成功。

原生动作逐个提交，完成数及 dispatching/completed/uncertain 状态保存在私有 `*.checkpoint.json`。这用于现场恢复与审计，不是服务重启后自动重放的依据。已经发出的动作出现异常，结果可能已生效，因此停止且不重试；用户须核对实际结果。对外提交前仍核对账号及输入，最终仍需核验本轮结果。自动判断当前账号身份、跨进程续跑暂未实现。

## 工序技术产物与重放

固定执行的人工作业使用通用 `FixedInteractionService`，以执行编号绑定步骤、提供方、任务编号和待办版本。提供方通过 `FixedInteractionHandler` 接入，不把网站或浏览器判断写入工序控制器。浏览器适配器将 A2A `input-required` 投影为工序、技术步骤和业务步骤的 `WAITING_INPUT`；底层执行线程仍存活，数据库运行状态保留 RUNNING，重启恢复仍按既有中断策略处理。

直接执行工序时，“等待操作”“打开操作窗口”“完成，继续”出现在工序原有操作行，无需开启 Agent 对话。继续请求检查项目、会话、执行归属及任务和待办版本，旧页面不能恢复另一轮执行。等待期间每10秒检查窗口，单次检查上限5秒；窗口已断开则失败退出，不自动重新提交。网络读取失败显示状态未知，而不是声称仍正常运行。窗口断开与服务重启暂不支持跨进程续跑。

界面模拟回归：从根目录运行 `worker-python/.venv312/bin/python scripts/verify-fixed-interaction-ui.py`，验证不依赖对话框的继续入口、精确待办绑定和桌面/移动布局；不会调用真实网站。

成功任务经 `procedure.py` 分派固化。旧版 v1 保持兼容，只绑定 `input.text`；新版 v2 由 `procedure_v2.py` 编译显式数据流，保存引擎/工具版本、原生匹配信息、输出契约、绑定、审批模式及 SHA-256。新任务不向规划模型开放无来源的直接填写工具，填写和点击适配器仍调用原生 browser-use。

### 通用数据流 v2

- `workflow_input` 声明用户可编辑字符串参数；`workflow_time` 每轮读取时间和日期。
- `workflow_outputs` 返回名称与字段目录，长期工具回执保留成功状态及产物信息，避免 Agent 猜名称或反复导出。用户输入必须经引用连接到真实操作或产物，未使用的参数不能伪装成可编辑输入。
- `workflow_extract` 用固定提示词与 JSON Schema 提取当前页面数据；`workflow_transform` 用固定提示词处理绑定的数据，例如分类、总结、报告。两者只读且没有工具调用或重新规划权限，不替代已有 SQL/脚本执行器。
- `workflow_render` 使用 Python 标准库 Template 的 `${name}` 占位符。绑定采用 `{"source":"outputName","path":["field",0]}`，不执行表达式、Python 或 JavaScript。
- `workflow_fill` 将当前输出填入原生定位的字段；`workflow_click`/`workflow_keys` 声明 read/write 和稳定操作标识。同一运行的同一 write 标识不会重复派发。
- `workflow_tab` 通过不含会话参数的 URL 匹配标签页；匹配多个或缺失时失败，不复用旧 tab ID。
- `workflow_navigate` 支持绑定本轮网址，仍受浏览器域名授权限制，禁止在 URL 中嵌入凭证。
- `workflow_review` 展示本轮完整内容；CONFIRM 提交前核对数据版本，数据变更后重新确认；AUTO 沿用已有执行策略。
- `workflow_artifact` 导出绑定的 Markdown、文本或 JSON。PPT/其他文档继续使用平台现有固定文档渲染工具，不在浏览器服务复制实现。

`dataflow.py` 不依赖浏览器驱动，使用 jsonschema 做契约校验。发布时检查引用顺序、必填字段、索引保证、模板变量、输出类型、工具版本和副作用声明；运行时再验证真实值与大小。记录实际派发动作，部分批次只保留有凭据的已执行前缀，未执行尾部不进入脚本。只读提取失败可以排除；结果不明的写入不能自动清除。任意历史轨迹不会被猜测为正确数据流。

无法固化时保留非执行型 `*.candidate.json`，仅含步骤摘要和拒绝原因，不把截图、正文或输入值复制进去。旧金价/邮件等记录缺少来源关系时仍需重新整理，不能自动假定正文引用了哪个页面字段。该版本未提供任意旧轨迹的自动语义修复，也不支持循环/条件分支编译。

执行状态与固化状态分离：`NEEDS_REVIEW`、`READY`、`PUBLISHED`、`REPLAY_SUCCEEDED`。成功操作仍是 completed；页面单行显示未固化原因，可展开技术详情。首次发布标记静态校验通过、首次执行成功、重跑未验证，绝不为验证自动重复发送或删除。

平台在读取成功任务时归档脚本、可编辑输入文件、可读结果，并关联到原业务工序；脚本标记为 INTERNAL，只在技术详情中展示。首次归档自动发布 `browser.replay` 固定工具实现；已有其他实现时保留产物并提示合并，不覆盖原实现。重复读取不会重复创建资源或版本。历史成功任务可由后端在校验用户归属后调用内部 `/procedures/export` 补归档，无需重发邮件等对外操作。

“开始执行”沿用 FixedWorkService，读取参数文件当前版本并快照，调用 A2A 新任务。新工序将审批模式纳入不可变脚本及哈希；回放继承该模式，旧脚本缺失时保守使用 CONFIRM。AUTO 不额外要求确认输入；CONFIRM 回放前核对本轮完整输入。回放使用 SDK 原生 `load_and_rerun`，不调用规划模型；禁用失败重试、菜单补点击和重复步骤猜测跳过。结束后只读调用模型检查当前页面截图及本轮输入，不强制人工核验；无法证实成功时报告结果未核实，不按点击成功推断、不自动重发。保存原执行的脚本不等于重放已经验证。

私有数据目录中的 `*.procedure.json` 包含归属信息和不可变实现，须与平台数据库及资源文件一起备份和迁移。丢失时不会重新规划或静默回退。平台资源文件是可检查的技术产物，编辑其副本不会改变已发布版本；修改执行逻辑应重新设计、执行并发布新实现。

## 验证

通用绑定与固化测试：`tests/test_browser_dataflow.py`。本地原生浏览器回放验证：从根目录运行 `worker-python/.venv-browser-agent/bin/python scripts/verify-browser-dataflow.py`；首次录制与重放分别使用不同数据，断言页面回执和 Markdown 都变化，不访问外部站点。该脚本的提取模型是替身，DOM 操作和匹配使用真实 browser-use。

真实模型验证：`worker-python/.venv-browser-agent/bin/python scripts/verify-browser-flow-agent.py`，读取本地模型配置，只访问临时本地库存页。自然语言生成 v2 工序后，更换用户抬头和页面库存，再通过完整运行时重放并核验。成功结果记录在 `.run/browser-flow-agent-result.json`。此验证不代替 QQ 邮箱等真实站点兼容性测试，不会发送邮件。

核验使用当前页面 DOM/截图、本轮解析输出以及真实产物回执；产物存在不代表外部提交成功。重放参数优先于原目标中的示例值。v2 固化模型名称和提示词，不重新规划；读取的仍是本轮数据。页面状态验证：`scripts/verify-procedure-status-ui.py`，覆盖执行成功但固化失败、发布状态及桌面/手机布局。

`PYTHONPATH=. .venv-browser-agent/bin/python -m pytest tests/test_browser_agent.py`。状态机测试使用替身执行器验证协议、隔离、接管与取消，不等同于真实模型完成网站任务；真实网站回归需配置模型后单独运行，不能用通过单测代替。

本地真实回归：先在 `examples/browser-agent-check` 启动 `uvicorn app:app --host 127.0.0.1 --port 8767`，再从项目根目录运行 `worker-python/.venv312/bin/python scripts/verify-browser-agent.py`。它通过 A2A 请求真实模型操作普通输入框、数值框、富文本和提交按钮，模拟一次人工交还，并向测试站点核对只有一条新增记录、全部字段匹配。结果写入 `.run/browser-agent-verification.json`；不操作邮箱，不代表真实邮箱回归或完整平台 UI 回归。

2026-11-19 已使用 DeepSeek V4 Flash 通过上述真实回归。macOS 自动化配置使用 `--use-mock-keychain`，避免启动时访问登录钥匙串；本地 CDP 连接强制绕过环境代理，外部模型请求保留代理配置。

登录恢复真实浏览器回归：在根目录运行 `worker-python/.venv-browser-agent/bin/python scripts/verify-browser-login-recovery.py`。脚本自动启动本地登录站点，验证跨浏览器启动保留登录、登录失效暂停、未登录点击继续仍等待、原生重放不重复前序动作；不调用模型、不访问邮箱。单元测试另包含 `tests/test_browser_recovery.py`。
