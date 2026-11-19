#!/usr/bin/env python3
from __future__ import annotations

import csv
import json
import random
from pathlib import Path

ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "assets"
OUTPUTS = ROOT / "outputs"
random.seed(202609)

regions = [("R01", "华南", "南区组"), ("R02", "华东", "东区组"), ("R03", "华北", "北区组"), ("R04", "西部", "西区组")]
products = []
for family, family_name in [("P1", "云服务"), ("P2", "软件"), ("P3", "专业服务")]:
    for index in range(1, 5):
        code = f"{family}{index:02d}"
        products.append((code, family_name, f"{family_name}{index}线", f"{family_name}{index}产品"))

def write(name, columns, rows):
    ASSETS.mkdir(parents=True, exist_ok=True)
    with (ASSETS / name).open("w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(columns)
        writer.writerows(rows)

write("区域维表.csv", ["region_code", "region_name", "team"], regions)
write("产品维表.csv", ["product_code", "product_l1", "product_l2", "product_l3"], products)

forecast, actual = [], []
for region_code, _, _ in regions:
    for product_code, *_ in products:
        base = random.randint(420, 1450) * 10000
        forecast_margin = round(base * random.uniform(.28, .44), 2)
        forecast_bonus = round(base * random.uniform(.025, .055), 2)
        forecast.append(["2026-08", region_code, product_code, base, forecast_margin, forecast_bonus, round(forecast_margin - forecast_bonus, 2), "2026-08-31 18:00", "预测终稿"])
        revenue_factor = random.uniform(.86, 1.12)
        actual_revenue = round(base * revenue_factor, 2)
        actual_margin = round(actual_revenue * random.uniform(.25, .43), 2)
        actual_bonus = round(actual_revenue * random.uniform(.025, .062), 2)
        actual.append(["2026-08", region_code, product_code, actual_revenue, actual_margin, actual_bonus, round(actual_margin - actual_bonus, 2), "2026-09-03 08:30", "多维数据在线刷新"])

columns = ["period", "region_code", "product_code", "revenue", "gross_margin_amount", "bonus_cost", "gross_margin_ex_bonus", "snapshot_at", "source_system"]
write("预算预测终稿.csv", columns, forecast)
write("实际经营数据_在线刷新快照.csv", columns, actual)

adjustments = []
for index in range(8):
    region_code, *_ = regions[index % len(regions)]
    product_code, *_ = products[(index * 5) % len(products)]
    revenue = random.choice([1, -1]) * random.randint(12, 60) * 10000
    margin = round(revenue * random.uniform(.28, .40), 2)
    bonus = abs(round(revenue * random.uniform(.02, .05), 2))
    adjustments.append([f"ADJ{index + 1:03d}", "2026-08", region_code, product_code, revenue, margin, bonus, round(margin - bonus, 2), "业务确认未入账", f"owner{index % 3 + 1}", "已复核" if index < 6 else "待复核"])
write("未入账补录.csv", ["adjustment_id", "period", "region_code", "product_code", "revenue", "gross_margin_amount", "bonus_cost", "gross_margin_ex_bonus", "reason", "owner", "review_status"], adjustments)

agent_rows = [
    ["华南", "云服务", "重点客户项目验收延后，收入确认可能顺延", "经营问数助手", "需业务复核"],
    ["华东", "软件", "续费折扣高于预测，销毛额承压", "经营问数助手", "已与销售确认"],
    ["西部", "专业服务", "外包成本账单尚未全部入账", "经营问数助手", "需财务复核"],
]
write("外部助手线索_已确认.csv", ["region_name", "product_l1", "signal", "source_agent", "validation_status"], agent_rows)

(ASSETS / "分析口径与复核清单.md").write_text("""# 月度预算预测偏差分析口径

本案例全部业务数据均为模拟。工作流以 2026 年 8 月为分析期，实际口径为在线刷新快照加已复核未入账调整。

## 四项核心指标

1. 收入
2. 销毛额
3. 奖金-成本
4. 销毛额（不含奖金）= 销毛额 - 奖金-成本

## 质量门

- 预测与实际必须使用同一期间、区域和产品末级口径。
- 补录必须保留原因、责任人和复核状态；待复核补录不得进入正式实际数。
- 外部助手返回仅作为线索，必须显示来源和验证状态，不直接改写财务数值。
- 报告中的每个结论应能下钻到区域、产品和原始来源。
""", encoding="utf-8")

summary = {
    "forecast_rows": len(forecast), "actual_rows": len(actual), "adjustment_rows": len(adjustments),
    "period": "2026-08", "regions": len(regions), "products": len(products), "simulated": True,
}
(ASSETS / "样板数据说明.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")

OUTPUTS.mkdir(parents=True, exist_ok=True)
(OUTPUTS / "月度预算预测偏差分析_MVP.html").write_text("""<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>月度预算预测偏差分析 MVP</title><style>*{box-sizing:border-box}body{margin:0;font:14px/1.6 system-ui;color:#20364d;background:#f4f8fc}header{padding:28px 5vw;color:white;background:#1769aa}header h1{margin:0 0 6px;font-size:26px}main{max-width:1180px;margin:auto;padding:24px}.notice{padding:12px 16px;border-left:4px solid #2f80c9;background:#fff}.metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:20px 0}.metric,.panel{padding:18px;border:1px solid #d7e4f0;background:#fff}.metric small,.muted{color:#70859a}.metric strong{display:block;margin-top:8px;font-size:23px}.negative{color:#b34852}.positive{color:#16785b}.grid{display:grid;grid-template-columns:1.25fr .75fr;gap:16px}.bar{height:22px;margin:8px 0;background:#edf3f8}.bar i{display:block;height:100%;background:#438dc8}.steps{display:grid;grid-template-columns:repeat(5,1fr);gap:8px}.steps div{padding:12px;border-top:3px solid #438dc8;background:#fff}table{width:100%;border-collapse:collapse}th,td{padding:9px;border-bottom:1px solid #e2ebf3;text-align:left}th{background:#eef5fb}@media(max-width:760px){.metrics,.grid,.steps{grid-template-columns:1fr}}</style></head><body><header><h1>月度预算预测偏差分析 MVP</h1><div>2026 年 8 月 · 模拟数据 · 从线上刷新、补录到管理报告的可复用样板</div></header><main><div class="notice"><b>样板定位：</b>展示工作流和可追溯口径，不代表真实经营结论。正式报告由工作流读取项目数据后生成。</div><section class="metrics"><div class="metric"><small>收入偏差</small><strong class="negative">-3.8%</strong><span>实际 + 已复核补录 对比预测</span></div><div class="metric"><small>销毛额偏差</small><strong class="negative">-5.2%</strong><span>重点关注华东软件</span></div><div class="metric"><small>奖金-成本偏差</small><strong class="negative">+6.1%</strong><span>正偏差代表成本超预测</span></div><div class="metric"><small>销毛额（不含奖金）</small><strong class="negative">-6.4%</strong><span>剔除奖金后仍承压</span></div></section><div class="grid"><section class="panel"><h2>区域贡献示意</h2><p>华南收入偏差</p><div class="bar"><i style="width:78%"></i></div><p>华东销毛额偏差</p><div class="bar"><i style="width:64%"></i></div><p>西部未入账成本风险</p><div class="bar"><i style="width:38%"></i></div><p class="muted">点击正式报告中的区域可继续下钻到产品一级、二级和末级。</p></section><section class="panel"><h2>需要人判断</h2><table><tr><th>事项</th><th>状态</th></tr><tr><td>2 条未入账补录</td><td>待复核</td></tr><tr><td>华南项目验收顺延</td><td>需业务确认</td></tr><tr><td>华东续费折扣</td><td>已确认</td></tr><tr><td>西部外包账单</td><td>需财务确认</td></tr></table></section></div><h2>端到端工作流</h2><section class="steps"><div><b>1 取数</b><br>预测终稿与在线实际快照</div><div><b>2 补录</b><br>未入账数据及复核状态</div><div><b>3 统一口径</b><br>四项指标、区域与产品层级</div><div><b>4 分析</b><br>贡献度、异常和解释线索</div><div><b>5 输出</b><br>可下钻 HTML 管理报告</div></section></main></body></html>""", encoding="utf-8")
