"""Generate Slovak test audio for smoke tests.

Produces a clean TTS sample and a degraded 'back of the lecture hall' variant
(added noise, band-limiting, low level) so we can validate the bad-audio path.
Requires network for the TTS step (Microsoft edge-tts).
"""

import asyncio
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

HERE = Path(__file__).resolve().parent
FIX = HERE / "fixtures"
FIX.mkdir(parents=True, exist_ok=True)

# A realistic lecture paragraph: Slovak diacritics + technical terms that ASR
# commonly mangles (mitochondrie, glykolýza, Krebsov cyklus, ATP, ...).
REFERENCE_TEXT = (
    "Dnes sa budeme venovať téme bunkové dýchanie. Bunkové dýchanie je proces, "
    "pri ktorom bunky získavajú energiu rozkladom glukózy. Tento proces prebieha "
    "v mitochondriách a delí sa na tri hlavné fázy: glykolýzu, Krebsov cyklus a "
    "oxidatívnu fosforyláciu. Počas glykolýzy sa molekula glukózy rozkladá na dve "
    "molekuly pyruvátu. Výsledkom je tvorba adenozíntrifosfátu, skrátene ATP, "
    "ktorý slúži ako hlavný zdroj energie pre bunku."
)

VOICE = "sk-SK-LukasNeural"
CLEAN_MP3 = FIX / "sample_sk.mp3"
NOISY_WAV = FIX / "sample_sk_noisy.wav"
REF_TXT = FIX / "sample_sk.reference.txt"


async def synth() -> None:
    import edge_tts

    print(f"Synthesizing Slovak TTS -> {CLEAN_MP3.name}")
    communicate = edge_tts.Communicate(REFERENCE_TEXT, VOICE)
    await communicate.save(str(CLEAN_MP3))
    REF_TXT.write_text(REFERENCE_TEXT, encoding="utf-8")


def degrade() -> None:
    """Create a realistic bad-audio variant from the clean sample."""
    sys.path.insert(0, str(HERE.parent))
    from transcribbio_ml.audio import decode_to_mono16k

    audio = decode_to_mono16k(CLEAN_MP3)
    rng = np.random.default_rng(42)

    # 1) Band-limit (cheap telephone-ish lowpass ~ crude far-field mic).
    from scipy.signal import butter, lfilter

    b, a = butter(4, 3400 / 8000, btype="low")
    audio = lfilter(b, a, audio).astype(np.float32)

    # 2) Add broadband noise at roughly 8 dB SNR.
    sig_rms = float(np.sqrt(np.mean(audio**2)) + 1e-9)
    noise = rng.normal(0, 1, size=audio.shape).astype(np.float32)
    noise_rms = float(np.sqrt(np.mean(noise**2)) + 1e-9)
    target_snr_db = 8.0
    noise_gain = sig_rms / (noise_rms * (10 ** (target_snr_db / 20.0)))
    audio = audio + noise * noise_gain

    # 3) Drop the overall level (recorded far from the speaker).
    audio = audio * 0.35
    audio = np.clip(audio, -1.0, 1.0)

    sf.write(str(NOISY_WAV), audio, 16000, subtype="PCM_16")
    print(f"Wrote degraded sample -> {NOISY_WAV.name}")


def main() -> None:
    asyncio.run(synth())
    degrade()
    print("Done. Reference:", REFERENCE_TEXT[:60], "...")


if __name__ == "__main__":
    main()
