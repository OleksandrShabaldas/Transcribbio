"""Transcribbio ML sidecar.

A local, loopback-only service the desktop hub spawns and supervises. It performs
Slovak-first speech-to-text (faster-whisper on CUDA) plus LLM-based transcript
correction and study-material generation (Gemini primary, local Ollama fallback).
"""

__version__ = "0.1.0"
