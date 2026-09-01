import json

from fastapi import FastAPI, Query
from fastapi.responses import StreamingResponse


app = FastAPI(title="Market and Macro Demo API", version="1.0.0")

MARKET_DATA = [
    ("2026-01", 100.0, 100.0, 7.18, 50.3, 104.2),
    ("2026-02", 97.5, 102.8, 7.21, 49.8, 101.6),
    ("2026-03", 103.2, 105.4, 7.16, 50.6, 106.8),
    ("2026-04", 108.6, 109.7, 7.12, 51.1, 110.5),
    ("2026-05", 105.1, 114.3, 7.09, 50.4, 107.9),
    ("2026-06", 101.8, 119.6, 7.04, 49.9, 103.7),
]

NVIDIA_FIVE_YEAR_FINANCIALS = [
    {
        "fiscal_year": "FY2022", "period_end": "2022-01-30", "revenue_usd_m": 26914,
        "gross_profit_usd_m": 17475, "operating_income_usd_m": 10041, "net_income_usd_m": 9752,
        "operating_cash_flow_usd_m": 9108, "rd_expense_usd_m": 5268, "inventory_usd_m": 2605,
        "data_center_revenue_usd_m": 10613, "gross_margin_pct": 64.9,
    },
    {
        "fiscal_year": "FY2023", "period_end": "2023-01-29", "revenue_usd_m": 26974,
        "gross_profit_usd_m": 15356, "operating_income_usd_m": 4224, "net_income_usd_m": 4368,
        "operating_cash_flow_usd_m": 5641, "rd_expense_usd_m": 7339, "inventory_usd_m": 5159,
        "data_center_revenue_usd_m": 15005, "gross_margin_pct": 56.9,
    },
    {
        "fiscal_year": "FY2024", "period_end": "2024-01-28", "revenue_usd_m": 60922,
        "gross_profit_usd_m": 44301, "operating_income_usd_m": 32972, "net_income_usd_m": 29760,
        "operating_cash_flow_usd_m": 28090, "rd_expense_usd_m": 8675, "inventory_usd_m": 5282,
        "data_center_revenue_usd_m": 47525, "gross_margin_pct": 72.7,
    },
    {
        "fiscal_year": "FY2025", "period_end": "2025-01-26", "revenue_usd_m": 130497,
        "gross_profit_usd_m": 97858, "operating_income_usd_m": 81453, "net_income_usd_m": 72880,
        "operating_cash_flow_usd_m": 64089, "rd_expense_usd_m": 12914, "inventory_usd_m": 10080,
        "data_center_revenue_usd_m": 115186, "gross_margin_pct": 75.0,
    },
    {
        "fiscal_year": "FY2026", "period_end": "2026-01-25", "revenue_usd_m": 215938,
        "gross_profit_usd_m": 153463, "operating_income_usd_m": 130387, "net_income_usd_m": 120067,
        "operating_cash_flow_usd_m": 102718, "rd_expense_usd_m": 18497, "inventory_usd_m": 21403,
        "data_center_revenue_usd_m": 193500, "gross_margin_pct": 71.1,
    },
]


@app.get("/health")
def health() -> dict:
    return {"status": "online", "records": len(MARKET_DATA), "notice": "simulated data"}


@app.get("/api/v1/market/monthly-indicators")
def monthly_indicators(year: int = Query(default=2026)) -> StreamingResponse:
    def rows():
        for period, sector_index, lithium_index, eur_cny, pmi, policy_attention in MARKET_DATA:
            if not period.startswith(str(year)):
                continue
            yield json.dumps({
                "period": period,
                "new_energy_equipment_index": sector_index,
                "lithium_material_price_index": lithium_index,
                "eur_cny": eur_cny,
                "manufacturing_pmi": pmi,
                "policy_attention_index": policy_attention,
                "source": "finflow-simulated-market-api",
            }, ensure_ascii=False) + "\n"

    return StreamingResponse(rows(), media_type="application/x-ndjson")


@app.get("/api/v1/companies/nvidia/five-year-financials")
def nvidia_five_year_financials() -> StreamingResponse:
    def rows():
        for item in NVIDIA_FIVE_YEAR_FINANCIALS:
            yield json.dumps({
                **item,
                "source": "NVIDIA annual reports and SEC EDGAR filings",
                "source_url": "https://investor.nvidia.com/financial-info/annual-reports-and-proxies/default.aspx",
                "latest_10k_url": "https://www.sec.gov/Archives/edgar/data/1045810/000104581026000021/nvda-20260125.htm",
            }, ensure_ascii=False) + "\n"

    return StreamingResponse(rows(), media_type="application/x-ndjson")
