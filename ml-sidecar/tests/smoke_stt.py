"""End-to-end STT smoke test on clean + degraded Slovak audio.

Downloads the Whisper model on first run, transcribes both fixtures, prints
timing / real-time factor / device, and a rough WER against the reference.
"""

import re
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from transcribbio_ml.audio import preprocess  # noqa: E402
from transcribbio_ml.config import get_settings  # noqa: E402
from transcribbio_ml.transcribe import WhisperEngine  # noqa: E402

FIX = HERE / "fixtures"


def normalize(text: str) -> list[str]:
    text = text.lower()
    text = re.sub(r"[^\w\sáäčďéíĺľňóôŕšťúýž]", " ", text)
    return text.split()


def wer(ref: str, hyp: str) -> float:
    r, h = normalize(ref), normalize(hyp)
    # Levenshtein over word lists.
    d = [[0] * (len(h) + 1) for _ in range(len(r) + 1)]
    for i in range(len(r) + 1):
        d[i][0] = i
    for j in range(len(h) + 1):
        d[0][j] = j
    for i in range(1, len(r) + 1):
        for j in range(1, len(h) + 1):
            cost = 0 if r[i - 1] == h[j - 1] else 1
            d[i][j] = min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
    return d[len(r)][len(h)] / max(len(r), 1)


def run_one(engine: WhisperEngine, settings, path: Path, ref: str) -> None:
    print(f"\n=== {path.name} ===")
    t0 = time.time()
    pre = preprocess(
        path,
        denoise_snr_threshold_db=settings.denoise_snr_threshold_db,
        target_rms_dbfs=settings.target_rms_dbfs,
    )
    t_pre = time.time() - t0
    print(f"preprocess: {t_pre:.2f}s | dur {pre.duration_s:.1f}s | SNR {pre.snr_db:.1f}dB | denoised={pre.denoised}")

    t1 = time.time()
    res = engine.transcribe(pre.audio, language=settings.default_language)
    t_stt = time.time() - t1
    rtf = t_stt / max(pre.duration_s, 0.01)
    print(f"transcribe: {t_stt:.2f}s | RTF {rtf:.2f}x | {res.device}/{res.compute_type} | lang={res.language} ({res.language_probability:.2f})")
    print(f"WER vs reference: {wer(ref, res.text) * 100:.1f}%")
    print(f"segments: {len(res.segments)} | low-conf: {sum(s.low_confidence for s in res.segments)}")
    print("TRANSCRIPT:")
    print(" ", res.text)


def main() -> int:
    settings = get_settings()
    ref = (FIX / "sample_sk.reference.txt").read_text(encoding="utf-8")
    engine = WhisperEngine(settings)

    for name in ("sample_sk.mp3", "sample_sk_noisy.wav"):
        p = FIX / name
        if p.exists():
            run_one(engine, settings, p, ref)
        else:
            print(f"(skip {name}: not found — run make_slovak_sample.py first)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
