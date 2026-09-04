from typing import Literal

from pydantic import AliasChoices, Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    service_name: str = "finbtp-worker"
    llm_provider: str = Field(default="codex", validation_alias=AliasChoices("FINFLOW_LLM_PROVIDER", "LLM_PROVIDER"))
    openai_api_key: str = ""
    openai_base_url: str = "https://api.openai.com/v1"
    openai_model: str = "gpt-5.6-sol"
    openai_reasoning_effort: Literal["low", "medium", "high", "xhigh"] = "low"
    openai_max_output_tokens: int = 2500
    codex_cli_path: str = ""
    codex_cli_model: str = "gpt-5.6-sol"
    codex_cli_reasoning_effort: Literal["low", "medium", "high", "xhigh"] = "low"
    codex_cli_force_http: bool = True
    codex_cli_timeout_seconds: float = 240.0
    codex_cli_research_timeout_seconds: float = 60.0
    codex_cli_cooldown_seconds: float = 300.0
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_chat_model: str = "deepseek-v4-flash"
    max_upload_bytes: int = 500 * 1024 * 1024
    max_transform_input_bytes: int = 100 * 1024 * 1024 * 1024
    knowledge_chunk_chars: int = 1200
    knowledge_chunk_overlap: int = 150
    request_timeout_seconds: float = 60.0
    whisper_model: str = "small"
    whisper_device: str = "cpu"
    whisper_compute_type: str = "int8"
    agent_enabled: bool = True
    agent_skills_dir: str = "skills"
    agent_mcp_config: str = ""
    agent_mcp_allowed_tools: str = ""
    agent_max_steps: int = 10
    agent_max_tool_calls: int = 80
    agent_task_timeout_seconds: int = 900
    research_searxng_url: str = ""
    research_allowed_domains: str = ""
    research_max_download_bytes: int = 10 * 1024 * 1024
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()
