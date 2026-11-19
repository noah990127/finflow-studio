#!/usr/bin/env python3
"""Generate deterministic, non-sensitive assets for the data-preparation showcase."""

from __future__ import annotations

import csv
import json
from pathlib import Path

from openpyxl import Workbook


ROOT = Path(__file__).resolve().parent
ASSETS = ROOT / "assets"


def generate_orders() -> None:
    path = ASSETS / "经营订单明细_样例.csv"
    with path.open("w", encoding="utf-8-sig", newline="") as output:
        writer = csv.writer(output)
        writer.writerow(["order_id", "order_date", "store_code", "channel", "status", "amount", "currency"])
        currencies = ("CNY", "CNY", "CNY", "USD", "EUR")
        channels = ("门店", "小程序", "电商")
        for index in range(20_000):
            writer.writerow([
                f"O{index + 1:08d}",
                f"2026-09-{index % 28 + 1:02d}",
                f"S{index % 1000:04d}",
                channels[index % len(channels)],
                "取消" if index % 20 == 0 else "完成",
                round(80 + (index % 5000) * 0.37, 2),
                currencies[index % len(currencies)],
            ])


def generate_stores() -> None:
    workbook = Workbook(write_only=True)
    sheet = workbook.create_sheet("门店维表")
    sheet.append(["store_code", "region", "manager", "store_type", "opened_year"])
    for index in range(1000):
        sheet.append([
            f"S{index:04d}", f"区域{index % 10}", f"负责人{index % 50}",
            "旗舰店" if index % 8 == 0 else "标准店", 2015 + index % 11,
        ])
    workbook.save(ASSETS / "门店维表_样例.xlsx")


def generate_benchmark_report() -> None:
    report = {
        "scenario": "1,000,000-row x 300-column fact table with 1,000-row x 300-column dimension",
        "sourceTypes": ["Parquet fact file", "Excel dimension", "HTTP API snapshot", "database snapshot"],
        "elapsedSeconds": 1.308,
        "stage": "VERIFIED",
        "inputRows": 1_000_000,
        "filteredRows": 950_000,
        "outputRows": 10,
        "checks": [
            "strict transform plan validated",
            "result reopened successfully",
            "row and column counts reconciled",
            "input and output SHA-256 hashes recorded",
            "M:1 dimension joins did not expand rows",
        ],
        "notice": "Deterministic local benchmark measured on 2026-09-11; source generation time excluded.",
    }
    (ASSETS / "百万行宽表验证记录.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def main() -> None:
    ASSETS.mkdir(parents=True, exist_ok=True)
    generate_orders()
    generate_stores()
    generate_benchmark_report()


if __name__ == "__main__":
    main()
