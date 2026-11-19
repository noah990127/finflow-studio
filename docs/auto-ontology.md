# 从审核台账发布到 NVIDIA Auto Ontology

## 业务使用

在项目的「业务知识梳理」选择来源、核对范围和原文，处理阻断缺口并完成台账。到「成果与审核」审核本版流程及候选。进入「本体模型」即可构建模型，查看每项候选的去向，下载原生模型、OWL/Turtle 本体与证据清单。管理员尚未绑定远端时也可以完成这部分工作。

发布前需核对验收场景并运行本版建模校核，处理待审和未覆盖路径，再构建一次。页面会显示阻断原因。由登录的项目所有者核对目标实例和模型摘要后发布。资料换版后应回到台账查看变化、重新审核和构建。历史发布仍可下载当时的模型与证据清单。

| 业务资料 | 在 Auto Ontology 中的表示 | 核对重点 |
| --- | --- | --- |
| 已确认的物理表、列 | 数据库、模式、表、列及绑定术语 | 只导出审核定义支持的字段、类型及可解析的主外键。核对未映射关系的提示。 |
| 已确认的业务对象 | 独立逻辑模式中的表投影、术语及字段属性 | 表示业务概念。写入本体目录不会在财务业务数据库建表。 |
| 业务对象之间的具名关系 | OWL/Turtle 中有来源和目标类型的对象属性，关联经过审核的证据 | 核对两端业务对象、关系方向和原文基数。原生目录写入需要明确连接键，业务关系名称不能证明数据库如何连接。 |
| 流程、动作、函数或规则 | 对应候选、审核、引用和原文保存在 FinFlow 清单 | 这些记录用于解释和复核。落账、审批和规则执行还需要各自经过验证的业务系统。 |
| 能力问题、验收场景 | 已审核问题、场景签名、验收用例与走查保存在清单 | 路径覆盖可重放；金额、四舍五入、会计期间、例外审批等业务结果应由业务负责人独立核验。 |

原生模型文件是 JSON 语法的 UTF-8 YAML，可以直接导入上游。单独的 Turtle 文件供支持 RDF/OWL 的图工具使用；发布操作传输 NVIDIA 原生模型。旁边的 JSON 清单保留在 FinFlow，包含来源版本、条款、审核人及处置说明。模型本身仍会包含业务术语、结构和描述，绑定目标前应核对这些资料允许传输的范围。

## 管理员部署

经联调的上游为 NVIDIA Auto Ontology 1.3.0 源码提交 `d7e553e4c18112479c78b8f846e9929c4b5ed0a3`。通过官方前端网关的 API key 访问后端。使用上游部署指南配置独立 PostgreSQL、向量扩展、网关鉴权与备份。以下配置接入已经可用的实例。[上游源码](https://github.com/NVIDIA/auto-ontology/tree/d7e553e4c18112479c78b8f846e9929c4b5ed0a3) · [上游部署文档](https://github.com/NVIDIA/auto-ontology/blob/d7e553e4c18112479c78b8f846e9929c4b5ed0a3/DEPLOYMENT.md)

每个项目使用一个独立、起始为空的 Auto Ontology 实例。上游术语目录有全局可见的名称和属性。在同一个网关前配置多个域名仍会访问同一个目录，部署者应在数据库和网关层面隔离项目。项目绑定文件保存在仓库外，文件名为 `targets.json`。以下内容中的项目 ID 换成 FinFlow 项目的实际 ID。

```json
{
  "your-finflow-project-id": {
    "base_url": "https://procurement-ontology.example.org",
    "target_id": "finance-procurement",
    "token_env": "PROCUREMENT_ONTOLOGY_API_KEY"
  }
}
```

- `base_url` 使用网关 HTTPS 源地址。本机回环开发测试可使用 HTTP。新目标地址或标识会产生新的目标指纹；轮换同一目标的 API key 保持指纹稳定。一个地址及一个目标标识只允许绑定一个项目。
- 在仅供 Worker 使用、位于仓库外的环境文件中设置 `PROCUREMENT_ONTOLOGY_API_KEY`。内部 CA 可以挂载到配置目录，并在同一环境文件设置 `FINFLOW_AUTO_ONTOLOGY_CA_FILE=/run/finflow/ontology/ca.pem`。Worker 校验服务端证书和主机名。
- Java 与 Worker 共用 `FINFLOW_AUTO_ONTOLOGY_WORKER_SECRET`。用密钥管理系统生成和注入至少 32 个 ASCII 字符。只有 Worker 需要上游 API key。FinFlow 的业务前端无需接触任一凭据。
- 用 `FINFLOW_AUTO_ONTOLOGY_CONFIG_DIR` 指向上面配置文件的宿主目录，用 `FINFLOW_AUTO_ONTOLOGY_ENV_FILE` 指向 Worker 环境文件。目录和文件需允许容器内 Worker 用户读取。使用部署指南中的强密码、业务存储与网络配置。

```bash
docker compose -f docker-compose.yml -f docker-compose.ontology.yml up -d --build
```

不使用 Compose 时，Java 进程设置共享密钥，Worker 设置共享密钥、`FINFLOW_AUTO_ONTOLOGY_TARGETS_FILE`（配置文件绝对路径）、绑定的凭据变量及可选 CA。Worker 仅向受信任的内部网络提供服务。Java 负责验证项目所有权、读取当前源文件版本与保留发布快照。

## 核验与恢复

发布先保存不可变快照和请求标识，再调用 `POST /api/model/import?replace=false&embed=false`。系统只发送准备好的模型。写入后按目标数据库 ID 重新导出，对照术语、表、列、属性和外键，再单独核对术语的描述和绑定。上游可重新分配 ID，核验使用实际关系与字段内容。更改后的版本使用新的命名空间，旧快照可继续核对。运维需要为版本保留预留空间，并为过期目录制定由业务方确认的清理周期。

| 发布状态 | 含义及操作 |
| --- | --- |
| 发布中（PUBLISHING） | 首次请求已经入库。重复点击会返回同一记录。预留超过五分钟后可发起只读核对。 |
| 已核验（VERIFIED） | 发布时按范围从远端读回后与快照一致。历史回执记录的是当时的结果。 |
| 待核对（UNCERTAIN） | 网络异常、协议不一致或无法确定远端状态。重试只读取；核对前同一目标暂停发布新的版本。 |
| 未发现（ABSENT） | 只读核对未发现该快照。核对绑定与当前模型后，可显式重试写入。 |
| 失败（FAILED） | 配置或模型校验失败，或上游明确拒绝导入。修正原因后可重试仍然有效的当前模型。 |

公开 API 位于 `/api/projects/{projectId}/tacit/ontology/publications`。GET 返回目标及历史游标，POST 接受 `request_id`、`expected_revision`、`model_digest`、`target_id`。同一请求可以安全重放。`/{id}/snapshot` 下载该项目的历史快照，`/{id}/retry` 按表中的状态核对或重试。一次发布支持至多 512 个原生术语、2 MiB 的模型请求，单个远端响应限 4 MiB。应用尚未验证超出该规模的拆分发布。发布不需要上游模型推理或嵌入服务。

## 在自己的环境验收

先为验收专门准备空的目标实例与测试账号，并将上游凭据注入环境变量 `FINFLOW_AUTO_ONTOLOGY_API_TOKEN`。验收脚本会创建真实目录及合成采购资料。测试使用固定抽取响应，执行真实登录、文件处理、审核、构建、导入、独立读回、凭据失效恢复、重复请求和资料换版。运行前安装 Worker 开发依赖、打包 Java 后端并通过环境注入验收网关 API key。默认创建临时 H2 库。对独立 FinFlow PostgreSQL 验收库可加 `--postgres` 并设置 `DATABASE_URL`（JDBC URL）、`DATABASE_USERNAME` 和 `DATABASE_PASSWORD`；脚本会真实写入验收项目。

```bash
worker-python/.venv312/bin/python scripts/verify-ontology-publication.py --help
worker-python/.venv312/bin/python scripts/verify-ontology-publication.py \
  --backend-jar backend-java/target/finbtp-backend-0.1.0-SNAPSHOT.jar \
  --target https://ontology-acceptance.example.org
```

是否放行实际财经工作还需要两类现场证据。业务负责人选取自己熟悉的脱敏材料与例外情形，由独立评审人先固定应出现和应暂缓的结论，再用正式模型盲评精确率、召回率、证据定位以及高影响误判。平台负责人在拟上线环境用实际资料体量、并发度和发布频率验证容量，并从备份恢复 FinFlow 与本体库，核对同一个发布摘要和来源版本。企业环境的模型、文档、业务标准和部署边界都会影响结果。[已有盲审和负载验收工具](../scripts/fixtures/knowledge/README.md)

## 本次发布的验收记录（2026-11-19）

| 验收场景 | 观察结果 |
| --- | --- |
| 完整采购流程 | 使用真实 FinFlow 登录、上传、解析、审核与发布，经过 NVIDIA 官方身份网关、FastAPI 和 PostgreSQL 17.12（pgvector 0.8.0）。FinFlow 使用 H2、独立 PostgreSQL 两种部署分别通过。固定模型响应只承担合成资料的抽取。 |
| PostgreSQL 双端读回 | 采购模型的 4 项候选中，有 1 个逻辑业务对象、3 个保留在清单的行为定义。独立查询核对了表、术语及“采购单号”属性绑定；替换来源后，台账从版本 14 升到 15，旧版发布请求被拒绝，历史快照可读。 |
| 业务关系图 | 独立 RDFLib 解析器读回 32 个三元组，核实 2 个业务类和 1 个具名关系的定义域、值域和来源。6 项未确认、依据不足或相互冲突的关系断言在清单中列明原因。 |
| 跨版本发布 | 同一项目在同一上游实例保存两个不同的已审模型，两次各写入一次。分别查询旧版和新版，两个原始远端数据库 ID 保持稳定。 |
| 多表模型 | 独立样例的 3 个术语、5 个列和属性及显式外键 `PO_LINES.order_id → PO.order_id` 全部读回；上游 Ossie 导出产生 2 个对象映射。生成的语义模型、本体、映射文档另经上游验证程序检查。 |
| 故障与越权 | 目标缺失或不匹配、资料变化、未完成走查、重复请求及失效凭据均走预期分支。覆盖只读核对后显式重试、并发请求去重、项目所有权与历史快照隔离。 |
| 工作台操作 | Chromium 覆盖桌面及 390px 窄屏、模型下载、质量门槛、请求响应丢失后的重试与历史记录。此项用可控的 HTTP 服务模拟异常；上表后端联调另行经过真实网关。 |

本次全仓回归通过 Java 351 项、Python 440 项、前端 90 项，共 881 项，无跳过。Java 预览用例使用构造的 10 万行、200 列工作簿，前端生产构建和浏览器操作另行通过。当前验收机没有 Docker 引擎，本次未执行镜像构建或 Compose 部署。

该次测试没有连接企业业务数据库或使用实际财务凭据。正式模型在本部门资料上的语义准确率、长期容量、灾备和容器镜像部署，应以上线环境的验收记录为准。
