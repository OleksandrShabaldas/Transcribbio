"""faster-whisper transcription engine, tuned for noisy Slovak lecture audio.

Key choices for robustness on bad audio:
- Silero VAD to skip silence and cut hallucinated repetitions.
- condition_on_previous_text=False so one bad guess doesn't cascade.
- word timestamps + per-segment confidence so the correction pass and the UI can
  flag low-confidence spans instead of silently trusting them.
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Callable, Optional

import numpy as np

from .config import Settings
from .cuda import setup_cuda_dll_path

log = logging.getLogger("transcribbio.transcribe")

ProgressCb = Callable[[float, str], None]  # (fraction_0_1, stage_message)


@dataclass
class Word:
    start: float
    end: float
    word: str
    probability: float


@dataclass
class Segment:
    id: int
    start: float
    end: float
    text: str
    avg_logprob: float
    no_speech_prob: float
    compression_ratio: float
    low_confidence: bool
    words: list[Word]


@dataclass
class TranscriptionResult:
    text: str
    language: str
    language_probability: float
    duration_s: float
    device: str
    compute_type: str
    segments: list[Segment]


def _is_low_confidence(avg_logprob: float, no_speech_prob: float, compression_ratio: float) -> bool:
    return avg_logprob < -1.0 or no_speech_prob > 0.6 or compression_ratio > 2.4


class WhisperEngine:
    """Lazily-loaded, reusable Whisper model wrapper."""

    def __init__(self, settings: Settings):
        self.settings = settings
        self._model = None
        self.device: Optional[str] = None
        self.compute_type: Optional[str] = None

    @property
    def loaded(self) -> bool:
        return self._model is not None

    def load(self) -> None:
        if self._model is not None:
            return
        setup_cuda_dll_path()
        from faster_whisper import WhisperModel

        device = self.settings.resolved_device()
        compute_type = self.settings.compute_type(device)
        model_id = self.settings.whisper_model
        download_root = str(self.settings.models_dir)

        t0 = time.time()
        try:
            log.info("Loading Whisper '%s' on %s (%s)", model_id, device, compute_type)
            self._model = WhisperModel(
                model_id, device=device, compute_type=compute_type, download_root=download_root
            )
            self.device, self.compute_type = device, compute_type
        except Exception as e:
            if device == "cuda":
                log.warning("CUDA load failed (%s); falling back to CPU int8", e)
                self._model = WhisperModel(
                    model_id, device="cpu", compute_type=self.settings.compute_type_cpu,
                    download_root=download_root,
                )
                self.device, self.compute_type = "cpu", self.settings.compute_type_cpu
            else:
                raise
        log.info("Whisper ready in %.1fs (%s/%s)", time.time() - t0, self.device, self.compute_type)

    def unload(self) -> None:
        self._model = None

    def transcribe(
        self,
        audio: np.ndarray,
        language: Optional[str] = None,
        vad: bool = True,
        word_timestamps: bool = True,
        progress: Optional[ProgressCb] = None,
    ) -> TranscriptionResult:
        self.load()
        assert self._model is not None
        lang = None if (language or self.settings.default_language) == "auto" else (
            language or self.settings.default_language
        )

        if progress:
            progress(0.0, "Transcribing")

        segments_iter, info = self._model.transcribe(
            audio,
            language=lang,
            beam_size=self.settings.beam_size,
            vad_filter=vad,
            vad_parameters=dict(min_silence_duration_ms=500, speech_pad_ms=200),
            word_timestamps=word_timestamps,
            condition_on_previous_text=False,
            temperature=[0.0, 0.2, 0.4, 0.6, 0.8, 1.0],  # graceful fallback on hard audio
        )

        total = max(info.duration, 0.01)
        segments: list[Segment] = []
        text_parts: list[str] = []
        for seg in segments_iter:
            words = [
                Word(w.start, w.end, w.word, w.probability)
                for w in (seg.words or [])
            ] if word_timestamps else []
            low = _is_low_confidence(seg.avg_logprob, seg.no_speech_prob, seg.compression_ratio)
            segments.append(
                Segment(
                    id=seg.id, start=seg.start, end=seg.end, text=seg.text.strip(),
                    avg_logprob=seg.avg_logprob, no_speech_prob=seg.no_speech_prob,
                    compression_ratio=seg.compression_ratio, low_confidence=low, words=words,
                )
            )
            text_parts.append(seg.text.strip())
            if progress:
                progress(min(seg.end / total, 0.999), "Transcribing")

        if progress:
            progress(1.0, "Transcribed")

        return TranscriptionResult(
            text=" ".join(p for p in text_parts if p),
            language=info.language,
            language_probability=float(info.language_probability),
            duration_s=float(info.duration),
            device=self.device or "cpu",
            compute_type=self.compute_type or "int8",
            segments=segments,
        )
