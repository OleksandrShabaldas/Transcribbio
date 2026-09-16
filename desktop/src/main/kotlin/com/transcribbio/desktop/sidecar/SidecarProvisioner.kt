package com.transcribbio.desktop.sidecar

import com.transcribbio.desktop.core.ProcessRunner
import com.transcribbio.desktop.core.SidecarLocator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path

/** Auto-provisions the sidecar's Python environment and ML models so the user
 *  never has to touch a terminal (the "zero-config" requirement). */
class SidecarProvisioner {

    data class Progress(val message: String, val fraction: Double? = null)

    private val json = Json { ignoreUnknownKeys = true }

    /** Ensure the sidecar venv exists with all dependencies installed. */
    suspend fun ensureVenv(sidecarDir: Path, onProgress: (Progress) -> Unit): Result<Unit> {
        if (SidecarLocator.venvReady(sidecarDir)) return Result.success(Unit)

        val sysPython = SidecarLocator.systemPython()
            ?: return Result.failure(IllegalStateException(
                "No Python interpreter found. Install Python 3.10+ and retry."))

        onProgress(Progress("Creating Python environment…", null))
        val venvDir = sidecarDir.resolve(".venv").toString()
        val createCode = ProcessRunner.run(sysPython + listOf("-m", "venv", venvDir), sidecarDir) {
            onProgress(Progress(it))
        }
        if (createCode != 0) return Result.failure(IllegalStateException("venv creation failed ($createCode)"))

        val venvPy = SidecarLocator.venvPython(sidecarDir).toString()
        onProgress(Progress("Upgrading pip…", null))
        ProcessRunner.run(listOf(venvPy, "-m", "pip", "install", "--upgrade", "pip", "wheel", "setuptools"), sidecarDir) {
            onProgress(Progress(it))
        }

        onProgress(Progress("Installing dependencies (this can take a few minutes)…", null))
        val reqs = sidecarDir.resolve("requirements.txt").toString()
        val installCode = ProcessRunner.run(
            listOf(venvPy, "-m", "pip", "install", "-r", reqs), sidecarDir
        ) { line -> onProgress(Progress(line)) }

        return if (installCode == 0) Result.success(Unit)
        else Result.failure(IllegalStateException("pip install failed ($installCode)"))
    }

    /** Download the Whisper model (and optionally the local LLM) with progress. */
    suspend fun ensureModels(
        sidecarDir: Path,
        env: Map<String, String>,
        includeOllama: Boolean,
        onProgress: (Progress) -> Unit,
    ): Result<Unit> {
        val venvPy = SidecarLocator.venvPython(sidecarDir).toString()
        val cmd = mutableListOf(venvPy, "-m", "transcribbio_ml.provision", "--whisper")
        if (includeOllama) cmd += "--ollama"

        var failed = false
        val code = ProcessRunner.run(cmd, sidecarDir, env) { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("{")) {
                try {
                    val obj = json.parseToJsonElement(trimmed) as JsonObject
                    val target = obj["target"]?.jsonPrimitive?.content ?: ""
                    val state = obj["state"]?.jsonPrimitive?.content ?: ""
                    val message = obj["message"]?.jsonPrimitive?.content ?: ""
                    val fraction = obj["fraction"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    if (state == "error") failed = true
                    onProgress(Progress("[$target] $message", fraction))
                } catch (_: Exception) {
                    onProgress(Progress(trimmed))
                }
            } else {
                onProgress(Progress(trimmed))
            }
        }
        return if (code == 0 && !failed) Result.success(Unit)
        else Result.failure(IllegalStateException("Model provisioning failed"))
    }
}
