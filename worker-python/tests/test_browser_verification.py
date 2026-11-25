from types import SimpleNamespace
from unittest.mock import AsyncMock
import pytest
pytest.importorskip("browser_use")
from app.browser_agent.verification import Outcome, verify_result
from app.browser_agent.interaction import BrowserTaskFailure


@pytest.mark.asyncio
async def test_verification_uses_current_evidence_without_browser_actions():
    browser = SimpleNamespace(take_screenshot=AsyncMock(return_value=b"screenshot"))
    llm = SimpleNamespace(ainvoke=AsyncMock(return_value=SimpleNamespace(completion=Outcome(verified=True, evidence="已发送记录匹配"))))
    assert await verify_result(browser, llm, "send", {"recipient": "example"}) == "已发送记录匹配"
    assert "example" in str(llm.ainvoke.call_args)


@pytest.mark.asyncio
@pytest.mark.parametrize("result", [None, Outcome(verified=False, evidence="没有成功证据"), Outcome(verified=True, evidence="")])
async def test_unknown_result_is_not_reported_success(result):
    browser = SimpleNamespace(take_screenshot=AsyncMock(return_value=b"screenshot"))
    llm = SimpleNamespace(ainvoke=AsyncMock(return_value=SimpleNamespace(completion=result)))
    with pytest.raises(BrowserTaskFailure, match="不要重复提交"):
        await verify_result(browser, llm, "send", {})


@pytest.mark.asyncio
async def test_verification_includes_current_dom_inputs_and_real_artifact_receipts():
    browser = SimpleNamespace(take_screenshot=AsyncMock(return_value=None),
        get_browser_state_summary=AsyncMock(return_value=SimpleNamespace(dom_state=SimpleNamespace(
            llm_representation=lambda: 'Saved Operations 39 units'))))
    llm = SimpleNamespace(ainvoke=AsyncMock(return_value=SimpleNamespace(completion=Outcome(verified=True,evidence='Saved record matches'))))
    assert await verify_result(browser, llm, 'Save report for Team', {'header':'Operations'},
        outputs={'quantity':39}, artifacts=[{'name':'report.md','mimeType':'text/markdown'}]) == 'Saved record matches'
    prompt = str(llm.ainvoke.call_args)
    assert 'Operations 39' in prompt and 'report.md' in prompt and 'override' in prompt
