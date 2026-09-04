import asyncio
import json
import re
import uuid
from typing import Any, Sequence

from langchain_core.language_models.chat_models import BaseChatModel
from langchain_core.messages import AIMessage, BaseMessage
from langchain_core.messages.tool import tool_call
from langchain_core.outputs import ChatGeneration, ChatResult
from langchain_core.tools import BaseTool
from langchain_core.utils.function_calling import convert_to_openai_tool
from pydantic import Field

from ..llm import llm


class CodexCliChatModel(BaseChatModel):
    """Expose the local Codex CLI as a tool-calling LangChain chat model."""

    bound_tools: list[dict[str, Any]] = Field(default_factory=list)

    @property
    def _llm_type(self) -> str:
        return "finflow-codex-cli"

    def bind_tools(
        self,
        tools: Sequence[dict[str, Any] | type | BaseTool],
        *,
        tool_choice: str | None = None,
        **kwargs: Any,
    ) -> "CodexCliChatModel":
        del tool_choice, kwargs
        return self.model_copy(update={"bound_tools": [convert_to_openai_tool(item) for item in tools]})

    async def _agenerate(self, messages: list[BaseMessage], stop=None, run_manager=None, **kwargs: Any) -> ChatResult:
        del stop, run_manager, kwargs
        raw = await llm.complete(self._tool_system_prompt(), self._conversation(messages))
        return self._result(raw or "")

    def _generate(self, messages: list[BaseMessage], stop=None, run_manager=None, **kwargs: Any) -> ChatResult:
        del stop, run_manager, kwargs
        return asyncio.run(self._agenerate(messages))

    def _tool_system_prompt(self) -> str:
        tools = json.dumps(self.bound_tools, ensure_ascii=False)
        return f"""你是 DeepAgents 使用的工具调用模型。根据完整对话自主决定下一步。
可用工具如下：{tools}
需要调用工具时只返回 JSON：{{"tool_calls":[{{"name":"工具名","arguments":{{...}}}}]}}。
可以在一次响应中调用多个互不依赖的工具。看到工具结果后继续选择工具，任务规划完成后只返回
JSON：{{"final":{{"summary":"面向用户的简短说明","intent":"意图","selected_skills":["skill"]}}}}。
禁止输出 Markdown，禁止虚构工具名，工具参数必须符合对应 schema。"""

    def _conversation(self, messages: list[BaseMessage]) -> str:
        lines: list[str] = []
        for message in messages:
            role = getattr(message, "type", "message")
            content = getattr(message, "content", "")
            lines.append(f"[{role}] {content}")
            calls = getattr(message, "tool_calls", [])
            if calls:
                lines.append("[assistant_tool_calls] " + json.dumps(calls, ensure_ascii=False))
            if role == "tool":
                lines.append(f"[tool_call_id] {getattr(message, 'tool_call_id', '')}")
        return "\n".join(lines)

    def _result(self, raw: str) -> ChatResult:
        payload = self._json_payload(raw)
        calls = payload.get("tool_calls") if isinstance(payload, dict) else None
        if isinstance(calls, list) and calls:
            parsed = []
            for item in calls:
                if not isinstance(item, dict) or not str(item.get("name", "")).strip():
                    continue
                arguments = item.get("arguments", item.get("args", {}))
                parsed.append(tool_call(
                    name=str(item["name"]),
                    args=arguments if isinstance(arguments, dict) else {},
                    id=str(item.get("id") or uuid.uuid4()),
                ))
            if parsed:
                return ChatResult(generations=[ChatGeneration(message=AIMessage(content="", tool_calls=parsed))])
        final = payload.get("final", payload) if isinstance(payload, dict) else {"summary": raw}
        content = json.dumps(final, ensure_ascii=False) if isinstance(final, dict) else str(final)
        return ChatResult(generations=[ChatGeneration(message=AIMessage(content=content))])

    def _json_payload(self, raw: str) -> dict[str, Any]:
        candidate = re.sub(r"^```(?:json)?\s*", "", raw.strip(), flags=re.IGNORECASE)
        candidate = re.sub(r"\s*```$", "", candidate)
        try:
            value = json.loads(candidate)
        except json.JSONDecodeError:
            match = re.search(r"\{.*\}", candidate, re.DOTALL)
            if not match:
                return {"final": {"summary": candidate or "已完成规划", "intent": "workbench-task"}}
            try:
                value = json.loads(match.group(0))
            except json.JSONDecodeError:
                return {"final": {"summary": candidate, "intent": "workbench-task"}}
        return value if isinstance(value, dict) else {"final": {"summary": str(value), "intent": "workbench-task"}}
