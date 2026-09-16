package com.transcribbio.desktop.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path

object ProcessRunner {
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
