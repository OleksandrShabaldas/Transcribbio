"""Routes LLM requests across providers per the configured policy, with fallback."""

from __future__ import annotations

import logging
from dataclasses import dataclass

from ..config import Settings
from . import ProviderError, ProviderUnavailable
from .gemini import GeminiProvider
from .ollama import OllamaProvider

log = logging.getLogger("transcribbio.llm.router")


@dataclass
class LLMResult:
    text: str
    provider: str


class LLMRouter:
    def __init__(self, settings: Settings):
        self.settings = settings
        self.gemini = GeminiProvider(settings.gemini_api_key, settings.gemini_model)
        self.ollama = OllamaProvider(settings.ollama_url, settings.ollama_model)

    def _order(self) -> list:
        policy = self.settings.llm_policy
        if policy == "gemini_only":
            return [self.gemini]
        if policy == "ollama_only":
            return [self.ollama]
        # default: gemini_then_ollama
        return [self.gemini, self.ollama]

    def generate(self, system: str, user: str, temperature: float = 0.2) -> LLMResult:
        errors: list[str] = []
        for provider in self._order():
            if not provider.available():
                errors.append(f"{provider.name}: unavailable")
                continue
            try:
                text = provider.generate(system, user, temperature=temperature)
                return LLMResult(text=text, provider=provider.name)
            except ProviderUnavailable as e:
                errors.append(f"{provider.name}: {e}")
            except ProviderError as e:
                log.warning("Provider %s failed, trying next: %s", provider.name, e)
                errors.append(f"{provider.name}: {e}")
        raise ProviderError("No LLM provider succeeded -> " + "; ".join(errors))

    def status(self) -> dict:
        return {
            "policy": self.settings.llm_policy,
            "gemini": {
                "configured": self.gemini.available(),
                "model": self.settings.gemini_model,
            },
            "ollama": {
                "server_up": self.ollama.server_up(),
                "model": self.settings.ollama_model,
                "model_ready": self.ollama.has_model(),
            },
        }
