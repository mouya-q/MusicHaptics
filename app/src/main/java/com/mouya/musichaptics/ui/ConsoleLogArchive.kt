package com.mouya.musichaptics.ui

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConsoleLogArchive {
    private const val DIRECTORY = "logs"
    private const val FILE_NAME = "console.log"
    private const val MAX_LINES = 300
    private const val MAX_BYTES = 192 * 1024
    private const val FLUSH_INTERVAL_MS = 750L
    private const val FLUSH_CHAR_THRESHOLD = 4096

    // Lines are buffered in memory and flushed in batches; the old code issued
    // a file open/write/close per log line, which shows up as UI-process I/O
    // storms when the hooked apps stream diagnostics.
    private val pending = StringBuilder()
    private var lastFlushMs = 0L
    private var flushesSinceSizeCheck = 0

    private fun file(context: Context): File = File(context.filesDir, DIRECTORY).apply { mkdirs() }
        .resolve(FILE_NAME)

    @Synchronized
    fun load(context: Context): List<String> = try {
        file(context).takeIf { it.exists() }?.readLines()?.takeLast(MAX_LINES) ?: emptyList()
    } catch (_: Exception) { emptyList() }

    @Synchronized
    fun replace(context: Context, lines: List<String>) {
        try {
            pending.setLength(0)
            val target = file(context)
            val retained = lines.takeLast(MAX_LINES)
            target.writeText(retained.joinToString(separator = "\n", postfix = if (retained.isEmpty()) "" else "\n"))
        } catch (_: Exception) { }
    }

    @Synchronized
    fun append(context: Context, line: String) {
        try {
            pending.append(line).append('\n')
            val now = android.os.SystemClock.elapsedRealtime()
            if (pending.length >= FLUSH_CHAR_THRESHOLD || now - lastFlushMs >= FLUSH_INTERVAL_MS) {
                flushLocked(context, now)
            }
        } catch (_: Exception) { }
    }

    /** Persists any buffered lines; safe to call from lifecycle onStop(). */
    @Synchronized
    fun flush(context: Context) {
        try {
            if (pending.isNotEmpty()) flushLocked(context, android.os.SystemClock.elapsedRealtime())
        } catch (_: Exception) { }
    }

    private fun flushLocked(context: Context, nowMs: Long) {
        if (pending.isEmpty()) return
        val target = file(context)
        target.appendText(pending.toString())
        pending.setLength(0)
        lastFlushMs = nowMs
        // Size capping is amortized: checking length on every line is a syscall
        // per log entry, so only re-trim every 16 flushes.
        if (++flushesSinceSizeCheck >= 16) {
            flushesSinceSizeCheck = 0
            if (target.length() > MAX_BYTES) {
                replace(context, target.readLines().takeLast(MAX_LINES))
            }
        }
    }

    @Synchronized
    fun exportToDownloads(context: Context, lines: List<String>): Result<String> = runCatching {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "MusicHapticsX_log_$stamp.txt"
        val content = buildString {
            append("MusicHapticsX dashboard log export\n")
            append("Exported: ").append(Date()).append("\n\n")
            lines.forEach { append(it).append('\n') }
        }
        require(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "Android 9 requires storage permission to export to Download"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MusicHapticsX")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create Download file")
        try {
            resolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                ?: error("Unable to open Download file")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Exception) {
            resolver.delete(uri, null, null)
            throw error
        }
        "Download/MusicHapticsX/$name"
    }
}