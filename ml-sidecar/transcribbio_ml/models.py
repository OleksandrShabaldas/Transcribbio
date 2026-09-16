"""Pydantic request/response models for the sidecar HTTP API."""

from __future__ import annotations

from typing import Literal, Optional

from pydantic import BaseModel, Field

MaterialKind = Literal["summary", "notes", "takeaways", "flashcards"]


class WordModel(BaseModel):
    start: float
    end: float
    word: str
    probability: float


class SegmentModel(BaseModel):
    id: int
    start: float
    end: float
    text: str
    avg_logprob: float
    no_speech_prob: float
    compression_ratio: float
    low_confidence: bool
    words: list[WordModel] = []


class TranscriptionResponse(BaseModel):
    text: str
    language: str
    language_probability: float
    duration_s: float
    device: str
    compute_type: str
    snr_db: Optional[float] = None
    denoised: Optional[bool] = None
    segments: list[SegmentModel] = []


class TranscribeRequest(BaseModel):
    path: str = Field(..., description="Local audio file path accessible to the sidecar")
    language: Optional[str] = Field(None, description="ISO code, or 'auto'")
    denoise: Optional[bool] = Field(None, description="Force denoise on/off; None = auto by SNR")
    word_timestamps: bool = True


class CorrectRequest(BaseModel):
    text: str
    language: str = "sk"


class CorrectResponse(BaseModel):
    text: str
    provider: str


class MaterialRequest(BaseModel):
    kind: MaterialKind
    text: str
    language: str = "sk"


class FlashcardModel(BaseModel):
    question: str
    answer: str


class MaterialResponse(BaseModel):
    kind: MaterialKind
    provider: str
    markdown: Optional[str] = None
    flashcards: Optional[list[FlashcardModel]] = None


class ProcessRequest(BaseModel):
    path: str
    language: Optional[str] = None
    denoise: Optional[bool] = None
    materials: list[MaterialKind] = Field(default_factory=list)
    correct: bool = True


class JobRef(BaseModel):
    job_id: str


class ProcessResult(BaseModel):
    transcription: TranscriptionResponse
    corrected_text: Optional[str] = None
    correction_provider: Optional[str] = None
    materials: dict[str, MaterialResponse] = Field(default_factory=dict)


class JobStatus(BaseModel):
    job_id: str
    state: Literal["queued", "running", "done", "error"]
    stage: str = ""
    fraction: float = 0.0
    message: str = ""
    result: Optional[ProcessResult] = None
    error: Optional[str] = None
