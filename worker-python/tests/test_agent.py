from pathlib import Path

import pytest

from app.agent import OpenTaskRequest, load_skills, run_open_task_stream
from app.agent import runtime


def test_loads_reusable_skills_from_markdown() -> None:
    skills = load_skills(str(Path(__file__).parents[1] / "skills"))

    assert {skill.name for skill in skills} == {
        "financial-analysis",
        "annual-report-analysis",
        "document-data-extraction",
        "excel-analysis",
        "research-and-evidence",
        "deliverable-generation",
        "workflow-authoring",
        "workspace-operations",
    }
    assert all(skill.instructions for skill in skills)
    assert all("Human Confirmation Boundary" in skill.instructions for skill in skills)


@pytest.mark.asyncio
async def test_open_task_has_governed_local_fallback(monkeypatch) -> None:
    monkeypatch.setattr(runtime, "_model", lambda: None)
    monkeypatch.setattr(runtime.settings, "llm_provider", "none")
    request = OpenTaskRequest(task="总结当前资料", project_id="project-1", source_context="收入同比增长 12%。")

    events = [event async for event in run_open_task_stream(request)]

    assert events[0]["type"] == "thinking_summary"
    assert events[1]["type"] == "planning"
    assert events[-1]["type"] == "completed"
    assert events[-1]["mode"] == "local-governed"
    assert "收入同比增长" in events[-1]["content"]


@pytest.mark.asyncio
async def test_open_task_stream_separates_generation_from_final_result(monkeypatch) -> None:
    monkeypatch.setattr(runtime, "_model", lambda: None)
    monkeypatch.setattr(runtime, "llm", type("StubLlm", (), {"configured": False})())
    request = OpenTaskRequest(task="生成摘要", project_id="project-1", source_context="现金流改善。")

    events = [event async for event in run_open_task_stream(request)]
    event_types = [event["type"] for event in events]

    assert "generating" in event_types
    assert event_types[-1] == "completed"
