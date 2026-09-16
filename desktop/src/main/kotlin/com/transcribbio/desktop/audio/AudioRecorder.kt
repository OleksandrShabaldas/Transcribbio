package com.transcribbio.desktop.audio

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.RandomAccessFile
import java.nio.file.Path
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine
import kotlin.math.min
import kotlin.math.sqrt

/** Captures the desktop microphone to a 16 kHz mono WAV, streaming straight to
 *  disk (so multi-hour lectures don't sit in memory) with a live level meter. */
class AudioRecorder {
    private val sampleRate = 16000
    private val format = AudioFormat(sampleRate.toFloat(), 16, 1, true, false) // signed, little-endian

    private var line: TargetDataLine? = null
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var bytesWritten = 0L

    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    private val _level = MutableStateFlow(0f) // 0..1 RMS
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    fun isMicAvailable(): Boolean {
        val info = DataLine.Info(TargetDataLine::class.java, format)
        return AudioSystem.isLineSupported(info)
    }

    fun start(outFile: Path) {
        if (running) return
        val info = DataLine.Info(TargetDataLine::class.java, format)
        require(AudioSystem.isLineSupported(info)) { "No compatible microphone found" }
        val l = (AudioSystem.getLine(info) as TargetDataLine).apply {
            open(format)
            start()
        }
        line = l
        bytesWritten = 0
        running = true
        _recording.value = true

        val raf = RandomAccessFile(outFile.toFile(), "rw").apply {
            setLength(0)
            writeWavHeaderPlaceholder(this)
        }

        thread = Thread {
            val buf = ByteArray(4096)
            val startNs = System.nanoTime()
            try {
                while (running) {
                    val n = l.read(buf, 0, buf.size)
                    if (n > 0) {
                        raf.write(buf, 0, n)
                        bytesWritten += n
                        _level.value = rms16le(buf, n)
                        _elapsedMs.value = (System.nanoTime() - startNs) / 1_000_000
                    }
                }
            } finally {
                patchWavSizes(raf, bytesWritten)
                raf.close()
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** Stops recording and returns the captured duration in seconds. */
    fun stop(): Double {
        if (!running) return 0.0
        running = false
        line?.let { it.stop(); it.close() }
        line = null
        thread?.join(3000)
        thread = null
        _recording.value = false
        _level.value = 0f
        val frames = bytesWritten / 2
        return frames.toDouble() / sampleRate
    }

    private fun rms16le(buf: ByteArray, n: Int): Float {
        var sum = 0.0
        var i = 0
        val samples = n / 2
        while (i + 1 < n) {
            val s = (buf[i].toInt() and 0xFF) or (buf[i + 1].toInt() shl 8)
            val v = s / 32768.0
            sum += v * v
            i += 2
        }
        if (samples == 0) return 0f
        return min(1.0, sqrt(sum / samples) * 3.0).toFloat() // scaled for visibility
    }

    private fun writeWavHeaderPlaceholder(raf: RandomAccessFile) {
        val byteRate = sampleRate * 2
        raf.seek(0)
        raf.writeBytes("RIFF")
        raf.writeIntLE(0)             // file size - 8 (patched later)
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.writeIntLE(16)            // PCM header size
        raf.writeShortLE(1)          // PCM
        raf.writeShortLE(1)          // mono
        raf.writeIntLE(sampleRate)
        raf.writeIntLE(byteRate)
        raf.writeShortLE(2)          // block align
        raf.writeShortLE(16)         // bits per sample
        raf.writeBytes("data")
        raf.writeIntLE(0)            // data size (patched later)
    }

    private fun patchWavSizes(raf: RandomAccessFile, dataBytes: Long) {
        raf.seek(4)
        raf.writeIntLE((36 + dataBytes).toInt())
        raf.seek(40)
        raf.writeIntLE(dataBytes.toInt())
    }

    private fun RandomAccessFile.writeIntLE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF); write((v shr 16) and 0xFF); write((v shr 24) and 0xFF)
    }

    private fun RandomAccessFile.writeShortLE(v: Int) {
        write(v and 0xFF); write((v shr 8) and 0xFF)
    }
}
