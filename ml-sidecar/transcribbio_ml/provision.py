"""First-run provisioning: download the Whisper model and (optionally) the local
LLM, emitting NDJSON progress lines to stdout for the desktop app to display.

    python -m transcribbio_ml.provision --whisper --ollama

Each stdout line is a JSON object: {"target","state","fraction","message"}.
"""

from __future__ import annotations

import argparse
import json
import sys
from typing import Optional

from .config import get_settings
from .llm.ollama import OllamaProvider


def _emit(target: str, state: str, fraction: Optional[float], message: str) -> None:
    print(json.dumps({"target": target, "state": state, "fraction": fraction, "message": message}), flush=True)


def ensure_whisper_model() -> str:
    """Download the configured Whisper model into the app models dir. Returns path."""
    settings = get_settings()
    _emit("whisper", "start", None, f"Downloading Whisper model '{settings.whisper_model}'")
    from faster_whisper.utils import download_model

    path = download_model(settings.whisper_model, cache_dir=str(settings.models_dir))
    _emit("whisper", "done", 1.0, f"Whisper model ready: {path}")
    return path


def ensure_ollama_model() -> None:
    settings = get_settings()
    provider = OllamaProvider(settings.ollama_url, settings.ollama_model)
    if not provider.server_up():
        _emit("ollama", "error", None, "Ollama server not reachable (is Ollama installed/running?)")
        return
    if provider.has_model():
        _emit("ollama", "done", 1.0, f"Local model already present: {settings.ollama_model}")
        return
    _emit("ollama", "start", 0.0, f"Pulling local model '{settings.ollama_model}'")

    def prog(frac: Optional[float], status: str) -> None:
        _emit("ollama", "progress", frac, status)

    provider.pull(progress=prog)
    _emit("ollama", "done", 1.0, f"Local model ready: {settings.ollama_model}")


def main() -> int:
    parser = argparse.ArgumentParser(prog="transcribbio_ml.provision")
    parser.add_argument("--whisper", action="store_true", help="Download the Whisper model")
    parser.add_argument("--ollama", action="store_true", help="Pull the local LLM via Ollama")
    args = parser.parse_args()

    if not (args.whisper or args.ollama):
        args.whisper = True  # default: at least ensure STT is ready

    ok = True
    if args.whisper:
        try:
            ensure_whisper_model()
        except Exception as e:  # noqa: BLE001
            ok = False
            _emit("whisper", "error", None, str(e))
    if args.ollama:
        try:
            ensure_ollama_model()
        except Exception as e:  # noqa: BLE001
            ok = False
            _emit("ollama", "error", None, str(e))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
