import json

from fastapi import FastAPI
from fastapi.responses import StreamingResponse


app = FastAPI(title="FinFlow Showcase Data API", version="1.0.0")

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

GLOBAL_TAX_2026_TRENDS = [
    {"theme": "OECD Pillar Two", "region": "全球", "effective_period": "2024-2026", "status": "执行与申报基础设施完善", "impact": "15%全球最低税的GIR、安全港与数据口径", "priority": 5, "source_url": "https://www.oecd.org/en/about/news/announcements/2026/05/global-minimum-tax-release-of-a-common-understanding-of-implementing-jurisdictions-and-further-administrative-guidance-to-support-compliance.html"},
    {"theme": "EU CBAM", "region": "欧盟", "effective_period": "2026-01-01", "status": "正式阶段", "impact": "授权申报人、嵌入排放与证书成本", "priority": 5, "source_url": "https://taxation-customs.ec.europa.eu/carbon-border-adjustment-mechanism/cbam-definitive-regime_en"},
    {"theme": "EU ViDA", "region": "欧盟", "effective_period": "2026准备期", "status": "分阶段实施", "impact": "电子发票、数字报告、平台经济和单一VAT登记", "priority": 4, "source_url": "https://taxation-customs.ec.europa.eu/news/vat-digital-age-2026-work-programme-available-2026-05-22_en"},
    {"theme": "US OBBBA corporate provisions", "region": "美国", "effective_period": "2026", "status": "多项企业税条款生效", "impact": "利息扣除、BEAT、CAMT与部分能源激励", "priority": 4, "source_url": "https://www.irs.gov/instructions/i8991"},
    {"theme": "UN tax convention", "region": "全球", "effective_period": "2025-2027谈判", "status": "2026年实质性谈判", "impact": "国际税收合作、服务课税与争议防范", "priority": 3, "source_url": "https://financing.desa.un.org/unfcitc"},
]


@app.get("/health")
def health() -> dict:
    return {
        "status": "online",
        "records": len(NVIDIA_FIVE_YEAR_FINANCIALS) + len(GLOBAL_TAX_2026_TRENDS),
        "notice": "showcase data",
    }


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


@app.get("/api/v1/tax/2026-policy-trends")
def global_tax_2026_trends() -> StreamingResponse:
    return StreamingResponse((json.dumps(item, ensure_ascii=False) + "\n" for item in GLOBAL_TAX_2026_TRENDS), media_type="application/x-ndjson")
