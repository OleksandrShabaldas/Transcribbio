"""High-level LLM tasks: transcript correction (chunked) and study-material
generation (summary / notes / takeaways / flashcards)."""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass
from typing import Callable, Optional

from . import prompts
from .llm.router import LLMRouter

log = logging.getLogger("transcribbio.tasks")

ProgressCb = Callable[[float, str], None]


# ── Correction ────────────────────────────────────────────────────────────────

def _chunk_words(text: str, chunk_words: int) -> list[str]:
    words = text.split()
    if not words:
        return []
    return [" ".join(words[i:i + chunk_words]) for i in range(0, len(words), chunk_words)]


def correct_transcript(
    router: LLMRouter,
    raw_text: str,
    language: str,
    chunk_words: int = 900,
    overlap_words: int = 80,
    progress: Optional[ProgressCb] = None,
) -> tuple[str, str]:
    """Return (corrected_text, provider_used). Long transcripts are corrected in
    chunks, each given the tail of the previous *corrected* output as context so
    sentences flow across boundaries without duplication."""
    chunks = _chunk_words(raw_text, chunk_words)
    if not chunks:
        return "", "none"

    corrected_parts: list[str] = []
    provider_used = "none"
    for i, chunk in enumerate(chunks):
        prev_tail = ""
        if corrected_parts:
            prev_tail = " ".join(corrected_parts[-1].split()[-overlap_words:])
        system = prompts.CORRECT_SYSTEM
        user = prompts.correct_user(chunk, language, prev_tail)
        result = router.generate(system, user, temperature=0.1)
        provider_used = result.provider
        corrected_parts.append(result.text.strip())
        if progress:
            progress((i + 1) / len(chunks), f"Correcting ({i + 1}/{len(chunks)})")

    return "\n\n".join(corrected_parts).strip(), provider_used


# ── Study materials ───────────────────────────────────────────────────────────

@dataclass
class Flashcard:
    question: str
    answer: str


def _extract_json_array(text: str) -> list:
    """Robustly pull a JSON array out of an LLM response (handles code fences /
    stray prose)."""
    cleaned = text.strip()
    cleaned = re.sub(r"^```(?:json)?", "", cleaned).strip()
    cleaned = re.sub(r"```$", "", cleaned).strip()
    try:
        return json.loads(cleaned)
    except Exception:
        pass
    start, end = cleaned.find("["), cleaned.rfind("]")
    if start != -1 and end != -1 and end > start:
        return json.loads(cleaned[start:end + 1])
    raise ValueError("Could not parse JSON array from model output")


def parse_flashcards(raw: str) -> list[Flashcard]:
    data = _extract_json_array(raw)
    cards: list[Flashcard] = []
    for item in data:
        if isinstance(item, dict):
            q = str(item.get("question", "")).strip()
            a = str(item.get("answer", "")).strip()
            if q and a:
                cards.append(Flashcard(q, a))
    if not cards:
        raise ValueError("No valid flashcards in model output")
    return cards


def flashcards_to_csv(cards: list[Flashcard]) -> str:
    """Anki-importable CSV (front,back). Fields are quoted/escaped."""
    import csv
    import io

    buf = io.StringIO()
    writer = csv.writer(buf)
    for c in cards:
        writer.writerow([c.question, c.answer])
    return buf.getvalue()


@dataclass
class MaterialResult:
    kind: str
    provider: str
    markdown: Optional[str] = None          # for summary/notes/takeaways
    flashcards: Optional[list[Flashcard]] = None  # for flashcards


def _strip_markdown_fence(text: str) -> str:
    """Remove an enclosing ```lang ... ``` fence some models wrap whole answers in."""
    t = text.strip()
    if t.startswith("```"):
        # drop first fence line (``` or ```markdown)
        nl = t.find("\n")
        if nl != -1:
            t = t[nl + 1:]
        if t.rstrip().endswith("```"):
            t = t.rstrip()[:-3]
    return t.strip()


def generate_material(
    router: LLMRouter,
    kind: str,
    transcript: str,
    language: str,
) -> MaterialResult:
    if kind not in prompts.MATERIAL_KINDS:
        raise ValueError(f"Unknown material kind: {kind}")
    system, user = prompts.material_prompt(kind, transcript, language)
    temperature = 0.3 if kind in ("summary", "notes", "takeaways") else 0.2
    result = router.generate(system, user, temperature=temperature)

    if kind == "flashcards":
        cards = parse_flashcards(result.text)
        return MaterialResult(kind=kind, provider=result.provider, flashcards=cards)
    return MaterialResult(kind=kind, provider=result.provider, markdown=_strip_markdown_fence(result.text))
