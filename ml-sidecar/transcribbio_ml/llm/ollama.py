"""Ollama provider (local, offline fallback) via its HTTP API on localhost."""

from __future__ import annotations

import logging
from typing import Optional

import requests

from . import ProviderError, ProviderUnavailable

log = logging.getLogger("transcribbio.llm.ollama")


class OllamaProvider:
    name = "ollama"

    def __init__(self, url: str = "http://127.0.0.1:11434", model: str = "qwen2.5:7b-instruct"):
        self.url = url.rstrip("/")
        self.model = model

    def _tags(self, timeout: float = 2.0) -> Optional[list[str]]:
        try:
            r = requests.get(f"{self.url}/api/tags", timeout=timeout)
            r.raise_for_status()
            return [m.get("name", "") for m in r.json().get("models", [])]
        except Exception:
            return None

    def server_up(self) -> bool:
        return self._tags() is not None

    def has_model(self) -> bool:
        tags = self._tags()
        if tags is None:
            return False
        # Match "qwen2.5:7b-instruct" or a bare "qwen2.5" family prefix.
        return any(t == self.model or t.split(":")[0] == self.model.split(":")[0] for t in tags)

    def available(self) -> bool:
        return self.has_model()

    def pull(self, progress=None, timeout: float = 3600.0) -> None:
        """Download the model (blocking, streamed). Used by first-run provisioning."""
        try:
            with requests.post(
                f"{self.url}/api/pull",
                json={"name": self.model, "stream": True},
                stream=True,
                timeout=timeout,
            ) as r:
                r.raise_for_status()
                import json

                for line in r.iter_lines():
                    if not line:
                        continue
                    evt = json.loads(line)
                    if progress and "completed" in evt and "total" in evt and evt["total"]:
                        progress(evt["completed"] / evt["total"], evt.get("status", "pulling"))
                    elif progress:
                        progress(None, evt.get("status", "pulling"))
        except Exception as e:
            raise ProviderError(f"Ollama pull failed: {e}") from e

    def generate(
        self,
        system: str,
        user: str,
        temperature: float = 0.2,
        num_ctx: int = 8192,
        timeout: float = 600.0,
    ) -> str:
        if not self.server_up():
            raise ProviderUnavailable("Ollama server is not running")
        try:
            r = requests.post(
                f"{self.url}/api/chat",
                json={
                    "model": self.model,
                    "messages": [
                        {"role": "system", "content": system},
                        {"role": "user", "content": user},
                    ],
                    "stream": False,
                    "options": {"temperature": temperature, "num_ctx": num_ctx},
                },
                timeout=timeout,
            )
            r.raise_for_status()
            content = r.json().get("message", {}).get("content", "")
        except ProviderError:
            raise
        except Exception as e:
            raise ProviderError(f"Ollama request failed: {e}") from e

        if not content.strip():
            raise ProviderError("Ollama returned an empty response")
        return content.strip()
