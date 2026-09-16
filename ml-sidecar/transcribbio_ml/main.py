"""FastAPI application for the Transcribbio ML sidecar.

Loopback-only. If a token is configured (env TRANSCRIBBIO_SIDECAR_TOKEN, set by
the desktop when it spawns us), every endpoint except /health requires the
matching X-Transcribbio-Token header.
"""

from __future__ import annotations

import logging
import os
from typing import Optional

from fastapi import Depends, FastAPI, Header, HTTPException

from . import __version__, cuda
from .config import Settings, get_settings
from .llm.router import LLMRouter
from .jobs import JobManager
from .models import (
    CorrectRequest, CorrectResponse, FlashcardModel, JobRef, JobStatus,
    MaterialRequest, MaterialResponse, ProcessRequest, TranscribeRequest,
    TranscriptionResponse,
)
from .tasks import correct_transcript, generate_material
from .transcribe import WhisperEngine

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("transcribbio.main")


def create_app(settings: Optional[Settings] = None) -> FastAPI:
    settings = settings or get_settings()
    engine = WhisperEngine(settings)
    router = LLMRouter(settings)
    jobs = JobManager(settings, engine, router)
    token = os.environ.get("TRANSCRIBBIO_SIDECAR_TOKEN")

    app = FastAPI(title="Transcribbio ML Sidecar", version=__version__)
    app.state.settings = settings
    app.state.engine = engine
    app.state.router = router
    app.state.jobs = jobs
    app.state.token = token

    def auth(x_transcribbio_token: Optional[str] = Header(default=None)) -> None:
        if token and x_transcribbio_token != token:
            raise HTTPException(status_code=401, detail="Invalid or missing sidecar token")

    # ── Status ────────────────────────────────────────────────────────────────
    @app.get("/health")
    def health() -> dict:
        return {
            "status": "ok",
            "version": __version__,
            "whisper_loaded": engine.loaded,
            "whisper_model": settings.whisper_model,
            "device": engine.device or settings.resolved_device(),
            "cuda_available": cuda.cuda_available(),
        }

    @app.get("/diag", dependencies=[Depends(auth)])
    def diag() -> dict:
        return {
            "version": __version__,
            "cuda": cuda.describe(),
            "llm": router.status(),
            "settings": settings.public_dict(),
        }

    # ── Stateless compute steps ────────────────────────────────────────────────
    @app.post("/transcribe", response_model=TranscriptionResponse, dependencies=[Depends(auth)])
    def transcribe(req: TranscribeRequest) -> TranscriptionResponse:
        from .audio import preprocess
        from .jobs import _to_transcription_response

        if not os.path.exists(req.path):
            raise HTTPException(status_code=404, detail=f"Audio not found: {req.path}")
        pre = preprocess(
            req.path,
            denoise_snr_threshold_db=settings.denoise_snr_threshold_db,
            target_rms_dbfs=settings.target_rms_dbfs,
            force_denoise=req.denoise,
        )
        tr = engine.transcribe(
            pre.audio, language=req.language or settings.default_language,
            word_timestamps=req.word_timestamps,
        )
        return _to_transcription_response(tr, pre.snr_db, pre.denoised)

    @app.post("/correct", response_model=CorrectResponse, dependencies=[Depends(auth)])
    def correct(req: CorrectRequest) -> CorrectResponse:
        text, provider = correct_transcript(
            router, req.text, req.language,
            chunk_words=settings.correction_chunk_words,
            overlap_words=settings.correction_overlap_words,
        )
        return CorrectResponse(text=text, provider=provider)

    @app.post("/materials", response_model=MaterialResponse, dependencies=[Depends(auth)])
    def materials(req: MaterialRequest) -> MaterialResponse:
        mr = generate_material(router, req.kind, req.text, req.language)
        return MaterialResponse(
            kind=mr.kind, provider=mr.provider, markdown=mr.markdown,
            flashcards=[FlashcardModel(question=c.question, answer=c.answer)
                        for c in mr.flashcards] if mr.flashcards else None,
        )

    # ── Full pipeline as a polled job ──────────────────────────────────────────
    @app.post("/jobs", response_model=JobRef, dependencies=[Depends(auth)])
    def submit_job(req: ProcessRequest) -> JobRef:
        if not os.path.exists(req.path):
            raise HTTPException(status_code=404, detail=f"Audio not found: {req.path}")
        return JobRef(job_id=jobs.submit(req))

    @app.get("/jobs/{job_id}", response_model=JobStatus, dependencies=[Depends(auth)])
    def job_status(job_id: str) -> JobStatus:
        st = jobs.status(job_id)
        if st is None:
            raise HTTPException(status_code=404, detail="Unknown job")
        return st

    return app


# Default app for `uvicorn transcribbio_ml.main:app`
app = create_app()
