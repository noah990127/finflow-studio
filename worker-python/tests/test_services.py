from fastapi.testclient import TestClient

from app.main import app
from app.llm import extract_response_text
from app.models import GenerateContentRequest
from app.services import _generation_prompt


client = TestClient(app)


def test_health_without_api_key() -> None:
    from app.config import settings
    response = client.get("/health")
    assert response.status_code == 200
    assert response.json()["status"] == "online"
    assert response.json()["llmProvider"] == settings.llm_provider


def test_extracts_codex_responses_text() -> None:
    assert extract_response_text({
        "output": [{"type": "message", "content": [{"type": "output_text", "text": "分析完成"}]}]
    }) == "分析完成"


def test_search_keeps_ref_location() -> None:
    response = client.post(
        "/v1/knowledge/search",
        json={
            "query": "毛利率下降",
            "chunks": [
                {
                    "ref_id": "ref-1",
                    "source_name": "经营报告.pdf",
                    "text": "本月毛利率下降，主要受到原材料价格影响。",
                    "location": {"page": 12},
                },
                {
                    "ref_id": "ref-2",
                    "source_name": "预算表.xlsx",
                    "text": "收入完成预算目标。",
                    "location": {"sheet": "汇总"},
                },
            ],
        },
    )
    assert response.status_code == 200
    assert response.json()[0]["ref_id"] == "ref-1"
    assert response.json()[0]["location"]["page"] == 12


def test_dataset_profile_reports_nulls() -> None:
    response = client.post(
        "/v1/datasets/profile",
        json={
            "columns": ["revenue"],
            "rows": [{"revenue": 100}, {"revenue": None}],
        },
    )
    assert response.status_code == 200
    assert response.json()["columns"][0]["null_count"] == 1


def test_generate_content_fails_clearly_without_model(monkeypatch) -> None:
    from app.config import settings
    monkeypatch.setattr(settings, "llm_provider", "disabled")
    response = client.post(
        "/v1/knowledge/generate",
        json={"format": "MERMAID", "requirements": "从左到右", "source_text": "从收入数据得到分析结论"},
    )

    assert response.status_code == 503
    assert "大模型尚未配置" in response.json()["detail"]


def test_financial_report_requests_structured_sections_and_charts() -> None:
    system, _ = _generation_prompt(GenerateContentRequest(
        format="FINANCIAL_REPORT", requirements="生成交互经营报告", source_text="FY2026 收入 100 万元"
    ))

    assert "JSON 顶层字段必须为 sections" in system
    assert "6 至 10 个完整报告章节" in system
    assert "至少 3 个章节" in system


def test_slide_prompt_honors_requested_page_range_and_richer_evidence() -> None:
    system, _ = _generation_prompt(GenerateContentRequest(
        format="PPTX", requirements="期望篇幅：12-14 页", source_text="这里是可核对的财务与政策数据"
    ))

    assert "生成 12 至 14 页正文" in system
    assert "生成 4 至 8 页" not in system
    assert "量化依据、业务影响和决策或动作" in system
    assert "至少40%" in system
