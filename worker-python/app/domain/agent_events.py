from typing import Any, Literal

from pydantic import BaseModel, Field


AgentEventType = Literal[
    "thinking_summary",
    "planning",
    "skill_loading",
    "tool_search",
    "tool_call",
    "executing",
    "observation",
    "plan_updated",
    "waiting_confirmation",
    "generating",
    "retrying",
    "completed",
    "failed",
    "cancelled",
    "content",
]


class AgentEvent(BaseModel):
    type: AgentEventType
    activity_id: str = ""
    status: str = ""
    message: str = ""
    toolName: str = ""
    argumentSummary: str = ""
    resultSummary: str = ""
    error: str = ""
    progress: int = 0
    payload: dict[str, Any] = Field(default_factory=dict)
