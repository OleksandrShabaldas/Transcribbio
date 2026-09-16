"""Central configuration for the ML sidecar.

All tunables resolve in this order: explicit constructor arg -> environment
variable -> value from ``config.json`` in the data dir -> built-in default.
The desktop app passes most settings via environment variables when it spawns us.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass, field, asdict
from pathlib import Path
from typing import Any, Optional


def _default_data_dir() -> Path:
    """Per-user, app-managed data directory (mirrors the desktop app's choice)."""
    env = os.environ.get("TRANSCRIBBIO_DATA_DIR")
    if env:
        return Path(env)
    local = os.environ.get("LOCALAPPDATA")
    if local:
        return Path(local) / "Transcribbio"
    return Path.home() / ".transcribbio"


@dataclass
class Settings:
    # ── Filesystem ──
    data_dir: Path = field(default_factory=_default_data_dir)

    # ── Whisper / STT ──
    # "large-v3" is downloaded from HF the first time; a local CT2 dir path also works.
    whisper_model: str = "large-v3"
    # "cuda" or "cpu"; "auto" picks cuda when available.
    device: str = "auto"
    # int8_float16 is the sweet spot for a 6 GB RTX 3060 (fast, fits in VRAM).
    compute_type_cuda: str = "int8_float16"
    compute_type_cpu: str = "int8"
    # Primary transcript language. Slovak by default; "auto" lets Whisper detect.
    default_language: str = "sk"
    beam_size: int = 5

    # ── Audio preprocessing ──
    target_sample_rate: int = 16000
    # Below this estimated SNR (dB) we run spectral denoise before transcription.
    denoise_snr_threshold_db: float = 18.0
    target_rms_dbfs: float = -20.0

    # ── LLM providers ──
    # "gemini" primary with "ollama" fallback, "ollama" only, or "gemini" only.
    llm_policy: str = "gemini_then_ollama"
    gemini_model: str = "gemini-2.0-flash"
    gemini_api_key: Optional[str] = None
    ollama_url: str = "http://127.0.0.1:11434"
    ollama_model: str = "qwen2.5:7b-instruct"
    # Approx. words per correction chunk (keeps well within model context with overlap).
    correction_chunk_words: int = 900
    correction_overlap_words: int = 80

    # ── Server ──
    host: str = "127.0.0.1"
    port: int = 0  # 0 => pick a free port and report it on stdout

    # ── Derived paths (not serialized) ──
    @property
    def models_dir(self) -> Path:
        return self.data_dir / "models"

    @property
    def library_dir(self) -> Path:
        return self.data_dir / "library"

    @property
    def config_file(self) -> Path:
        return self.data_dir / "config.json"

    @property
    def log_dir(self) -> Path:
        return self.data_dir / "logs"

    def resolved_device(self) -> str:
        if self.device != "auto":
            return self.device
        try:
            from .cuda import cuda_available

            return "cuda" if cuda_available() else "cpu"
        except Exception:
            return "cpu"

    def compute_type(self, device: Optional[str] = None) -> str:
        dev = device or self.resolved_device()
        return self.compute_type_cuda if dev == "cuda" else self.compute_type_cpu

    def ensure_dirs(self) -> None:
        for d in (self.data_dir, self.models_dir, self.library_dir, self.log_dir):
            d.mkdir(parents=True, exist_ok=True)

    # ── Persistence ──
    _SERIALIZABLE = {
        "whisper_model", "device", "compute_type_cuda", "compute_type_cpu",
        "default_language", "beam_size", "denoise_snr_threshold_db",
        "target_rms_dbfs", "llm_policy", "gemini_model", "ollama_url",
        "ollama_model", "correction_chunk_words", "correction_overlap_words",
    }

    def load_overrides(self) -> "Settings":
        """Overlay values from config.json + environment onto the defaults."""
        cfg = self.config_file
        if cfg.exists():
            try:
                data: dict[str, Any] = json.loads(cfg.read_text(encoding="utf-8"))
                for k in self._SERIALIZABLE:
                    if k in data and data[k] is not None:
                        setattr(self, k, data[k])
            except Exception:
                pass  # never let a bad config file crash the sidecar

        # Environment always wins (that's how the desktop app drives us).
        env_map = {
            "TRANSCRIBBIO_WHISPER_MODEL": "whisper_model",
            "TRANSCRIBBIO_DEVICE": "device",
            "TRANSCRIBBIO_LANGUAGE": "default_language",
            "TRANSCRIBBIO_LLM_POLICY": "llm_policy",
            "TRANSCRIBBIO_GEMINI_MODEL": "gemini_model",
            "TRANSCRIBBIO_GEMINI_API_KEY": "gemini_api_key",
            "TRANSCRIBBIO_OLLAMA_URL": "ollama_url",
            "TRANSCRIBBIO_OLLAMA_MODEL": "ollama_model",
        }
        for env_key, attr in env_map.items():
            val = os.environ.get(env_key)
            if val:
                setattr(self, attr, val)

        port = os.environ.get("TRANSCRIBBIO_PORT")
        if port and port.isdigit():
            self.port = int(port)
        return self

    def public_dict(self) -> dict[str, Any]:
        """Settings safe to surface to the UI (no secrets)."""
        d = {k: getattr(self, k) for k in self._SERIALIZABLE}
        d["data_dir"] = str(self.data_dir)
        d["gemini_api_key_set"] = bool(self.gemini_api_key)
        return d


_settings: Optional[Settings] = None


def get_settings() -> Settings:
    global _settings
    if _settings is None:
        _settings = Settings().load_overrides()
        _settings.ensure_dirs()
    return _settings
