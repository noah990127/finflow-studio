# 隐性知识抽取与并发验收

从仓库根目录运行以下命令。使用已安装 worker 依赖的 Python 3.12 环境。评测数据可以保存在仓库外。抽取会将指定的原文发给 worker 配置的模型服务，输出也包含原文证据。

## 独立业务标注

内置 `documents.json` 含两个虚构项目、四份短文。五个标注目标涵盖两个互相矛盾的门槛、跨文件待核对项、未定义单位，以及含执行日期和严格边界的正常规则。每份文件记载的门槛都可独立抽取，冲突的执行口径应单列待审核。正式验收请选取拟上线业务的留出材料，让熟悉业务的专家在看到预测结果前制作金标，并记录适用版本、标注分歧和最终裁定。

1. 按 `documents.json` 建立独立项目、业务范围、原文和版本。运行 `python scripts/evaluate-knowledge.py fingerprint --documents /path/documents.json`，将输出作为金标文件的 `corpus_digest`。
2. 按 `gold.json` 先行固定每个项目的验收章节和完整目标。只有所列章节纳入分母，重复和额外预测都计入。每个目标指定 `kind`（`assert` 或 `abstain`）、适用章节、需要时限制的状态、必需字段和每份来源的最小逐字引文。字段路径支持点分键，`*` 遍历列表。每组允许值接受同义的精确表示，完整数组可约束全部输出或例外。未标注字段的业务含义需由专家核对。
3. 配置项目已有的 worker 模型参数，从原文独立运行所有分页抽取和跨文件对齐。抽取阶段只读取 `--documents`。服务失败返回非零退出码，只有所有项目都完成后才生成预测文件。生成原始数据和每轮输出时请使用独立路径。

```bash
python scripts/evaluate-knowledge.py extract --documents /path/documents.json --output predictions.json
python scripts/evaluate-knowledge.py score --documents /path/documents.json --gold /path/gold.json --predictions predictions.json --output exact.json
python scripts/evaluate-knowledge.py blind --documents /path/documents.json --gold /path/gold.json --predictions predictions.json --output blind.json --review-template decisions.json
```

将 `blind.json` 发给评审人核对原文、字段、例外与互相矛盾的结论。盲审包不含供应商、模型名称或自动得分。协调人根据预先固定的金标和评审意见，在 `decisions.json` 填写评审身份、每条预测匹配的 `gold_id`（误报填 `null`）及逐条理由。任何省略的预测都会导致评测退出。专家可认可不同的表达；证据位置、来源版本、金标引文、确定或待定状态仍需通过程序核对。

```bash
python scripts/evaluate-knowledge.py score --documents /path/documents.json --gold /path/gold.json --predictions predictions.json --adjudication decisions.json --output adjudicated.json --require assert.precision=0.95 --require abstain.recall=0.95
```

阈值由业务方在验收前确定。上述数值仅演示命令格式。报告给出逐项目漏报、误报和无效证据；整体、确定答复、显式暂缓各自报告匹配数、预测数、应有数、精确率、召回率和 F1。每条匹配须有真实原文、准确字符区间并覆盖全部必需来源。一对一匹配防止重复得分。空分母记为 `null`；配置门槛后，未达值或空分母均返回退出码 2。未表达判断计漏报。证据完整率仅检验逐字定位，语义由标注字段与专家复核评估。应用未输出概率，这组指标衡量实际暂缓行为。保留预测文件中的 `alignment_providers` 以区分模型辅助与本地对齐。

## 带身份认证的 HTTP 并发验收

使用专用验收部署，按项目配置启动 Java、Python worker 和目标数据库。默认八个并发客户端，每个客户端建一个独立项目。各客户端创建不同记录，重放原请求，读取持久化状态并比对项目全部记录。最后，各客户端竞争同一项目的同一版本，校验其余写入收到 409。提供第二个账号后，附加跨所有者读写拒绝和原工作区完整性检查。运行结束会通过项目删除接口归档本轮创建的项目；该接口执行软删除，数据库仍保留测试记录。

```bash
# 通过环境预先设置 FINFLOW_EVAL_PASSWORD 和 FINFLOW_EVAL_OTHER_PASSWORD。
python scripts/evaluate-knowledge-load.py --base-url http://127.0.0.1:18100 --user default --other-user test1 --label postgres-staging --concurrency 8 --rounds 6 --output load.json
```

报告以单次 HTTP 请求计数；按操作给出最近秩 p50、p95、p99 和最大延迟。总吞吐量包含符合预期的拒绝与竞争响应。计时起点为登录和创建项目后，含首次工作区写入，终点为竞争后读回校验。负载不调用模型。结果随数据量、实例和部署环境变化；应单独验证大文件模型耗时、固定时长稳定性及目标规模容量。
