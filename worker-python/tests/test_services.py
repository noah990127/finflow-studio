import json

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


def test_generate_content_falls_back_without_model(monkeypatch) -> None:
    from app.config import settings
    monkeypatch.setattr(settings, "llm_provider", "disabled")
    response = client.post(
        "/v1/knowledge/generate",
        json={"format": "PPTX", "requirements": "生成12页中文管理层汇报", "source_text": "收入增长来自云业务。[1]\n经营现金流保持稳定。[2]"},
    )

    assert response.status_code == 200
    assert response.json()["mode"] == "local-extractive-fallback"
    assert len(json.loads(response.json()["content"])["slides"]) == 12


def test_stream_generate_content_falls_back_without_model(monkeypatch) -> None:
    from app.config import settings
    monkeypatch.setattr(settings, "llm_provider", "disabled")

    response = client.post(
        "/v1/knowledge/generate/stream",
        json={"format": "HTML", "requirements": "生成经营分析", "source_text": "收入增长来自云业务。[1]"},
    )

    assert response.status_code == 200
    events = [json.loads(line) for line in response.text.splitlines()]
    assert events[-1]["type"] == "complete"
    assert events[-1]["mode"] == "local-extractive-fallback"
    assert events[-1]["content"] == "收入增长来自云业务。"


def test_research_fetch_rejects_private_network_urls() -> None:
    response = client.post("/v1/research/fetch", json={"url": "http://127.0.0.1:8080/private"})

    assert response.status_code == 422


def test_json_research_payload_exposes_nested_values_as_tables() -> None:
    from app.research import _json_tables

    tables = _json_tables({"amount": 1, "base": "EUR", "rates": {"USD": 1.17, "CNY": 8.42}})

    assert any(table["title"] == "rates" for table in tables)
    rates = next(table for table in tables if table["title"] == "rates")
    assert ["USD", "1.17"] in rates["rows"]


def test_html_reader_preserves_table_rows() -> None:
    from app.research import _ReadableText

    parser = _ReadableText()
    parser.feed("<html><title>Rates</title><table><tr><th>Currency</th><th>Rate</th></tr>"
                "<tr><td>USD</td><td>1.17</td></tr></table></html>")

    assert parser.title == "Rates"
    assert parser.tables[0]["rows"] == [["Currency", "Rate"], ["USD", "1.17"]]


def test_json_file_preview_renders_structured_tables(tmp_path) -> None:
    from app.document_preview import preview_document

    source = tmp_path / "rates.json"
    source.write_text('{"provenance":{"sourceUrl":"https://example.com"},"tables":['
                      '{"title":"rates","rows":[["currency","rate"],["USD",1.17]]}]}')

    preview = preview_document(source, source.name)

    assert preview.kind == "data"
    assert preview.pages[0].blocks[0].text == "rates"
    assert preview.pages[0].blocks[1].rows[1] == ["USD", "1.17"]


def test_financial_report_requests_structured_sections_and_charts() -> None:
    system, _ = _generation_prompt(GenerateContentRequest(
        format="FINANCIAL_REPORT", requirements="生成交互经营报告", source_text="FY2026 收入 100 万元"
    ))

    assert "JSON 顶层字段必须为 sections" in system
    assert "6 至 10 个完整报告章节" in system
    assert "至少 3 个章节" in system


def test_analysis_uses_requirements_as_user_prompt_and_connected_material_as_context() -> None:
    system, user = _generation_prompt(GenerateContentRequest(
        format="ANALYSIS", requirements="比较三家公司现金流并解释差异", source_text="公司A经营现金流为100"
    ))

    assert "只分析连接到当前节点的资料" in system
    assert user == "用户的分析要求：\n比较三家公司现金流并解释差异\n\n连接到节点的资料：\n公司A经营现金流为100"


def test_slide_prompt_honors_requested_page_range_and_richer_evidence() -> None:
    system, _ = _generation_prompt(GenerateContentRequest(
        format="PPTX", requirements="期望篇幅：12-14 页", source_text="这里是可核对的财务与政策数据"
    ))

    assert "生成 12 至 14 页正文" in system
    assert "生成 4 至 8 页" not in system
    assert "量化依据、业务影响和决策或动作" in system
    assert "至少40%" in system
