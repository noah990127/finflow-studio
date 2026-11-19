import json
from typing import AsyncIterator, Optional

import httpx

from .config import settings


class DeepSeekClient:
    @property
    def configured(self) -> bool:
        return bool(settings.deepseek_api_key)

    async def complete(self, system: str, user: str) -> Optional[str]:
        if not self.configured:
            return None
        headers = {
            "Authorization": "Bearer " + settings.deepseek_api_key,
            "Content-Type": "application/json",
        }
        payload = {
            "model": settings.deepseek_chat_model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "stream": False,
        }
        timeout = httpx.Timeout(settings.request_timeout_seconds)
        async with httpx.AsyncClient(base_url=settings.deepseek_base_url, timeout=timeout) as client:
            response = await client.post("/chat/completions", headers=headers, json=payload)
            response.raise_for_status()
            data = response.json()
            return data["choices"][0]["message"]["content"]

    async def stream_complete(self, system: str, user: str) -> AsyncIterator[str]:
        if not self.configured:
            return
        headers = {
            "Authorization": "Bearer " + settings.deepseek_api_key,
            "Content-Type": "application/json",
        }
        payload = {
            "model": settings.deepseek_chat_model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user},
            ],
            "stream": True,
        }
        timeout = httpx.Timeout(settings.request_timeout_seconds)
        async with httpx.AsyncClient(base_url=settings.deepseek_base_url, timeout=timeout) as client:
            async with client.stream("POST", "/chat/completions", headers=headers, json=payload) as response:
                response.raise_for_status()
                async for line in response.aiter_lines():
                    if not line.startswith("data:"):
                        continue
                    data = line[5:].strip()
                    if not data or data == "[DONE]":
                        continue
                    event = json.loads(data)
                    choices = event.get("choices") or []
                    if not choices:
                        continue
                    content = (choices[0].get("delta") or {}).get("content")
                    if isinstance(content, str) and content:
                        yield content


deepseek = DeepSeekClient()
