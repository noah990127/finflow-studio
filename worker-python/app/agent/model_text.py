"""Use the configured model for bounded text-only requests outside the tool loop."""
import json
from contextlib import aclosing
from pathlib import Path

from ..config import settings
from ..llm import llm
from .model_config import AgentModelConfig, custom_model


def _opencode_api():
    """Resolve an explicitly configured chat-completions provider without starting a CLI."""
    from .opencode_runtime import configured_providers
    path = Path(settings.opencode_provider_config)
    provider_id, separator, model = settings.opencode_model.partition("/")
    if not path.is_file() or not separator or not model:
        return None
    provider = configured_providers(json.loads(path.read_text(encoding="utf-8"))).get(provider_id, {})
    if provider.get("npm") not in {"@ai-sdk/openai-compatible", "@ai-sdk/deepseek"}:
        return None
    options = provider.get("options", {})
    if set(options) - {"baseURL", "apiKey"}:
        return None
    api_key, url = options.get("apiKey"), options.get("baseURL")
    if not isinstance(api_key, str) or not api_key or api_key.startswith("{") or not url:
        return None
    return AgentModelConfig(base_url=url, api_key=api_key, model=model)


async def _chat(config, system, user):
    reply = await custom_model(config, timeout=20).ainvoke([("system", system), ("human", user)])
    return reply.content


async def complete_text(system: str, user: str, override: AgentModelConfig | None = None):
    if override:
        return await _chat(override, system, user)
    if llm.configured:
        return await llm.complete(system, user)
    if settings.agent_runtime == "opencode":
        from .opencode_runtime import TextInference, run_opencode_stream
        config = _opencode_api()
        if config:
            return await _chat(config, system, user)
        request = TextInference(system + "\n当前用户输入的 JSON 字符串\n" + json.dumps(user, ensure_ascii=False))
        async with aclosing(run_opencode_stream(request)) as stream:
            async for event in stream:
                if event.get("type") == "completed":
                    return event["content"]
    if settings.agent_runtime == "codex-cli" and llm._codex_cli_path():
        return await llm._complete_codex_cli(system, user)
    return None
