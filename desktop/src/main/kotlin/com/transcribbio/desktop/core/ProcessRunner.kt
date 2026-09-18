package com.transcribbio.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path

object ProcessRunner {
    /** Remove this JVM's runtime (java.home) directories from the child process's PATH.
     *  Critical for the bundled desktop app: otherwise the spawned Python loads the JVM's
     *  own MSVCP140.dll from `…/runtime/bin` and crashes with 0xC0000005. */
    fun stripJvmFromPath(pb: ProcessBuilder) {
        val javaHome = runCatching { File(System.getProperty("java.home")).canonicalPath }.getOrNull() ?: return
        val envMap = pb.environment()
        val key = envMap.keys.firstOrNull { it.equals("PATH", ignoreCase = true) } ?: return
        val sep = File.pathSeparator
        val cleaned = (envMap[key] ?: "").split(sep).filter { seg ->
            if (seg.isBlank()) return@filter false
            val canon = runCatching { File(seg).canonicalPath }.getOrDefault(seg)
            !(canon.equals(javaHome, true) || canon.startsWith(javaHome + File.separator, true))
        }.joinToString(sep)
        envMap[key] = cleaned
    }

    /** Run a process to completion, streaming each merged stdout/stderr line to [onLine].
     *  Returns the exit code. Runs on the IO dispatcher. */
    suspend fun run(
        command: List<String>,
        workingDir: Path? = null,
        env: Map<String, String> = emptyMap(),
        onLine: (String) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        workingDir?.let { pb.directory(it.toFile()) }
        pb.environment().putAll(env)
        stripJvmFromPath(pb)
        val proc = pb.start()
        BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                onLine(line!!)
            }
        }
        proc.waitFor()
    }
}
