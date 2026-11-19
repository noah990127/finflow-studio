"""Inference-only execution: no agent loop, tools, search, or repair fallback."""
import hashlib
import json

from pydantic import BaseModel, Field
from .agent.model_config import AgentModelConfig, custom_model
from .config import settings
from .llm import llm


class FixedModelRequest(BaseModel):
    configuration: AgentModelConfig | None = None
    snapshot: dict = Field(default_factory=dict)
    prompt: str = Field(default="", max_length=50000)
    inputs: dict[str, str] = Field(default_factory=dict)


def model_snapshot(configuration=None):
    if configuration:
        value = {"provider": "custom", "model": configuration.model, "baseUrl": configuration.base_url}
    elif llm.provider == "deepseek":
        value = {"provider": "deepseek", "model": settings.deepseek_chat_model, "baseUrl": settings.deepseek_base_url}
    elif llm.provider == "codex":
        value = {"provider": "codex", "model": settings.openai_model, "baseUrl": settings.openai_base_url,
                 "reasoning": settings.openai_reasoning_effort, "maxOutputTokens": settings.openai_max_output_tokens}
    elif llm.provider == "codex-cli":
        model = llm._codex_cli_model()
        if not model:
            raise ValueError("固定模型工序需要明确的Codex模型名称，不能使用随环境变化的默认模型")
        value = {"provider": "codex-cli", "model": model,
                 "reasoning": settings.codex_cli_reasoning_effort, "forceHttp": settings.codex_cli_force_http,
                 "executor": "isolated-text-no-tools-v1"}
    else:
        raise ValueError("固定模型工序需要无工具的模型 API，请在会话中配置模型 API；不会回退到 Agent CLI")
    value["executorVersion"] = 1
    value["tools"] = []
    value["hash"] = hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()
    return value


async def execute_model(request: FixedModelRequest):
    if model_snapshot(request.configuration) != request.snapshot:
        raise ValueError("模型配置已变化，请通过 Agent 发布新的实现版本")
    if not request.prompt.strip():
        raise ValueError("固定提示词不能为空")
    if sum(len(v) for v in request.inputs.values()) > 500000:
        raise ValueError("输入文本超过固定模型工序上限，不会自动截断")
    user = json.dumps({"inputs": request.inputs}, ensure_ascii=False)
    if request.configuration:
        from langchain_core.messages import SystemMessage, HumanMessage
        response = await custom_model(request.configuration).ainvoke([
            SystemMessage(content=request.prompt), HumanMessage(content=user)])
        if response.tool_calls:
            raise ValueError("固定模型工序不允许工具调用")
        text = response.content
    else:
        text = await llm.complete(request.prompt, user)
    if not isinstance(text, str) or not text.strip():
        raise ValueError("模型未返回文本，不生成空产物")
    return {"content": text, "modelSnapshot": request.snapshot}
