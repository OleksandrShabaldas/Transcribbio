"""Environment diagnostics: verify every ML dependency imports and report CUDA
status for CTranslate2. Run this any time the sidecar 'can't find the GPU'."""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

MODULES = [
    "numpy", "av", "soundfile", "scipy", "noisereduce", "onnxruntime",
    "ctranslate2", "faster_whisper", "fastapi", "uvicorn", "requests",
]


def main() -> int:
    print("IMPORTS:")
    ok = True
    for m in MODULES:
        try:
            mod = __import__(m)
            print(f"  {m:16} {getattr(mod, '__version__', 'ok')}")
        except Exception as e:
            ok = False
            print(f"  {m:16} FAIL: {e}")
    try:
        from google import genai  # noqa: F401
        print(f"  {'google-genai':16} ok")
    except Exception as e:
        ok = False
        print(f"  {'google-genai':16} FAIL: {e}")

    from transcribbio_ml import cuda
    print("\nCUDA:")
    print(json.dumps(cuda.describe(), indent=2))
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
