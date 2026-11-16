package com.kaivoflow.feature.canvas.inspector.panels

import java.util.Locale

/** Small text formatters shared by the panels. Pure, so they are tested on the JVM. */
object PanelFormat {
    /** `812 B`, `2 KB`, `1.4 MB`. */
    fun bytes(n: Long): String {
        val v = n.coerceAtLeast(0)
        return when {
            v < 1024 -> "$v B"
            v < 1024L * 1024 -> trim(v / 1024.0) + " KB"
            v < 1024L * 1024 * 1024 -> trim(v / (1024.0 * 1024)) + " MB"
            else -> trim(v / (1024.0 * 1024 * 1024)) + " GB"
        }
    }

    private fun trim(d: Double): String {
        val s = String.format(Locale.US, "%.1f", d)
        return if (s.endsWith(".0")) s.dropLast(2) else s
    }

    /** `just now`, `4 min ago`, `2 h ago`, `3 d ago`. */
    fun age(nowMs: Long, thenMs: Long): String {
        val sec = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
        return when {
            sec < 45 -> "just now"
            sec < 3600 -> "${max1(sec / 60)} min ago"
            sec < 86400 -> "${sec / 3600} h ago"
            else -> "${sec / 86400} d ago"
        }
    }

    private fun max1(v: Long) = if (v < 1) 1 else v

    /** `Pinned, 3 items, 2 KB, 4 min ago`. */
    fun pinnedLine(itemCount: Int, bytes: Long, nowMs: Long, pinnedAt: Long): String =
        "Pinned, $itemCount ${if (itemCount == 1) "item" else "items"}, ${bytes(bytes)}, ${age(nowMs, pinnedAt)}"

    /** `Run 2 of 5` for 1-based [position]. */
    fun runLabel(position: Int, total: Int): String = "Run $position of $total"

    /** Short checksum for display: first 8 and last 4 hex digits. */
    fun shortChecksum(c: String): String = if (c.length <= 16) c else c.take(8) + "…" + c.takeLast(4)
}
