import json

from app.document_preview import preview_document, preview_research


def test_existing_snapshot_is_readable_without_rewriting_data(tmp_path):
    payload = {"sources": [{"title": "新闻", "url": "https://example.com/news", "text": "正文第一段\n正文第二段"}],
               "failedSources": [{"url": "https://example.com/blocked", "error": "空正文"}]}
    for name in ("sources.json", "sources.json.md"):
        path = tmp_path / name
        raw = json.dumps(payload, ensure_ascii=False)
        path.write_text(raw)
        preview = preview_document(path, name)
        assert preview.kind == "research"
        assert preview.research_sources[0]["text"] == "正文第一段\n正文第二段"
        assert preview.research_sources[0]["embedAllowed"] is False
        assert "空正文" in preview.warnings[0]
        assert path.read_text() == raw


def test_search_candidates_are_not_presented_as_captured_body():
    preview = preview_research({"sources": [{"title": "线索", "url": "https://example.com", "why_relevant": "搜索摘要"}]}, "search.json")
    assert preview.research_sources[0]["text"] == ""
    assert preview.research_sources[0]["summary"] == "搜索摘要"


def test_single_snapshot_retains_capture_time_and_embedding_policy():
    preview = preview_research({"url": "https://example.com", "text": "正文", "embed_allowed": True,
                                "captured_at": "2026-11-17T03:00:00Z"}, "page.json")
    assert preview.research_sources[0]["embedAllowed"] is True
    assert preview.research_sources[0]["capturedAt"] == "2026-11-17T03:00:00Z"


def test_other_json_keeps_existing_preview():
    assert preview_research({"sources": [{"name": "业务数据", "amount": 10}]}, "data.json") is None
    assert preview_research({"sections": []}, "report.json") is None
