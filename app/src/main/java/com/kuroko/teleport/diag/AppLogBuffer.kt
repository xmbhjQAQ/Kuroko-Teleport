package com.kuroko.teleport.diag

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val code: Char) {
    INFO('I'),
    WARN('W'),
    ERROR('E')
}

data class AppLogEntry(
    val timestampMs: Long,
    val formattedTime: String,
    val level: LogLevel,
    val tag: String,
    val message: String
) {
    fun formatLine(): String = "$formattedTime [${level.code}] [$tag] $message"
}

/**
 * Lightweight, process-wide, thread-safe in-memory ring buffer for non-sensitive App events.
 * Never logs auth tokens, API keys, raw coordinates, or user route data.
 */
object AppLogBuffer {
    const val MAX_ENTRIES = 400
    private const val MAX_MESSAGE_CHARS = 480

    private val lock = Any()
    private val entries = ArrayDeque<AppLogEntry>(MAX_ENTRIES)
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun i(tag: String, msg: String) = append(LogLevel.INFO, tag, msg)

    fun w(tag: String, msg: String) = append(LogLevel.WARN, tag, msg)

    fun e(tag: String, msg: String) = append(LogLevel.ERROR, tag, msg)

    private fun append(level: LogLevel, tag: String, msg: String) {
        val now = System.currentTimeMillis()
        val cleanTag = tag.replace('\r', ' ').replace('\n', ' ').trim().ifEmpty { "App" }
        val cleanMsg = msg
            .replace('\r', ' ')
            .replace('\n', ' ')
            .trim()
            .let { if (it.length > MAX_MESSAGE_CHARS) it.take(MAX_MESSAGE_CHARS) + "…" else it }
        synchronized(lock) {
            if (entries.size >= MAX_ENTRIES) {
                entries.removeFirst()
            }
            val formatted = timeFormat.format(Date(now))
            entries.addLast(
                AppLogEntry(
                    timestampMs = now,
                    formattedTime = formatted,
                    level = level,
                    tag = cleanTag,
                    message = cleanMsg
                )
            )
        }
    }

    fun entriesSnapshot(): List<AppLogEntry> = synchronized(lock) {
        entries.toList()
    }

    fun dumpText(errorsOnly: Boolean = false): String = synchronized(lock) {
        val sb = StringBuilder()
        var first = true
        for (entry in entries) {
            if (errorsOnly && entry.level == LogLevel.INFO) continue
            if (!first) sb.append('\n')
            sb.append(entry.formatLine())
            first = false
        }
        sb.toString()
    }

    fun clear() = synchronized(lock) {
        entries.clear()
    }
}
