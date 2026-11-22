package com.breakthebarriers.unbar.ui.components

import java.util.Locale

// Amounts are mathematical (left-to-right): prefix with LRM (U+200E) so the
// Persian RTL locale renders "1.1 MB" instead of flipping it to "MB 1.1".
// formatSpeedBps inherits the prefix (it appends "/s" to formatBytes).
fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "\u200E0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var idx = 0
    while (value >= 1024.0 && idx < units.lastIndex) {
        value /= 1024.0
        idx++
    }
    return if (idx == 0) {
        "\u200E$bytes B"
    } else {
        "\u200E" + String.format(Locale.US, "%.1f %s", value, units[idx])
    }
}

fun formatSpeedBps(bytesPerSec: Long): String {
    if (bytesPerSec <= 0L) return "0 B/s"
    return "${formatBytes(bytesPerSec)}/s"
}

fun formatElapsed(sinceMs: Long?, nowMs: Long): String {
    if (sinceMs == null) return "00:00:00"
    val totalSec = ((nowMs - sinceMs) / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}
