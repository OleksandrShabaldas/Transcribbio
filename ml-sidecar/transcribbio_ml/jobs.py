"""In-memory job runner for the full processing pipeline.

A single-worker executor serialises GPU-heavy work (only one transcription/LLM
job at a time) to stay within 6 GB VRAM. The desktop app submits a job and polls
its status for live progress; the desktop is the source of truth for persistence,
so losing in-memory jobs on restart is safe (it just resubmits)."""

from __future__ import annotations

import logging
import threading
import uuid
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass, field
from typing import Optional

from .audio import preprocess
from .config import Settings
from .llm.router import LLMRouter
from .models import (
    FlashcardModel, JobStatus, MaterialResponse, ProcessRequest, ProcessResult,
    SegmentModel, TranscriptionResponse, WordModel,
)
from .tasks import correct_transcript, generate_material
from .transcribe import TranscriptionResult, WhisperEngine

log = logging.getLogger("transcribbio.jobs")


@dataclass
class _JobState:
    job_id: str
    state: str = "queued"
    stage: str = ""
    fraction: float = 0.0
    message: str = ""
    result: Optional[ProcessResult] = None
    error: Optional[str] = None
    lock: threading.Lock = field(default_factory=threading.Lock)


def _to_transcription_response(tr: TranscriptionResult, snr_db: float, denoised: bool) -> TranscriptionResponse:
    return TranscriptionResponse(
        text=tr.text,
        language=tr.language,
        language_probability=tr.language_probability,
        duration_s=tr.duration_s,
        device=tr.device,
        compute_type=tr.compute_type,
        snr_db=snr_db,
        denoised=denoised,
        segments=[
            SegmentModel(
                id=s.id, start=s.start, end=s.end, text=s.text,
                avg_logprob=s.avg_logprob, no_speech_prob=s.no_speech_prob,
                compression_ratio=s.compression_ratio, low_confidence=s.low_confidence,
                words=[WordModel(start=w.start, end=w.end, word=w.word, probability=w.probability)
                       for w in s.words],
            )
            for s in tr.segments
        ],
    )


class JobManager:
    def __init__(self, settings: Settings, engine: WhisperEngine, router: LLMRouter):
        self.settings = settings
        self.engine = engine
        self.router = router
        self._jobs: dict[str, _JobState] = {}
        self._pool = ThreadPoolExecutor(max_workers=1, thread_name_prefix="tb-job")
        self._lock = threading.Lock()

    def submit(self, req: ProcessRequest) -> str:
        job_id = uuid.uuid4().hex[:12]
        with self._lock:
            self._jobs[job_id] = _JobState(job_id=job_id)
        self._pool.submit(self._run, job_id, req)
        return job_id

    def status(self, job_id: str) -> Optional[JobStatus]:
        st = self._jobs.get(job_id)
        if not st:
            return None
        with st.lock:
            return JobStatus(
                job_id=st.job_id, state=st.state, stage=st.stage, fraction=st.fraction,
                message=st.message, result=st.result, error=st.error,
            )

    def _set(self, job_id: str, **kw) -> None:
        st = self._jobs.get(job_id)
        if not st:
            return
        with st.lock:
            for k, v in kw.items():
                setattr(st, k, v)

    def _run(self, job_id: str, req: ProcessRequest) -> None:
        try:
            self._set(job_id, state="running", stage="preprocess", fraction=0.02, message="Preparing audio")
            pre = preprocess(
                req.path,
                denoise_snr_threshold_db=self.settings.denoise_snr_threshold_db,
                target_rms_dbfs=self.settings.target_rms_dbfs,
                force_denoise=req.denoise,
            )

            def stt_progress(frac: float, msg: str) -> None:
                self._set(job_id, stage="transcribe", fraction=0.10 + frac * 0.50, message=msg)

            self._set(job_id, stage="transcribe", fraction=0.10, message="Transcribing")
            tr = self.engine.transcribe(
                pre.audio,
                language=req.language or self.settings.default_language,
                progress=stt_progress,
            )
            transcription = _to_transcription_response(tr, pre.snr_db, pre.denoised)

            # The transcript is the core deliverable. LLM steps (correction, study
            # materials) are best-effort: if no provider is available or one fails, keep
            # the transcript and finish successfully rather than throwing it away.
            corrected_text: Optional[str] = None
            correction_provider: Optional[str] = None
            if req.correct and tr.text.strip():
                def corr_progress(frac: float, msg: str) -> None:
                    self._set(job_id, stage="correct", fraction=0.60 + frac * 0.25, message=msg)

                self._set(job_id, stage="correct", fraction=0.60, message="Correcting transcript")
                try:
                    corrected_text, correction_provider = correct_transcript(
                        self.router, tr.text, tr.language,
                        chunk_words=self.settings.correction_chunk_words,
                        overlap_words=self.settings.correction_overlap_words,
                        progress=corr_progress,
                    )
                except Exception as e:  # noqa: BLE001
                    log.warning("Correction skipped (keeping raw transcript): %s", e)

            materials: dict[str, MaterialResponse] = {}
            base_text = corrected_text or tr.text
            if req.materials and base_text.strip():
                n = len(req.materials)
                for i, kind in enumerate(req.materials):
                    self._set(job_id, stage=f"materials:{kind}", fraction=0.85 + (i / n) * 0.15,
                              message=f"Generating {kind}")
                    try:
                        mr = generate_material(self.router, kind, base_text, tr.language)
                        materials[kind] = MaterialResponse(
                            kind=mr.kind, provider=mr.provider, markdown=mr.markdown,
                            flashcards=[FlashcardModel(question=c.question, answer=c.answer)
                                        for c in mr.flashcards] if mr.flashcards else None,
                        )
                    except Exception as e:  # noqa: BLE001
                        log.warning("Study material '%s' skipped: %s", kind, e)

            result = ProcessResult(
                transcription=transcription,
                corrected_text=corrected_text,
                correction_provider=correction_provider,
                materials=materials,
            )
            self._set(job_id, state="done", stage="done", fraction=1.0, message="Complete", result=result)
            log.info("Job %s complete", job_id)
        except Exception as e:  # noqa: BLE001 - surface any failure to the client
            log.exception("Job %s failed", job_id)
            self._set(job_id, state="error", message="Failed", error=str(e))

    def shutdown(self) -> None:
        self._pool.shutdown(wait=False, cancel_futures=True)
