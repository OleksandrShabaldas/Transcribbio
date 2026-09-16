"""Audio preprocessing: decode anything -> 16 kHz mono float32, estimate SNR,
adaptively denoise poor recordings, and loudness-normalize.

Designed for the real-world problem this app targets: phone/watch mics recorded
from the back of a lecture hall (low SNR, room reverb, variable levels).
"""

from __future__ import annotations

import logging
from dataclasses import dataclass
from pathlib import Path
from typing import Optional

import numpy as np

log = logging.getLogger("transcribbio.audio")

SR = 16000


@dataclass
class PreprocessResult:
    audio: np.ndarray            # float32 mono @ 16 kHz, roughly [-1, 1]
    sample_rate: int
    duration_s: float
    snr_db: float
    denoised: bool


def decode_to_mono16k(path: str | Path, target_sr: int = SR) -> np.ndarray:
    """Decode any container/codec (m4a, mp3, wav, ...) to float32 mono @ target_sr.

    Uses faster-whisper's PyAV-based decoder (ffmpeg libs bundled in the wheel),
    so there is no external ffmpeg dependency. Falls back to soundfile for WAV.
    """
    path = str(path)
    try:
        from faster_whisper.audio import decode_audio

        audio = decode_audio(path, sampling_rate=target_sr)
        return np.asarray(audio, dtype=np.float32)
    except Exception as e:  # pragma: no cover - fallback path
        log.warning("PyAV decode failed (%s); trying soundfile", e)
        import soundfile as sf

        data, sr = sf.read(path, dtype="float32", always_2d=True)
        mono = data.mean(axis=1)
        if sr != target_sr:
            mono = _resample_linear(mono, sr, target_sr)
        return mono.astype(np.float32)


def _resample_linear(x: np.ndarray, sr_in: int, sr_out: int) -> np.ndarray:
    if sr_in == sr_out or x.size == 0:
        return x
    n_out = int(round(x.size * sr_out / sr_in))
    t_in = np.linspace(0.0, 1.0, num=x.size, endpoint=False)
    t_out = np.linspace(0.0, 1.0, num=n_out, endpoint=False)
    return np.interp(t_out, t_in, x).astype(np.float32)


def estimate_snr_db(audio: np.ndarray, sr: int = SR) -> float:
    """Cheap frame-energy SNR estimate: separation between the loud (speech) and
    quiet (noise floor) energy percentiles. Not calibrated dB, but a reliable
    relative indicator for deciding whether to denoise."""
    if audio.size < sr // 10:
        return 40.0
    frame = int(0.03 * sr)  # 30 ms
    hop = frame
    n = (audio.size - frame) // hop + 1
    if n <= 1:
        return 40.0
    frames = np.lib.stride_tricks.as_strided(
        audio,
        shape=(n, frame),
        strides=(audio.strides[0] * hop, audio.strides[0]),
    )
    energy = np.sqrt(np.mean(frames.astype(np.float64) ** 2, axis=1) + 1e-12)
    energy_db = 20.0 * np.log10(energy + 1e-9)
    noise = np.percentile(energy_db, 10)
    speech = np.percentile(energy_db, 90)
    return float(speech - noise)


def denoise(audio: np.ndarray, sr: int = SR) -> np.ndarray:
    """Spectral-gating denoise (noisereduce; no PyTorch). Non-stationary mode
    tracks slowly varying lecture-hall noise. Processed in blocks to bound memory
    on long recordings."""
    try:
        import noisereduce as nr
    except Exception as e:  # pragma: no cover
        log.warning("noisereduce unavailable (%s); skipping denoise", e)
        return audio

    if audio.size == 0:
        return audio

    block = 60 * sr  # 60 s blocks
    if audio.size <= block:
        return nr.reduce_noise(y=audio, sr=sr, stationary=False, prop_decrease=0.9).astype(np.float32)

    out = np.empty_like(audio)
    for start in range(0, audio.size, block):
        seg = audio[start:start + block]
        out[start:start + seg.size] = nr.reduce_noise(
            y=seg, sr=sr, stationary=False, prop_decrease=0.9
        )
    return out.astype(np.float32)


def normalize_rms(audio: np.ndarray, target_dbfs: float = -20.0) -> np.ndarray:
    """Loudness-normalize to a target RMS, then peak-limit to avoid clipping."""
    if audio.size == 0:
        return audio
    rms = float(np.sqrt(np.mean(audio.astype(np.float64) ** 2)) + 1e-12)
    target_rms = 10.0 ** (target_dbfs / 20.0)
    gain = target_rms / rms
    out = audio * gain
    peak = float(np.max(np.abs(out)) + 1e-9)
    if peak > 0.99:
        out = out * (0.99 / peak)
    return out.astype(np.float32)


def preprocess(
    path: str | Path,
    denoise_snr_threshold_db: float = 18.0,
    target_rms_dbfs: float = -20.0,
    force_denoise: Optional[bool] = None,
) -> PreprocessResult:
    """Full preprocessing chain used before transcription."""
    audio = decode_to_mono16k(path)
    duration = audio.size / SR
    snr = estimate_snr_db(audio)

    do_denoise = force_denoise if force_denoise is not None else (snr < denoise_snr_threshold_db)
    if do_denoise:
        log.info("Low SNR (%.1f dB) -> denoising %.1fs of audio", snr, duration)
        audio = denoise(audio)

    audio = normalize_rms(audio, target_rms_dbfs)
    return PreprocessResult(
        audio=audio,
        sample_rate=SR,
        duration_s=duration,
        snr_db=snr,
        denoised=bool(do_denoise),
    )


def write_wav(path: str | Path, audio: np.ndarray, sr: int = SR) -> None:
    import soundfile as sf

    Path(path).parent.mkdir(parents=True, exist_ok=True)
    sf.write(str(path), audio, sr, subtype="PCM_16")
