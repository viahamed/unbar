package com.breakthebarriers.unbar.data.logs

import com.breakthebarriers.unbar.model.LogEntry
import com.breakthebarriers.unbar.model.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe, size-capped ring buffer for diagnostic logs.
 * Bounded to [MAX_ENTRIES] so memory and storage remain minimal.
 */
class LogStore {
    companion object {
        const val MAX_ENTRIES = 500
    }

    private val lock = Any()
    private val idGen = AtomicLong(1L)
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    // Diagnostic logging switch (Settings > System & Diagnostics). When
    // off, INFO/WARN entries are not recorded at all, so the log buffer
    // (and the work that fills it) costs no battery or resources.
    // ERROR entries are always kept — they are rare and needed to see
    // that something failed at all.
    @Volatile
    var enabled: Boolean = true

    fun info(tag: String, message: String) = append(LogLevel.INFO, tag, message)
    fun warn(tag: String, message: String) = append(LogLevel.WARN, tag, message)
    fun error(tag: String, message: String) = append(LogLevel.ERROR, tag, message)

    private fun append(level: LogLevel, tag: String, message: String) {
        if (!enabled && level != LogLevel.ERROR) return
        val entry = LogEntry(
            id = idGen.getAndIncrement(),
            timestampMs = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message.take(1024)
        )
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            _logs.value = buffer.toList()
        }
    }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _logs.value = emptyList()
        }
    }

    fun formatForExport(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        val snapshot = synchronized(lock) { buffer.toList() }
        return snapshot.joinToString("\n") { entry ->
            val ts = formatter.format(Date(entry.timestampMs))
            "[$ts] [${entry.level.name}] [${entry.tag}] ${entry.message}"
        }
    }
}
