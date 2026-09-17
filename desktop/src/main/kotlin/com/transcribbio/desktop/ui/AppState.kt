package com.transcribbio.desktop.ui

import com.transcribbio.desktop.audio.AudioRecorder
import com.transcribbio.desktop.core.AppConfig
import com.transcribbio.desktop.core.AppEnvironment
import com.transcribbio.desktop.core.ConfigStore
import com.transcribbio.desktop.data.LibraryRepository
import com.transcribbio.desktop.processing.ProcessingOrchestrator
import com.transcribbio.desktop.sidecar.SidecarManager
import com.transcribbio.desktop.sidecar.SidecarState
import com.transcribbio.desktop.sync.SyncServer
import com.transcribbio.desktop.update.DesktopUpdater
import java.util.UUID
import com.transcribbio.shared.model.Lecture
import com.transcribbio.shared.model.LectureStatus
import com.transcribbio.shared.model.StudyMaterialKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.nio.file.Paths
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

sealed interface Screen {
    data object Library : Screen
    data class Detail(val lectureId: String) : Screen
    data object Settings : Screen
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

private const val APP_VERSION = "1.0.1"

class AppState {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val env: AppEnvironment
    private val configStore: ConfigStore
    val repo: LibraryRepository
    val sidecar = SidecarManager(scope)
    val orchestrator: ProcessingOrchestrator
    val recorder = AudioRecorder()
    val syncServer: SyncServer
    val updater: DesktopUpdater
    val appVersion: String = APP_VERSION

    private val _config: MutableStateFlow<AppConfig>
    val config: StateFlow<AppConfig>

    private val _screen = MutableStateFlow<Screen>(Screen.Library)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    val themeMode = MutableStateFlow(ThemeMode.SYSTEM)

    private val _offlineProvisionMsg = MutableStateFlow<String?>(null)
    val offlineProvisionMsg: StateFlow<String?> = _offlineProvisionMsg.asStateFlow()

    // In-progress recording bookkeeping
    private var recordingLecture: Lecture? = null
    private var recordingAudioPath: Path? = null

    init {
        val initialConfig = ConfigStore(AppEnvironment(AppEnvironment.defaultDataDir())).load()
        env = AppEnvironment(Paths.get(initialConfig.dataDir))
        env.ensureDirs()
        configStore = ConfigStore(env)
        _config = MutableStateFlow(initialConfig.copy(dataDir = env.dataDir.toString()))
        config = _config.asStateFlow()
        repo = LibraryRepository(env)
        orchestrator = ProcessingOrchestrator(sidecar, repo) { _config.value }

        // Ensure a stable sync identity + pairing token (generated once, persisted).
        var cfg = _config.value
        if (cfg.desktopDeviceId.isBlank() || cfg.syncToken.isBlank() || cfg.desktopName.isBlank()) {
            cfg = cfg.copy(
                desktopDeviceId = cfg.desktopDeviceId.ifBlank { UUID.randomUUID().toString().substring(0, 8) },
                syncToken = cfg.syncToken.ifBlank { UUID.randomUUID().toString().replace("-", "") },
                desktopName = cfg.desktopName.ifBlank { defaultDesktopName() },
            )
            _config.value = cfg
            configStore.save(cfg)
        }
        syncServer = SyncServer(repo, { _config.value }, APP_VERSION) { id -> maybeProcess(id) }
        updater = DesktopUpdater(APP_VERSION, env.dataDir.toFile(), scope)

        repo.loadAll()
        scope.launch { sidecar.start(_config.value) }
        if (cfg.syncEnabled) syncServer.start()
        updater.checkOnLaunch()
    }

    private fun defaultDesktopName(): String =
        System.getenv("COMPUTERNAME") ?: System.getProperty("user.name")?.let { "$it's PC" } ?: "Transcribbio Desktop"

    // ── Navigation ──
    fun navigate(screen: Screen) { _screen.value = screen }
    fun back() { _screen.value = Screen.Library }

    // ── Recording ──
    fun micAvailable() = recorder.isMicAvailable()

    fun startRecording(title: String) {
        val name = title.ifBlank { defaultTitle() }
        val (lecture, audioPath) = repo.createForRecording(name, _config.value.language)
        recordingLecture = lecture
        recordingAudioPath = audioPath
        recorder.start(audioPath)
    }

    fun cancelRecording() {
        recorder.stop()
        recordingLecture?.let { repo.delete(it.id) }
        recordingLecture = null
        recordingAudioPath = null
    }

    fun stopRecordingAndProcess() {
        val duration = recorder.stop()
        val lecture = recordingLecture ?: return
        val saved = lecture.copy(durationS = duration, status = LectureStatus.RECORDED)
        repo.save(saved)
        recordingLecture = null
        recordingAudioPath = null
        maybeProcess(saved.id)
    }

    // ── Import ──
    fun importAudio(path: Path) {
        val title = path.fileName.toString().substringBeforeLast('.')
        val lecture = repo.importAudio(path, title.ifBlank { defaultTitle() }, _config.value.language)
        maybeProcess(lecture.id)
    }

    // ── Processing ──
    fun processLecture(id: String) = scope.launch { orchestrator.process(id) }

    private fun maybeProcess(id: String) {
        if (sidecar.state.value is SidecarState.Ready) {
            processLecture(id)
        }
    }

    fun generateMaterial(id: String, kind: StudyMaterialKind) =
        scope.launch { orchestrator.generateMaterial(id, kind) }

    fun reCorrect(id: String) = scope.launch { orchestrator.reCorrect(id) }

    fun deleteLecture(id: String) {
        repo.delete(id)
        if (_screen.value == Screen.Detail(id)) back()
    }

    fun renameLecture(id: String, title: String) {
        repo.get(id)?.let { repo.save(it.copy(title = title)) }
    }

    // ── Settings ──
    fun saveSettings(newConfig: AppConfig) {
        val old = _config.value
        _config.value = newConfig
        configStore.save(newConfig)
        val needsRestart = old.geminiApiKey != newConfig.geminiApiKey ||
            old.llmPolicy != newConfig.llmPolicy ||
            old.language != newConfig.language ||
            old.whisperModel != newConfig.whisperModel ||
            old.device != newConfig.device
        if (needsRestart) scope.launch { sidecar.restart(newConfig) }
    }

    fun retrySidecar() = scope.launch { sidecar.restart(_config.value) }

    fun provisionOfflineModel() = scope.launch {
        _offlineProvisionMsg.value = "Starting…"
        val res = sidecar.provisionOfflineModel(_config.value) { p ->
            _offlineProvisionMsg.value = p.message
        }
        _offlineProvisionMsg.value = if (res.isSuccess) "Offline model ready." else "Failed: ${res.exceptionOrNull()?.message}"
    }

    fun markFirstRunComplete() {
        if (!_config.value.firstRunComplete) saveSettings(_config.value.copy(firstRunComplete = true))
    }

    fun shutdown() {
        recorder.stop()
        syncServer.stop()
        sidecar.stop()
    }

    private fun defaultTitle(): String =
        "Lecture " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))
}
