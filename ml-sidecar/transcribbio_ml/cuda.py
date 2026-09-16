"""Windows CUDA runtime wiring for CTranslate2 / faster-whisper.

On Windows, CTranslate2 loads cuDNN and cuBLAS DLLs at model-creation time. We
install those libraries as pip wheels (``nvidia-cudnn-cu12``, ``nvidia-cublas-cu12``)
which drop their DLLs under ``site-packages/nvidia/*/bin``. Those directories are
not on the default DLL search path, so we register them explicitly *before*
CTranslate2 tries to place a model on the GPU. This is the single most common
reason GPU Whisper "silently" falls back to CPU on Windows.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path
from typing import Optional

_dll_dirs_registered = False
_cuda_available: Optional[bool] = None


def _nvidia_bin_dirs() -> list[Path]:
    """Locate the bundled NVIDIA DLL directories inside the active environment."""
    dirs: list[Path] = []
    try:
        import nvidia  # provided by the nvidia-* wheels
    except Exception:
        return dirs

    for base in getattr(nvidia, "__path__", []):
        root = Path(base)
        for sub in ("cudnn", "cublas", "cuda_runtime"):
            # Windows DLLs live in .../bin, Linux .so files in .../lib
            for leaf in ("bin", "lib"):
                d = root / sub / leaf
                if d.is_dir():
                    dirs.append(d)
    return dirs


def setup_cuda_dll_path() -> bool:
    """Register bundled CUDA DLL directories. Idempotent. Returns True if any found."""
    global _dll_dirs_registered
    if _dll_dirs_registered:
        return True

    found = False
    for d in _nvidia_bin_dirs():
        found = True
        try:
            if sys.platform == "win32" and hasattr(os, "add_dll_directory"):
                os.add_dll_directory(str(d))
        except Exception:
            pass
        # Also prepend to PATH as a fallback for older loaders.
        os.environ["PATH"] = str(d) + os.pathsep + os.environ.get("PATH", "")

    _dll_dirs_registered = True
    return found


def cuda_available() -> bool:
    """True if a usable CUDA device is present for CTranslate2."""
    global _cuda_available
    if _cuda_available is not None:
        return _cuda_available

    setup_cuda_dll_path()
    try:
        import ctranslate2

        _cuda_available = ctranslate2.get_cuda_device_count() > 0
    except Exception:
        _cuda_available = False
    return _cuda_available


def describe() -> dict:
    """Diagnostic info surfaced to the desktop app / logs."""
    info = {
        "platform": sys.platform,
        "cuda_available": cuda_available(),
        "nvidia_dll_dirs": [str(d) for d in _nvidia_bin_dirs()],
    }
    try:
        import ctranslate2

        info["ctranslate2_version"] = ctranslate2.__version__
        info["cuda_device_count"] = ctranslate2.get_cuda_device_count()
    except Exception as e:  # pragma: no cover - diagnostics only
        info["ctranslate2_error"] = str(e)
    return info
