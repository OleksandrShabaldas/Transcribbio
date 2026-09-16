"""Slovak-aware prompt templates.

Instructions are in English (models follow English meta-instructions most
reliably), but every template forces the *output* to stay in the transcript's
own language — Slovak by default — and forbids translation. Correct Slovak
diacritics (á ä č ď é í ĺ ľ ň ó ô ŕ š ť ú ý ž) are explicitly requested because
ASR output frequently drops or mangles them.
"""

from __future__ import annotations

PROMPTS_VERSION = "1.0.0"

_LANG_NAMES = {"sk": "Slovak", "en": "English", "cs": "Czech"}


def lang_name(code: str) -> str:
    return _LANG_NAMES.get((code or "sk").split("-")[0], code or "the source language")


# ── Transcript correction ─────────────────────────────────────────────────────

CORRECT_SYSTEM = (
    "You are an expert proofreader specialising in cleaning up automatic speech "
    "recognition (ASR) transcripts of university lectures. The audio is often "
    "low quality (recorded far from the speaker), so the raw text contains "
    "misheard words, missing or wrong diacritics, run-on sentences, and no "
    "punctuation.\n\n"
    "Your job is to produce a faithful, readable transcript. You MUST:\n"
    "- Fix spelling, wrong/missing diacritics, punctuation, capitalisation, and "
    "obvious mis-recognitions using context.\n"
    "- Add sensible paragraph breaks and sentence structure.\n"
    "- Repair clearly garbled technical terms when the intended term is obvious "
    "from context.\n"
    "You MUST NOT:\n"
    "- Translate. Keep the text in its original language.\n"
    "- Summarise, shorten, or omit content. Preserve every spoken idea.\n"
    "- Invent facts, examples, or sentences that were not spoken.\n"
    "- Add commentary, notes, or markdown headings.\n"
    "When a word is unintelligible and context does not resolve it, keep the best "
    "guess rather than deleting the passage.\n"
    "Output ONLY the corrected transcript text."
)


def correct_user(chunk_text: str, language: str, prev_tail: str = "") -> str:
    ctx = f"(For context, the previous part ended with: \"{prev_tail}\". Do not repeat it.)\n\n" if prev_tail else ""
    return (
        f"The transcript language is {lang_name(language)}. "
        f"Correct the following raw ASR transcript chunk.\n\n{ctx}"
        f"RAW TRANSCRIPT CHUNK:\n{chunk_text}"
    )


# ── Study materials ───────────────────────────────────────────────────────────

_MATERIAL_SYSTEM_BASE = (
    "You are a diligent study assistant helping a university student turn a "
    "lecture transcript into study material. Always write your output in the SAME "
    "language as the transcript ({lang}). Never translate. Base everything strictly "
    "on the transcript — do not invent facts. Use correct {lang} diacritics."
)


def _material_system(language: str) -> str:
    return _MATERIAL_SYSTEM_BASE.format(lang=lang_name(language))


SUMMARY_INSTR = (
    "Write a clear, well-structured summary of the lecture in Markdown. Start with "
    "a one-paragraph overview, then cover the main topics in logical order. Aim for "
    "roughly 15-25% of the original length. Be precise about definitions and key "
    "relationships. Output Markdown only."
)

NOTES_INSTR = (
    "Produce structured study notes in Markdown: use `##` topic headings and nested "
    "bullet points. Capture definitions (bold the term), key concepts, cause/effect "
    "relationships, formulas, and any concrete examples the lecturer gave. Keep it "
    "scannable and faithful to what was said. Output Markdown only."
)

TAKEAWAYS_INSTR = (
    "List the key takeaways of the lecture as a Markdown bullet list — the 5 to 12 "
    "most important, exam-relevant points, each one sentence, concrete and specific. "
    "Output the bullet list only."
)

FLASHCARDS_INSTR = (
    "Create study flashcards from the lecture. Focus on definitions, key facts, "
    "cause/effect, and concepts a student would be tested on. Return STRICT JSON: an "
    "array of objects with exactly the keys \"question\" and \"answer\". Questions and "
    "answers must be in the transcript's language, concise, and self-contained. "
    "Produce between 8 and 25 cards depending on the material. Output ONLY the JSON "
    "array, no code fences, no commentary."
)

_MATERIAL_INSTR = {
    "summary": SUMMARY_INSTR,
    "notes": NOTES_INSTR,
    "takeaways": TAKEAWAYS_INSTR,
    "flashcards": FLASHCARDS_INSTR,
}


def material_prompt(kind: str, transcript: str, language: str) -> tuple[str, str]:
    if kind not in _MATERIAL_INSTR:
        raise ValueError(f"Unknown material kind: {kind}")
    system = _material_system(language)
    user = (
        f"{_MATERIAL_INSTR[kind]}\n\n"
        f"Lecture language: {lang_name(language)}.\n\n"
        f"TRANSCRIPT:\n{transcript}"
    )
    return system, user


MATERIAL_KINDS = tuple(_MATERIAL_INSTR.keys())
