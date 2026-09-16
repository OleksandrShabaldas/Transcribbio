# Transcribbio

A personal, **free**, offline‑capable lecture recorder + Slovak speech‑to‑text + AI study‑material generator.

Record lectures on your **watch**, **phone**, or **desktop**; recordings sync to your desktop
(the compute hub) over your home Wi‑Fi; the desktop transcribes them with a GPU Whisper model
tuned for **Slovak** (bad‑audio robust), runs a second AI pass to fix recognition/grammar errors,
and generates **summaries, structured notes, key takeaways, and flashcards**.

## The three apps

| App | Platform | Role |
|-----|----------|------|
| **Desktop hub** | Windows (Compose Desktop / JVM) | The brain: STT, AI cleanup, study‑material generation, library, always‑on sync receiver |
| **Phone** | Android (Compose) | Record, queue, auto‑upload to desktop on home Wi‑Fi, view results |
| **Watch** | Wear OS (Compose for Wear OS) | Record hands‑free, hand off to phone |

## Core tech

- **STT:** [faster‑whisper](https://github.com/SYSTRAN/faster-whisper) `large-v3` on CUDA (RTX 3060), Silero VAD, adaptive denoise for low‑quality audio.
- **AI cleanup + notes:** Google **Gemini** free tier (primary) with a **local Ollama** model (offline fallback).
- **UI:** **Kotlin Multiplatform + Compose Multiplatform** (one codebase across watch/phone/desktop).
- **ML sidecar:** Python process the desktop app manages automatically (`ml-sidecar/`).
- **Sync:** LAN discovery (mDNS) + store‑and‑forward upload. No cloud, no cost.

## Status

Built in phases — each phase is a complete, usable app:

- **Phase 1 — Desktop hub** ✅ — full record → transcribe → correct → study‑materials loop, library, settings, auto‑provisioning; verified on an RTX 3060.
- **Phase 2 — Android phone** ✅ — foreground‑service recording, offline queue, mDNS auto‑discovery + pairing, background upload (WorkManager), result viewer. Desktop runs the always‑on Wi‑Fi sync receiver.
- **Phase 3 — Wear OS watch** ✅ — record on the watch (foreground service), store‑and‑forward to the phone over the Wear Data Layer; the phone receives it and syncs onward. Builds to an APK; needs the physical watch + phone to runtime‑test the handoff.

## Build & run

Uses a portable Gradle in `.tooling/` and the JDK 17 already on the machine.

```bash
# Desktop hub (opens the app; it auto-starts the Python engine on first run)
./gradlew :desktop:run

# Package the desktop app as a Windows installer (.msi/.exe)
./gradlew :desktop:packageDistributionForCurrentOS

# Android phone app -> androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :androidApp:assembleDebug

# Wear OS watch app -> wearApp/build/outputs/apk/debug/wearApp-debug.apk
./gradlew :wearApp:assembleDebug

# Install over USB / Wi-Fi debugging
adb install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk   # phone
adb install -r wearApp/build/outputs/apk/debug/wearApp-debug.apk         # watch
```

The watch and phone apps share `applicationId com.transcribbio.phone` and the same
signing key — required for the Wear Data Layer to pair them. The watch records,
hands the file to the phone, and the phone syncs it to the desktop.

Open the desktop app and the phone app on the **same Wi‑Fi**; the phone discovers the
desktop automatically and its recordings sync over for processing.

## Repo layout

```
Transcribbio/
├── ml-sidecar/     # Python: Whisper pipeline + AI cleanup/notes (managed by the desktop app)
├── shared/         # KMP shared module: data models, sync protocol, repositories
├── desktop/        # Compose Desktop app — the hub
├── androidApp/     # Android phone app (Phase 2)
└── wearApp/        # Wear OS app (Phase 3)
```
