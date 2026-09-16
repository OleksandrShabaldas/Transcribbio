"""Full-pipeline integration test via JobManager — the exact code path the
desktop app uses: submit a job, poll progress, print the transcript, the
corrected transcript, and every study material.

Uses whatever LLM the policy resolves to (local Ollama when no Gemini key)."""

import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

from transcribbio_ml.config import get_settings  # noqa: E402
from transcribbio_ml.jobs import JobManager  # noqa: E402
from transcribbio_ml.llm.router import LLMRouter  # noqa: E402
from transcribbio_ml.models import ProcessRequest  # noqa: E402
from transcribbio_ml.transcribe import WhisperEngine  # noqa: E402

FIX = HERE / "fixtures"


def main() -> int:
    settings = get_settings()
    print("LLM policy:", settings.llm_policy)
    router = LLMRouter(settings)
    print("LLM status:", router.status())

    engine = WhisperEngine(settings)
    jobs = JobManager(settings, engine, router)

    audio = FIX / "sample_sk_noisy.wav"
    if not audio.exists():
        print("Missing fixture; run make_slovak_sample.py first")
        return 1

    req = ProcessRequest(
        path=str(audio),
        language="sk",
        materials=["summary", "notes", "takeaways", "flashcards"],
        correct=True,
    )
    job_id = jobs.submit(req)
    print("Submitted job", job_id)

    last = None
    while True:
        st = jobs.status(job_id)
        line = f"{st.state:8} {st.stage:20} {st.fraction*100:5.1f}%  {st.message}"
        if line != last:
            print(line)
            last = line
        if st.state in ("done", "error"):
            break
        time.sleep(0.4)

    if st.state == "error":
        print("ERROR:", st.error)
        return 1

    r = st.result
    print("\n================ RAW TRANSCRIPT ================")
    print(r.transcription.text)
    print(f"\n(lang={r.transcription.language} dur={r.transcription.duration_s:.1f}s "
          f"snr={r.transcription.snr_db:.1f}dB denoised={r.transcription.denoised} "
          f"device={r.transcription.device})")

    print("\n================ CORRECTED ================", f"(via {r.correction_provider})")
    print(r.corrected_text)

    for kind, mat in r.materials.items():
        print(f"\n================ {kind.upper()} ================", f"(via {mat.provider})")
        if mat.flashcards:
            for i, c in enumerate(mat.flashcards, 1):
                print(f"  {i}. Q: {c.question}")
                print(f"     A: {c.answer}")
        else:
            print(mat.markdown)

    jobs.shutdown()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
