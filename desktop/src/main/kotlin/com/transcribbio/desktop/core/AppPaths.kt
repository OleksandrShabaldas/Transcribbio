package com.transcribbio.desktop.core

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Resolves per-user data locations. dataDir is user-configurable at runtime. */
class AppEnvironment(val dataDir: Path) {
    val libraryDir: Path = dataDir.resolve("library")
    val modelsDir: Path = dataDir.resolve("models")
    val logsDir: Path = dataDir.resolve("logs")
    val configFile: Path = dataDir.resolve("desktop-config.json")

    fun ensureDirs() {
        listOf(dataDir, libraryDir, modelsDir, logsDir).forEach { Files.createDirectories(it) }
    }

    companion object {
        fun defaultDataDir(): Path {
            System.getenv("TRANSCRIBBIO_DATA_DIR")?.let { return Paths.get(it) }
            System.getenv("LOCALAPPDATA")?.let { return Paths.get(it, "Transcribbio") }
            return Paths.get(System.getProperty("user.home"), ".transcribbio")
        }
    }
}

/** Locates the Python ML sidecar and a usable Python interpreter. */
object SidecarLocator {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /** The ml-sidecar directory: env override -> bundled app resources -> repo search. */
    fun sidecarDir(): Path? {
        System.getenv("TRANSCRIBBIO_SIDECAR_DIR")?.let {
            val p = Paths.get(it)
            if (Files.isDirectory(p)) return p
        }
        // Packaged app: Compose sets this to the appResources dir.
        System.getProperty("compose.application.resources.dir")?.let {
            val p = Paths.get(it, "ml-sidecar")
            if (Files.isDirectory(p)) return p
        }
        // Dev: walk up from the working directory to find ml-sidecar/.
        var dir: File? = File(System.getProperty("user.dir"))
        repeat(6) {
            val candidate = dir?.resolve("ml-sidecar")
            if (candidate != null && candidate.isDirectory) return candidate.toPath()
            dir = dir?.parentFile
        }
        return null
    }

    private fun venvPythonInside(venvDir: Path): Path =
        if (isWindows) venvDir.resolve("Scripts/python.exe") else venvDir.resolve("bin/python")

    /** The venv that lives under the (writable) data dir — survives app updates. */
    fun dataVenvDir(dataDir: Path): Path = dataDir.resolve("runtime/venv")

    /** Sidecar interpreter: the dev venv in the source dir if present (development),
     *  otherwise the data-dir venv (installed app). */
    fun venvPython(sidecarDir: Path, dataDir: Path): Path {
        val devVenv = venvPythonInside(sidecarDir.resolve(".venv"))
        if (Files.isExecutable(devVenv)) return devVenv
        return venvPythonInside(dataVenvDir(dataDir))
    }

    fun venvReady(sidecarDir: Path, dataDir: Path): Boolean =
        Files.isExecutable(venvPython(sidecarDir, dataDir))

    /** Find a system Python capable of creating the venv. Returns the launcher argv. */
    fun systemPython(): List<String>? {
        val candidates = if (isWindows)
            listOf(listOf("py", "-3.12"), listOf("py", "-3"), listOf("python"), listOf("python3"))
        else
            listOf(listOf("python3"), listOf("python"))
        for (c in candidates) {
            try {
                val p = ProcessBuilder(c + "--version").redirectErrorStream(true).start()
                if (p.waitFor() == 0) return c
            } catch (_: Exception) {
                // try next
            }
        }
        return null
    }
}
