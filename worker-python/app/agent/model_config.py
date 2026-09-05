from urllib.parse import urlsplit

from pydantic import BaseModel, Field, SecretStr, field_validator


class AgentModelConfig(BaseModel):
    base_url: str = Field(max_length=2000)
    model: str = Field(min_length=1, max_length=200)
    api_key: SecretStr = Field(repr=False)

    @field_validator("base_url")
    @classmethod
    def valid_url(cls, value):
        url = urlsplit(value)
        local = url.hostname in {"localhost", "127.0.0.1", "::1"}
        if (not url.hostname or url.username or url.password or url.query or url.fragment
                or not (url.scheme == "https" or (url.scheme == "http" and local))
                or url.hostname.startswith("169.254.") or url.hostname == "metadata.google.internal"):
            raise ValueError("Invalid model base URL")
        return value.rstrip("/")


def custom_model(config: AgentModelConfig, timeout=120):
    from langchain_openai import ChatOpenAI

    return ChatOpenAI(model=config.model, base_url=config.base_url, api_key=config.api_key.get_secret_value(),
                      timeout=timeout, max_retries=0, use_responses_api=False)


async def test_model(config: AgentModelConfig):
    import asyncio
    from langchain_core.messages import HumanMessage

    probe = {"type": "function", "function": {"name": "connection_check", "description": "Verify tool calling",
             "parameters": {"type": "object", "properties": {}, "required": []}}}
    try:
        model = custom_model(config, timeout=25).bind_tools([probe], tool_choice="connection_check")
        response = await asyncio.wait_for(model.ainvoke([HumanMessage(content="Call connection_check once.")]), 30)
        supported = any(call.get("name") == "connection_check" for call in response.tool_calls)
        return {"success": supported, "message": "连接成功，支持工具调用" if supported
                else "模型已连接，但未通过工具调用测试，请选择支持工具调用的模型"}
    except Exception:
        # Provider errors can contain request headers or echoed credentials.
        return {"success": False, "message": "连接失败，请检查 URL、API Key、模型名称及工具调用支持"}
