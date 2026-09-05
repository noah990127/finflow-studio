from unittest.mock import AsyncMock

import pytest
from langchain_core.messages import AIMessage
from app.agent.model_config import AgentModelConfig, custom_model, test_model as probe_model
from app.agent import model_config, runtime


def config():
    return AgentModelConfig(base_url="https://model.example/v1", model="user-model", api_key="test-secret")


def test_custom_client_is_isolated_from_defaults():
    before = runtime.settings.llm_provider
    model = custom_model(config())
    assert model.model_name == "user-model"
    assert model.openai_api_base == "https://model.example/v1"
    assert model.openai_api_key.get_secret_value() == "test-secret"
    assert runtime.settings.llm_provider == before
    assert "test-secret" not in repr(config())


@pytest.mark.asyncio
async def test_probe_checks_tool_calls_and_redacts_provider_errors(monkeypatch):
    class Probe:
        def bind_tools(self, *args, **kwargs):
            return self
        ainvoke = AsyncMock(return_value=AIMessage(content="", tool_calls=[{"id": "1", "name": "connection_check", "args": {}}]))
    monkeypatch.setattr(model_config, "custom_model", lambda *args, **kwargs: Probe())
    assert (await probe_model(config()))["success"]
    Probe.ainvoke = AsyncMock(return_value=AIMessage(content="No tools"))
    assert not (await probe_model(config()))["success"]
    Probe.ainvoke = AsyncMock(side_effect=RuntimeError("echo test-secret"))
    result = await probe_model(config())
    assert not result["success"]
    assert "test-secret" not in str(result)


@pytest.mark.parametrize("url", ["file:///tmp/key", "https://secret@host/v1", "http://remote/v1", "https://host/v1?key=test", "https://169.254.169.254/"])
def test_rejects_unsafe_urls(url):
    with pytest.raises(ValueError):
        AgentModelConfig(base_url=url, model="model", api_key="secret")


@pytest.mark.asyncio
async def test_custom_model_works_without_a_configured_default(monkeypatch):
    monkeypatch.setattr(runtime, "llm", type("Unconfigured", (), {"configured": False})())
    monkeypatch.setattr(runtime, "custom_model", lambda _: (_ for _ in ()).throw(RuntimeError("custom selected")))
    request = runtime.AgentPlanRequest(goal="Hello", page="home", capabilities=[], model_config=config().model_dump())
    with pytest.raises(RuntimeError, match="custom selected"):
        await runtime.plan_with_agent(request)
