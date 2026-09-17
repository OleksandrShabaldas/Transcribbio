package com.transcribbio.desktop.sidecar

import com.transcribbio.desktop.core.AppConfig
import com.transcribbio.desktop.core.SidecarLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.UUID

sealed interface SidecarState {
    data object Stopped : SidecarState
    data class Provisioning(val message: String, val fraction: Double? = null) : SidecarState
    data class Starting(val message: String) : SidecarState
    data class Ready(val health: HealthDto, val port: Int) : SidecarState
    data class Failed(val reason: String) : SidecarState
}

class SidecarManager(
    private val scope: CoroutineScope,
    private val provisioner: SidecarProvisioner = SidecarProvisioner(),
) {
    private val _state = MutableStateFlow<SidecarState>(SidecarState.Stopped)
    val state: StateFlow<SidecarState> = _state.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    @Volatile var client: SidecarClient? = null
        private set

    private var process: Process? = null
    private val json = Json { ignoreUnknownKeys = true }

    private fun appendLog(line: String) {
        _log.value = (_log.value + line).takeLast(400)
    }

    private fun buildEnv(config: AppConfig, token: String): Map<String, String> = buildMap {
        put("TRANSCRIBBIO_DATA_DIR", config.dataDir)
        put("TRANSCRIBBIO_LLM_POLICY", config.llmPolicy.api)
        put("TRANSCRIBBIO_LANGUAGE", config.language)
        put("TRANSCRIBBIO_WHISPER_MODEL", config.whisperModel)
        put("TRANSCRIBBIO_OLLAMA_MODEL", config.ollamaModel)
        put("TRANSCRIBBIO_DEVICE", config.device)
        put("TRANSCRIBBIO_SIDECAR_TOKEN", token)
        if (config.hasGeminiKey()) put("TRANSCRIBBIO_GEMINI_API_KEY", config.geminiApiKey)
        put("PYTHONUTF8", "1")
        put("PYTHONUNBUFFERED", "1")
    }

    suspend fun start(config: AppConfig) {
        stop()
        val sidecarDir = SidecarLocator.sidecarDir()
        if (sidecarDir == null) {
            _state.value = SidecarState.Failed("Could not locate the ml-sidecar directory")
            return
        }
        val dataDir = java.nio.file.Paths.get(config.dataDir)

        // 1) Ensure the Python environment exists.
        _state.value = SidecarState.Provisioning("Preparing Python environment…")
        val venv = provisioner.ensureVenv(sidecarDir, dataDir) { p ->
            _state.value = SidecarState.Provisioning(p.message, p.fraction)
            appendLog(p.message)
        }
        if (venv.isFailure) {
            _state.value = SidecarState.Failed(venv.exceptionOrNull()?.message ?: "venv setup failed")
            return
        }

        // 2) Ensure the Whisper model is present (fast no-op once cached).
        val token = UUID.randomUUID().toString().replace("-", "")
        val env = buildEnv(config, token)
        _state.value = SidecarState.Provisioning("Preparing Whisper model…")
        val models = provisioner.ensureModels(sidecarDir, dataDir, env, includeOllama = false) { p ->
            _state.value = SidecarState.Provisioning(p.message, p.fraction)
            appendLog(p.message)
        }
        if (models.isFailure) {
            _state.value = SidecarState.Failed(models.exceptionOrNull()?.message ?: "model download failed")
            return
        }

        // 3) Spawn the sidecar process.
        _state.value = SidecarState.Starting("Starting engine…")
        val venvPy = SidecarLocator.venvPython(sidecarDir, dataDir).toString()
        val portDeferred = CompletableDeferred<Int>()
        val proc = withContext(Dispatchers.IO) {
            val pb = ProcessBuilder(venvPy, "-m", "transcribbio_ml", "--token", token)
                .redirectErrorStream(true)
            pb.directory(sidecarDir.toFile())
            pb.environment().putAll(env)
            pb.start()
        }
        process = proc

        // Reader coroutine: catch the handshake, then keep buffering logs.
        scope.launch(Dispatchers.IO) {
            BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line!!
                    appendLog(l)
                    if (l.startsWith("TRANSCRIBBIO_SIDECAR ") && !portDeferred.isCompleted) {
                        runCatching {
                            val obj = json.parseToJsonElement(l.removePrefix("TRANSCRIBBIO_SIDECAR ").trim()) as JsonObject
                            portDeferred.complete(obj["port"]!!.jsonPrimitive.content.toInt())
                        }.onFailure { portDeferred.completeExceptionally(it) }
                    }
                }
            }
            // Process ended.
            if (_state.value is SidecarState.Ready || _state.value is SidecarState.Starting) {
                _state.value = SidecarState.Failed("Engine process exited (code ${proc.exitValue()})")
                client = null
            }
        }

        // 4) Wait for the handshake, then health-check.
        val port = withTimeoutOrNull(60_000) { portDeferred.await() }
        if (port == null) {
            _state.value = SidecarState.Failed("Engine did not report a port in time")
            stop()
            return
        }
        val c = SidecarClient("127.0.0.1", port, token)
        val ok: HealthDto? = withTimeoutOrNull(120_000) {
            var health: HealthDto? = null
            while (health == null || health.status != "ok") {
                health = runCatching { c.health() }.getOrNull()
                if (health == null || health.status != "ok") delay(400)
            }
            health
        }
        if (ok == null) {
            _state.value = SidecarState.Failed("Engine health check timed out")
            stop()
            return
        }
        client = c
        _state.value = SidecarState.Ready(ok, port)
        appendLog("Sidecar ready on port $port (cuda=${ok.cudaAvailable})")
    }

    fun stop() {
        client?.close()
        client = null
        process?.let { p -> runCatching { if (p.isAlive) p.destroy() } }
        process = null
        if (_state.value !is SidecarState.Failed) _state.value = SidecarState.Stopped
    }

    suspend fun restart(config: AppConfig) {
        stop()
        start(config)
    }

    /** Explicit first-run/offline model provisioning, including the local LLM. */
    suspend fun provisionOfflineModel(config: AppConfig, onProgress: (SidecarProvisioner.Progress) -> Unit): Result<Unit> {
        val dir = SidecarLocator.sidecarDir() ?: return Result.failure(IllegalStateException("no sidecar dir"))
        val dataDir = java.nio.file.Paths.get(config.dataDir)
        val token = UUID.randomUUID().toString().replace("-", "")
        return provisioner.ensureModels(dir, dataDir, buildEnv(config, token), includeOllama = true, onProgress = onProgress)
    }
}
