"""Gemini provider (Google AI Studio free tier) via the google-genai SDK.

Uses an ordered *chain* of models (primary + fallbacks) because individual Gemini
models are routinely unavailable on the free tier: retired (404), overloaded (503),
out of quota (429) or just very slow. Each request walks the chain until one model
answers:

- 503/500 "high demand"  -> one quick retry, then the next model
- 404 retired / 429 quota / 403 no access / empty reply -> next model immediately
- slower than ``timeout_s`` -> abandoned, next model
- 400/401 invalid API key -> stop (every model would fail the same way)

Models that just failed are put on a short cooldown and moved to the *back* of the
chain (never dropped), so a long, chunked correction doesn't pay the failure cost
on every chunk.
"""

from __future__ import annotations

import logging
import re
import threading
import time
from typing import Optional

from . import ProviderError, ProviderUnavailable

log = logging.getLogger("transcribbio.llm.gemini")

# Seconds a model sits at the back of the chain after failing, by failure kind.
_COOLDOWN_S = {"gone": 6 * 3600, "quota": 600, "overloaded": 120, "slow": 300, "empty": 60, "error": 120}

# Name fragments of models that can't do plain text generation (TTS, image, music,
# agents, etc.) — hidden from the model picker.
_NON_TEXT = (
    "tts", "image", "banana", "lyria", "robotics", "computer-use", "antigravity",
    "deep-research", "transcribe", "customtools", "embedding", "aqa", "veo", "imagen",
    "live", "audio",
)


# Google rejects request deadlines under 10s, and ~10s can expire even on tiny prompts.
MIN_TIMEOUT_S = 15.0


class _EmptyResponse(Exception):
    pass


def _sort_key(name: str) -> tuple:
    """-latest aliases first (never retire), then gemini-X.Y newest first, then others."""
    if name.endswith("-latest"):
        return (0, 0.0, name)
    m = re.match(r"gemini-(\d+(?:\.\d+)?)", name)
    if m:
        return (1, -float(m.group(1)), name)
    return (2 if name.startswith("gemini-") else 3, 0.0, name)


def is_text_model(name: str) -> bool:
    n = name.lower().removeprefix("models/")
    return n.startswith(("gemini-", "gemma-")) and not any(t in n for t in _NON_TEXT)


class GeminiProvider:
    name = "gemini"

    def __init__(self, api_key: Optional[str], models: list[str], timeout_s: float = 90.0):
        self.api_key = api_key
        self.models = [m.strip().removeprefix("models/") for m in models if m and m.strip()]
        self.timeout_s = max(MIN_TIMEOUT_S, float(timeout_s))
        self._client = None
        self._cooldown: dict[str, float] = {}  # model -> monotonic time it's healthy again
        self._lock = threading.Lock()

    def available(self) -> bool:
        return bool(self.api_key) and bool(self.models)

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

    # ── classification ──────────────────────────────────────────────────────────
    def _classify(self, e: Exception, timeout_s: float) -> tuple[str, str]:
        """Map an exception to (kind, short human detail)."""
        if isinstance(e, _EmptyResponse):
            return "empty", "empty or blocked reply"
        tname = type(e).__name__.lower()
        text = str(e)
        low = text.lower()
        if "timeout" in tname or "timed out" in low:
            return "slow", f"slower than {int(timeout_s)}s"
        code = getattr(e, "code", None)
        if isinstance(code, int):
            if code == 400 and ("api_key_invalid" in low or "api key not valid" in low):
                return "bad_key", "API key not valid"
            if code == 401:
                return "bad_key", "API key not valid (401 unauthorized)"
            if code == 403:
                return "error", "403 no access to this model"
            if code == 404:
                return "gone", "404 model retired / not available"
            if code == 429:
                return "quota", "429 free-tier quota used up"
            if code == 504 or "deadline_exceeded" in low or "deadline expired" in low:
                return "slow", f"slower than {int(timeout_s)}s"
            if code in (500, 502, 503):
                return "overloaded", f"{code} busy / high demand"
            return "error", f"{code} {getattr(e, 'status', '') or ''}".strip()
        return "error", text[:160] or type(e).__name__

    # ── chain ───────────────────────────────────────────────────────────────────
    def _ordered_models(self) -> list[str]:
        now = time.monotonic()
        with self._lock:
            healthy = [m for m in self.models if self._cooldown.get(m, 0) <= now]
            cooling = [m for m in self.models if self._cooldown.get(m, 0) > now]
        return healthy + cooling

    def _mark(self, model: str, kind: str) -> None:
        secs = _COOLDOWN_S.get(kind)
        if secs:
            with self._lock:
                self._cooldown[model] = time.monotonic() + secs

    def _clear(self, model: str) -> None:
        with self._lock:
            self._cooldown.pop(model, None)

    def call_model(
        self,
        model: str,
        system: str,
        user: str,
        temperature: float = 0.2,
        max_output_tokens: int = 16384,
        timeout_s: Optional[float] = None,
    ) -> str:
        """One request to one specific model (no fallback). Raises on failure."""
        client = self._client_or_raise()
        from google.genai import types

        t = max(MIN_TIMEOUT_S, timeout_s or self.timeout_s)
        # Gemma models on the Gemini API don't accept a system instruction; fold it in.
        if system and model.startswith("gemma-"):
            user, system = system + "\n\n" + user, ""
        resp = client.models.generate_content(
            model=model,
            contents=user,
            config=types.GenerateContentConfig(
                system_instruction=system or None,
                temperature=temperature,
                max_output_tokens=max_output_tokens,
                http_options=types.HttpOptions(timeout=int(t * 1000)),
                automatic_function_calling=types.AutomaticFunctionCallingConfig(disable=True),
            ),
        )
        text = getattr(resp, "text", None)
        if not text or not text.strip():
            raise _EmptyResponse()
        return text.strip()

    def generate(
        self,
        system: str,
        user: str,
        temperature: float = 0.2,
        max_output_tokens: int = 16384,
    ) -> tuple[str, str]:
        """Walk the model chain; return (text, model_used)."""
        self._client_or_raise()
        failures: list[str] = []
        slow: list[str] = []  # models that only failed by being too slow
        for model in self._ordered_models():
            for attempt in (1, 2):
                try:
                    text = self.call_model(model, system, user, temperature, max_output_tokens)
                    self._clear(model)
                    if failures:
                        log.info("Gemini fell back to %s after: %s", model, "; ".join(failures))
                    return text, model
                except Exception as e:  # noqa: BLE001 — classified below
                    kind, detail = self._classify(e, self.timeout_s)
                    if kind == "bad_key":
                        raise ProviderUnavailable(detail) from e
                    if kind == "overloaded" and attempt == 1:
                        time.sleep(1.5)
                        continue
                    self._mark(model, kind)
                    if kind == "slow":
                        slow.append(model)
                    failures.append(f"{model}: {detail}")
                    log.warning("Gemini model %s failed (%s), trying next", model, detail)
                    break
        # The timeout exists to switch away from a sluggish model, not to fail big tasks:
        # if nothing finished in time, give the first slow model one patient attempt.
        if slow:
            patient = max(600.0, self.timeout_s * 4)
            log.info("No model finished within %ss; retrying %s with %ss", int(self.timeout_s), slow[0], int(patient))
            try:
                text = self.call_model(slow[0], system, user, temperature, max_output_tokens, timeout_s=patient)
                self._clear(slow[0])
                return text, slow[0]
            except Exception as e:  # noqa: BLE001
                failures.append(f"{slow[0]} (patient retry): {self._classify(e, patient)[1]}")
        raise ProviderError("all Gemini models failed -> " + " | ".join(failures))

    # ── discovery / diagnostics ─────────────────────────────────────────────────
    def list_text_models(self) -> list[dict]:
        client = self._client_or_raise()
        out = []
        for m in client.models.list():
            name = (getattr(m, "name", "") or "").removeprefix("models/")
            acts = getattr(m, "supported_actions", None) or []
            if "generateContent" in acts and is_text_model(name):
                out.append({"name": name, "display_name": getattr(m, "display_name", "") or name})
        return sorted(out, key=lambda d: _sort_key(d["name"]))

    def test_model(self, model: str, timeout_s: float = 30.0) -> dict:
        t0 = time.monotonic()
        try:
            self.call_model(model, "", "Reply with the single word OK.", temperature=0.0,
                            max_output_tokens=256, timeout_s=timeout_s)
            self._clear(model)
            return {"model": model, "ok": True, "latency_s": round(time.monotonic() - t0, 1), "detail": "works"}
        except Exception as e:  # noqa: BLE001
            kind, detail = self._classify(e, timeout_s)
            return {"model": model, "ok": False, "latency_s": round(time.monotonic() - t0, 1), "detail": detail}
