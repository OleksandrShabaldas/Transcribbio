# Transcribbio ML Sidecar

The compute engine for the desktop hub: Slovak speech-to-text plus LLM-based
transcript correction and study-material generation. The desktop app spawns and
supervises this process automatically — you normally never run it by hand.

## What it does

- **STT:** `faster-whisper` (CTranslate2) `large-v3` on CUDA, with Silero VAD and
  adaptive spectral denoise for low-quality recordings. No PyTorch, no external ffmpeg.
- **LLM tasks:** transcript correction and summary / notes / takeaways / flashcards,
  routed to **Gemini** (free tier) with **Ollama** (local) as an offline fallback.
- **HTTP API:** a loopback FastAPI server; the desktop talks to it over `127.0.0.1`.

## Layout

```
transcribbio_ml/
├── __main__.py     # entry point: picks a port, prints a handshake, serves
├── main.py         # FastAPI app + endpoints
├── config.py       # settings (env / config.json driven)
├── cuda.py         # Windows CUDA DLL wiring for CTranslate2
├── audio.py        # decode → 16 kHz mono, SNR estimate, denoise, normalize
├── transcribe.py   # faster-whisper engine
├── tasks.py        # correction (chunked) + material generation
├── prompts.py      # Slovak-aware prompt templates
├── jobs.py         # in-memory job runner (serialises GPU work)
├── provision.py    # model download with NDJSON progress
├── models.py       # pydantic request/response models
└── llm/            # gemini.py, ollama.py, router.py
```

## Endpoints

| Method | Path | Purpose |
|--------|------|---------|
| GET  | `/health` | Liveness + device/model info |
| GET  | `/diag` | CUDA + LLM provider diagnostics |
| POST | `/transcribe` | Preprocess + transcribe one file |
| POST | `/correct` | LLM correction of a transcript |
| POST | `/materials` | Generate one study material |
| POST | `/jobs` | Start the full pipeline (returns `job_id`) |
| GET  | `/jobs/{id}` | Poll job progress + result |

All endpoints except `/health` require the `X-Transcribbio-Token` header when a
token is configured (the desktop sets one when it spawns the sidecar).

## Running manually (development)

```bash
# from ml-sidecar/
py -3.12 -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt

# smoke tests
.venv\Scripts\python tests\env_check.py          # imports + CUDA
.venv\Scripts\python tests\make_slovak_sample.py # generate test audio
.venv\Scripts\python tests\smoke_stt.py          # STT quality/speed
.venv\Scripts\python tests\smoke_pipeline.py     # full pipeline (Ollama)

# serve
.venv\Scripts\python -m transcribbio_ml --token dev
```

## Configuration (environment variables)

`TRANSCRIBBIO_DATA_DIR`, `TRANSCRIBBIO_LANGUAGE`, `TRANSCRIBBIO_WHISPER_MODEL`,
`TRANSCRIBBIO_DEVICE`, `TRANSCRIBBIO_LLM_POLICY`
(`gemini_then_ollama` | `ollama_only` | `gemini_only`),
`TRANSCRIBBIO_GEMINI_API_KEY`, `TRANSCRIBBIO_OLLAMA_MODEL`, `TRANSCRIBBIO_SIDECAR_TOKEN`.
