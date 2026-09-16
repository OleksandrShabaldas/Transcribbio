"""Gemini provider (Google AI Studio free tier) via the google-genai SDK."""

from __future__ import annotations

import logging
from typing import Optional

from . import ProviderError, ProviderUnavailable

log = logging.getLogger("transcribbio.llm.gemini")


class GeminiProvider:
    name = "gemini"

    def __init__(self, api_key: Optional[str], model: str = "gemini-2.0-flash"):
        self.api_key = api_key
        self.model = model
        self._client = None

    def available(self) -> bool:
        return bool(self.api_key)

    def _client_or_raise(self):
        if not self.api_key:
            raise ProviderUnavailable("No Gemini API key configured")
        if self._client is None:
            try:
                from google import genai

                self._client = genai.Client(api_key=self.api_key)
            except Exception as e:  # pragma: no cover
                raise ProviderUnavailable(f"google-genai not usable: {e}") from e
        return self._client

    def generate(
        self,
        system: str,
        user: str,
        temperature: float = 0.2,
        max_output_tokens: int = 8192,
    ) -> str:
        client = self._client_or_raise()
        try:
            from google.genai import types

            resp = client.models.generate_content(
                model=self.model,
                contents=user,
                config=types.GenerateContentConfig(
                    system_instruction=system,
                    temperature=temperature,
                    max_output_tokens=max_output_tokens,
                ),
            )
        except Exception as e:
            raise ProviderError(f"Gemini request failed: {e}") from e

        text = getattr(resp, "text", None)
        if not text:
            raise ProviderError("Gemini returned an empty response")
        return text.strip()
