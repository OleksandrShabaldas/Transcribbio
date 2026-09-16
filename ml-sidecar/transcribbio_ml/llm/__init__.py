"""LLM provider abstraction: Gemini (cloud, primary) and Ollama (local, fallback)."""


class ProviderError(RuntimeError):
    """Raised when an LLM provider is unavailable or fails a request."""


class ProviderUnavailable(ProviderError):
    """Provider is not usable right now (no key, offline, model missing)."""
