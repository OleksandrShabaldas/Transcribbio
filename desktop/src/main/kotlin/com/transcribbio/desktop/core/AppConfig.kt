package com.transcribbio.desktop.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

@Serializable
enum class LlmPolicy {
    @SerialName("gemini_then_ollama") GEMINI_THEN_OLLAMA,
    @SerialName("ollama_only") OLLAMA_ONLY,
    @SerialName("gemini_only") GEMINI_ONLY;

    val api: String
        get() = when (this) {
            GEMINI_THEN_OLLAMA -> "gemini_then_ollama"
            OLLAMA_ONLY -> "ollama_only"
            GEMINI_ONLY -> "gemini_only"
        }
}

/** Master configuration owned by the desktop app; relevant values are injected
 *  into the ML sidecar as environment variables when it is spawned. */
@Serializable
data class AppConfig(
    val dataDir: String = AppEnvironment.defaultDataDir().toString(),
    val geminiApiKey: String = "",
    val llmPolicy: LlmPolicy = LlmPolicy.GEMINI_THEN_OLLAMA,
    val language: String = "sk",
    val whisperModel: String = "large-v3",
    val ollamaModel: String = "qwen2.5:7b-instruct",
    val device: String = "auto",
    /** Study materials generated automatically right after correction. */
    val autoGenerateMaterials: List<String> = listOf("summary", "notes"),
    val firstRunComplete: Boolean = false,

    // ── Wi-Fi sync (phone/watch → desktop) ──
    val syncEnabled: Boolean = true,
    val syncToken: String = "",        // shared secret handed to paired devices
    val desktopDeviceId: String = "",  // stable id advertised over mDNS
    val desktopName: String = "",      // human-friendly name shown on devices
) {
    fun hasGeminiKey() = geminiApiKey.isNotBlank()
}

class ConfigStore(private val env: AppEnvironment) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(): AppConfig {
        val f = env.configFile
        if (!Files.exists(f)) return AppConfig(dataDir = env.dataDir.toString())
        return try {
            json.decodeFromString(AppConfig.serializer(), Files.readString(f))
        } catch (_: Exception) {
            AppConfig(dataDir = env.dataDir.toString())
        }
    }

    fun save(config: AppConfig) {
        env.ensureDirs()
        val tmp: Path = env.configFile.resolveSibling("desktop-config.json.tmp")
        Files.writeString(tmp, json.encodeToString(AppConfig.serializer(), config))
        Files.move(tmp, env.configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
