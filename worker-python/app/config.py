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
    # Empty means: inherit the desktop Codex default model when local config exists.
    codex_cli_model: str = ""
    codex_cli_reasoning_effort: Literal["low", "medium", "high", "xhigh"] = "low"
    codex_cli_force_http: bool = False
    codex_cli_timeout_seconds: float = 240.0
    deepseek_api_key: str = ""
    deepseek_base_url: str = "https://api.deepseek.com"
    deepseek_chat_model: str = "deepseek-v4-flash"
    max_upload_bytes: int = 500 * 1024 * 1024
    max_transform_input_bytes: int = 100 * 1024 * 1024 * 1024
    knowledge_chunk_chars: int = 1200
    knowledge_chunk_overlap: int = 150
    request_timeout_seconds: float = 60.0
    llm_max_attempts: int = 2
    llm_retry_base_seconds: float = 1.0
    whisper_model: str = "small"
    whisper_device: str = "cpu"
    whisper_compute_type: str = "int8"
    agent_enabled: bool = True
    agent_runtime: Literal["deep-agents", "codex-cli", "opencode"] = "codex-cli"
    opencode_cli_path: str = "opencode"
    opencode_model: str = ""
    opencode_provider_config: str = ""
    agent_skills_dir: str = "skills"
    agent_mcp_config: str = ""
    agent_mcp_allowed_tools: str = ""
    agent_max_steps: int = 10
    agent_max_tool_calls: int = 80
    agent_task_timeout_seconds: int = 900
    research_search_provider: Literal["searxng"] = "searxng"
    research_searxng_url: str = ""
    research_searxng_engines: str = "sogou,bing,baidu,brave,duckduckgo"
    research_search_connect_timeout_seconds: float = 5.0
    research_search_timeout_seconds: float = 15.0
    research_allowed_domains: str = ""
    research_max_download_bytes: int = 10 * 1024 * 1024
    research_discover_timeout_seconds: float = 45.0
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")


settings = Settings()
