from typing import Any, Literal

from pydantic import BaseModel, Field


ToolRisk = Literal["read", "write", "destructive", "export"]


class ToolDefinition(BaseModel):
    id: str
    category: str
    title: str
    description: str
    risk: ToolRisk
    requires_confirmation: bool
    arguments: list[str] = Field(default_factory=list)
    input_schema: dict[str, Any] = Field(default_factory=dict)
    output_schema: dict[str, Any] = Field(default_factory=dict)


class ToolObservation(BaseModel):
    tool_name: str
    status: str
    summary: str
    data: dict[str, Any] = Field(default_factory=dict)
    citations: list[dict[str, Any]] = Field(default_factory=list)
    provenance: dict[str, Any] = Field(default_factory=dict)
