# FinBTP Studio Docker 完整部署指南

> 适用范围：当前仓库 `dev` / `main` 分支
>
> 部署形态：单机 Docker Compose，默认单用户
>
> 内置案例：英伟达近五年经营状况分析、2026 年全球税务政策趋势

## 1. 部署后的服务

基础部署会启动以下服务：

| Compose 服务 | 固定版本或构建来源 | 默认访问地址 | 用途 |
|---|---|---|---|
| `frontend` | `frontend/Dockerfile` | `http://服务器IP:5173` | Vue 工作台及 API 反向代理 |
| `java-api` | JDK 21，`backend-java/Dockerfile` | `http://服务器IP:8080` | 项目、资源、工作流、调度、文件和 Office 接入 |
| `python-worker` | Python 3.12，`worker-python/Dockerfile` | 容器内 `8001` | AI、文档解析、数据处理、图表和输出件生成 |
| `postgres` | `postgres:17-alpine` | 仅容器内访问 | 平台元数据以及两套案例的演示表 |
| `minio` | `RELEASE.2025-07-23T15-54-02Z` | `9000` / `9001` | 上传文件、解析产物和输出件的对象存储 |
| `minio-init` | `RELEASE.2025-07-21T05-28-08Z` | 不对外开放 | 首次创建 `finflow` Bucket，成功后自动退出 |
| `onlyoffice-documentserver` | `8.3.3` | `http://服务器IP:8082` | Word、Excel、PowerPoint 在线查看和编辑 |

加载案例时还会启动：

| Compose 服务 | 默认地址 | 用途 |
|---|---|---|
| `demo-api` | 仅容器内 `8011` | 为英伟达财务数据和全球税务趋势提供演示数据服务 |

MinIO 不是数据库。它用于保存 PPTX、DOCX、PDF、CSV 等二进制对象；PostgreSQL 保存这些对象的名称、所属项目、版本、工作流和运行记录。两者必须一起备份。

## 2. 部署前准备

建议配置：

- 功能验证：4 核 CPU、8 GB 内存、40 GB 可用磁盘；
- 日常单用户：8 核 CPU、16 GB 内存、100 GB 以上 SSD；
- 大文件、Office 并发或千万行抽取：建议 16 GB 以上内存，并为对象和临时文件准备独立数据盘；
- Docker Engine 24+，Docker Compose v2+；当前已验证目标版本可使用 Docker Engine 29.2.1 / API 1.53 / Compose v5.0.2；
- 服务器需要能访问配置的 LLM API；如果镜像源受限，请使用第 11 节的离线部署方式。

当前版本没有登录和多用户权限，不要直接暴露到公网。至少使用企业内网、VPN、防火墙白名单或统一登录网关保护服务。

### aarch64 服务器说明

三个业务镜像、PostgreSQL 和 MinIO 可按 `linux/arm64` 构建或拉取。当前固定的 ONLYOFFICE Document Server 8.3.3 使用 `linux/amd64`，Compose 已显式声明该平台；aarch64 Linux 必须预先启用 amd64 容器模拟，否则 ONLYOFFICE 无法启动。模拟运行会比原生镜像更慢，生产环境优先使用独立 x86_64 Office 节点。

先验证平台能力：

```bash
docker run --rm --platform linux/amd64 alpine:3.21 uname -m
```

命令成功并输出 `x86_64` 后，才继续部署 ONLYOFFICE。

## 3. 获取代码

```bash
git clone https://github.com/noah990127/finflow-studio.git
cd finflow-studio
git checkout main
```

验证目录至少包含：

```text
frontend/
backend-java/
worker-python/
examples/cases/
docker-compose.yml
.env.example
```

## 4. 配置 `.env`

```bash
cp .env.example .env
chmod 600 .env
```

生成三个不同的随机密钥：

```bash
openssl rand -hex 32
openssl rand -hex 32
openssl rand -hex 32
```

编辑 `.env`，Docker 部署至少修改以下内容：

```dotenv
# 平台数据库，同时也是案例演示数据库
DATABASE_PASSWORD=替换为第一个强密码
FINFLOW_DEMO_DATABASE_URL=jdbc:postgresql://postgres:5432/finflow
FINFLOW_DEMO_DATABASE_USERNAME=finflow
FINFLOW_DEMO_DATABASE_PASSWORD=替换为与DATABASE_PASSWORD相同的值

# 对象存储
MINIO_ROOT_USER=finflow
MINIO_ROOT_PASSWORD=替换为第二个强密码

# Office 服务；Java 与 ONLYOFFICE 必须使用同一个值
ONLYOFFICE_JWT_SECRET=替换为第三个强密码
# 默认通过 Studio 的 /office 同源代理访问，无需填写服务器 IP
FINFLOW_OFFICE_DOCUMENT_SERVER_URL=/office
ONLYOFFICE_PLATFORM=linux/amd64

# 容器内案例 API 地址
FINFLOW_NVIDIA_API_URL=http://demo-api:8011/api/v1/companies/nvidia/five-year-financials
FINFLOW_GLOBAL_TAX_API_URL=http://demo-api:8011/api/v1/tax/2026-policy-trends

# 服务器提供的模型 API
FINFLOW_LLM_PROVIDER=codex
OPENAI_API_KEY=替换为服务器上的APIKey
OPENAI_BASE_URL=https://api.openai.com/v1
OPENAI_MODEL=gpt-5.6-sol
OPENAI_REASONING_EFFORT=medium
OPENAI_MAX_OUTPUT_TOKENS=4000
```

`FINFLOW_OFFICE_DOCUMENT_SERVER_URL` 默认保持 `/office`。前端会把该路径转发给 ONLYOFFICE，因此本机、远程 IP 和 HTTPS 域名都无需改动，也不会出现浏览器误连自身 `localhost:8082` 的问题。只有把 ONLYOFFICE 独立部署在另一网关或域名时，才改为浏览器可访问的绝对 HTTPS 地址。

若服务器使用 OpenAI 兼容的内部模型网关，只需替换：

```dotenv
OPENAI_BASE_URL=https://你的内部模型网关/v1
OPENAI_API_KEY=你的内部Key
OPENAI_MODEL=网关支持的模型名
```

密钥只写入服务器 `.env`，不要提交到 Git。

## 5. 构建并启动全部服务

同时启动平台与案例服务：

```bash
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml config
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml up --build -d
```

查看启动状态：

```bash
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml ps
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml logs -f --tail=200
```

第一次启动 ONLYOFFICE 可能需要几分钟。`minio-init` 显示 `Exited (0)` 表示 Bucket 创建成功，不是故障。

## 6. 初始化两个案例

### 6.1 创建案例数据库表

```bash
for file in examples/cases/*/postgres-init.sql; do
  docker compose exec -T postgres psql -U finflow -d finflow < "$file"
done
```

脚本使用 `CREATE SCHEMA IF NOT EXISTS` 和可重复写入方式，可再次执行。确认案例表可见：

```bash
docker compose exec postgres psql -U finflow -d finflow -c '\dt demo_examples.*'
```

### 6.2 导入项目、文件、连接、工作流和初始输出件

导入脚本只使用 Python 标准库，服务器安装 Python 3 即可：

```bash
python3 examples/cases/import_cases.py --api http://127.0.0.1:8080 --check
python3 examples/cases/import_cases.py --api http://127.0.0.1:8080
```

也可以只导入一个案例：

```bash
python3 examples/cases/import_cases.py --api http://127.0.0.1:8080 --case nvidia
python3 examples/cases/import_cases.py --api http://127.0.0.1:8080 --case global-tax-2026
```

导入器可重复执行：已有项目、文件和连接会复用，案例工作流会更新为仓库中的当前定义。

导入完成后，项目面板应只出现这两套内置案例：

1. 英伟达近五年经营状况分析；
2. 2026 年全球税务政策趋势。

## 7. 访问地址

```text
FinBTP Studio 工作台：http://服务器IP:5173
Java 健康状态：http://服务器IP:8080/actuator/health
ONLYOFFICE：http://服务器IP:8082/healthcheck
MinIO 控制台：http://服务器IP:9001
```

MinIO 控制台仅供管理员维护；生产环境不要向普通用户开放 Java 8080、MinIO 9000/9001 和案例 API。

## 8. 部署验收

### 8.1 服务健康检查

```bash
curl -fsS http://127.0.0.1:5173/
curl -fsS http://127.0.0.1:8080/actuator/health
curl -fsS http://127.0.0.1:8080/api/system/status
curl -fsS http://127.0.0.1:8082/healthcheck
curl -fsS http://127.0.0.1:9000/minio/health/live
docker compose exec demo-api python -c "import urllib.request; print(urllib.request.urlopen('http://localhost:8011/health').read().decode())"
```

### 8.2 案例验收

1. 打开工作台项目面板，确认两个案例存在；
2. 打开案例中的 PDF、Word、CSV 和网页资料，确认查看态可用；
3. 打开数据库/API 连接，确认配置和数据预览可用；
4. 打开工作流，确认节点、连线、自动排布、手工运行和执行历史可用；
5. 执行工作流，确认前台能看到流式进度；
6. 打开交互报告并切换指标概览、自主分析和数据明细；
7. 打开 PPTX/DOCX，确认 ONLYOFFICE 查看与编辑可用；
8. 点击输出件引用，确认按需弹出源文件与具体片段；
9. 下载资料和输出件，确认文件内容完整；
10. 重启服务后确认项目、工作流和文件仍然存在。

## 9. ONLYOFFICE 网络配置

Office 编辑依赖三条路径：

```text
浏览器 -> Studio /office -> onlyoffice-documentserver
Java API -> http://onlyoffice-documentserver
ONLYOFFICE -> http://java-api:8080
```

检查配置和互通：

```bash
curl -fsS http://127.0.0.1:8082/healthcheck
curl -I http://服务器IP:5173/office/web-apps/apps/api/documents/api.js
docker compose exec java-api env | grep FINFLOW_OFFICE
docker compose exec onlyoffice-documentserver curl -fsS http://java-api:8080/actuator/health
```

若页面显示“ONLYOFFICE Document Server 尚未启动”：

1. 确认 `onlyoffice-documentserver` 为 healthy；
2. 确认 `.env` 中 `FINFLOW_OFFICE_DOCUMENT_SERVER_URL=/office`，并能通过 Studio 地址访问 `/office/web-apps/apps/api/documents/api.js`；
3. 确认 Java 与 ONLYOFFICE 使用相同的 `ONLYOFFICE_JWT_SECRET`；
4. 修改 `.env` 后重建 Java 和 Office 容器；
5. HTTPS 工作台必须搭配 HTTPS Office 地址，浏览器会阻止混合内容。

```bash
docker compose up -d --force-recreate java-api onlyoffice-documentserver
docker compose logs --tail=300 java-api onlyoffice-documentserver
```

## 10. 日常运维、备份与升级

停止服务但保留数据：

```bash
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml down
```

启动现有容器：

```bash
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml up -d
```

修改模型配置后：

```bash
docker compose up -d --force-recreate python-worker
```

更新业务代码后：

```bash
git pull --ff-only
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml up --build -d
```

备份 PostgreSQL：

```bash
mkdir -p backups
docker compose exec -T postgres pg_dump -U finflow -d finflow -Fc > backups/finflow.dump
```

还必须备份 MinIO 的 `finflow-objects` 数据卷或其中的 `finflow` Bucket。仅备份 PostgreSQL 会丢失上传文件和输出件；仅备份 MinIO 会丢失项目、版本和工作流关系。

不要在生产环境执行：

```text
docker compose down -v
```

它会删除 PostgreSQL、MinIO、模型和 ONLYOFFICE 数据卷。

## 11. 镜像源受限或完全离线部署

推荐在可联网、与目标服务器同架构的构建机上准备镜像；业务镜像和第三方镜像都要进入离线包。

### 11.1 构建业务与案例镜像

```bash
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml build
```

确认实际镜像名：

```bash
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml images
```

### 11.2 拉取固定版本第三方镜像

```bash
docker pull postgres:17-alpine
docker pull minio/minio:RELEASE.2025-07-23T15-54-02Z
docker pull minio/mc:RELEASE.2025-07-21T05-28-08Z
docker pull --platform linux/amd64 onlyoffice/documentserver:8.3.3
```

### 11.3 导出与传输

将 `docker compose ... images` 输出中的三个业务镜像名，加上四个第三方镜像，一并执行：

```bash
docker save -o finflow-images.tar \
  你的frontend镜像 \
  你的java-api镜像 \
  你的python-worker镜像 \
  finflow-demo-api:1.0.0 \
  postgres:17-alpine \
  minio/minio:RELEASE.2025-07-23T15-54-02Z \
  minio/mc:RELEASE.2025-07-21T05-28-08Z \
  onlyoffice/documentserver:8.3.3
```

同时传输：

```text
finflow-images.tar
docker-compose.yml
examples/cases/docker-compose.cases.yml
examples/cases/nvidia/
examples/cases/global-tax-2026/
examples/cases/import_cases.py
.env.example
```

目标服务器执行：

```bash
docker load -i finflow-images.tar
cp .env.example .env
# 编辑 .env，替换数据库、对象存储、Office 和 LLM 配置
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml up -d --no-build
```

随后按第 6 节初始化两套案例。

## 12. 常见故障

### 页面能打开但 API 返回 502

```bash
docker compose ps java-api python-worker
docker compose logs --tail=200 frontend java-api python-worker
docker compose exec frontend wget -qO- http://java-api:8080/actuator/health
```

### 数据库提示缺少 `DATABASE_PASSWORD`

确认 `.env` 位于 `docker-compose.yml` 同目录，并同时设置：

```dotenv
DATABASE_PASSWORD=你的数据库密码
FINFLOW_DEMO_DATABASE_PASSWORD=同一个数据库密码
```

然后更新相关容器和案例连接：

```bash
docker compose up -d --force-recreate postgres java-api
python3 examples/cases/import_cases.py --api http://127.0.0.1:8080
```

### 案例 API 返回 502

```bash
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml ps demo-api
docker compose -f docker-compose.yml -f examples/cases/docker-compose.cases.yml logs --tail=200 demo-api java-api
```

确认 `.env` 中案例 API 使用 `http://demo-api:8011`，不能在容器连接里写 `127.0.0.1:8011`。

### AI 生成失败

```bash
docker compose exec python-worker env | grep -E 'FINFLOW_LLM_PROVIDER|OPENAI_BASE_URL|OPENAI_MODEL'
docker compose logs --tail=200 python-worker
```

修改模型、地址或 Key 后执行：

```bash
docker compose up -d --force-recreate python-worker
```

### 上传大文件返回 413

若外部还有 Nginx，需要设置：

```nginx
client_max_body_size 510m;
proxy_read_timeout 1800s;
proxy_buffering off;
```

### 查看磁盘占用

```bash
docker system df
docker compose exec minio du -sh /data
docker compose exec onlyoffice-documentserver du -sh /var/log/onlyoffice
```

清理前先完成 PostgreSQL 与 MinIO 的一致性备份，不要直接删除 Compose 数据卷。
