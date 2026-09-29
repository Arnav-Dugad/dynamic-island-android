package com.arnav.island.util

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

object Formatters {

    /** 4:05, 12:05, 1:02:05. Rounds up so a timer never shows 0:00 while time remains. */
    fun countdown(ms: Long): String = clock(((ms + 999) / 1000).coerceAtLeast(0))

    /** Elapsed time, rounding down. */
    fun elapsed(ms: Long): String = clock((ms / 1000).coerceAtLeast(0))

    fun clock(totalSeconds: Long): String {
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    /** 01:23.45 style for the stopwatch card. */
    fun stopwatch(ms: Long): String {
        val totalCs = (ms / 10).coerceAtLeast(0)
        val cs = totalCs % 100
        val totalS = totalCs / 100
        val h = totalS / 3600
        val m = (totalS % 3600) / 60
        val s = totalS % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d.%02d", h, m, s, cs)
        } else {
            String.format(Locale.US, "%02d:%02d.%02d", m, s, cs)
        }
    }

    /** "1h 12m", "35m", "< 1m". */
    fun humanDuration(ms: Long): String {
        val minutes = (ms / 60_000.0).roundToLong()
        return when {
            minutes < 1 -> "< 1m"
            minutes < 60 -> "${minutes}m"
            else -> {
                val h = minutes / 60
                val m = minutes % 60
                if (m == 0L) "${h}h" else "${h}h ${m}m"
            }
        }
    }

    fun bytesPerSecond(bps: Long): String {
        val v = abs(bps).toDouble()
        return when {
            v < 1_000 -> "${v.toLong()} B/s"
            v < 1_000_000 -> String.format(Locale.US, "%.0f KB/s", v / 1_000)
            v < 10_000_000 -> String.format(Locale.US, "%.1f MB/s", v / 1_000_000)
            else -> String.format(Locale.US, "%.0f MB/s", v / 1_000_000)
        }
    }

    fun bytes(b: Long): String {
        val gb = b / 1_073_741_824.0
        return if (gb >= 1) String.format(Locale.US, "%.1f GB", gb) else String.format(Locale.US, "%.0f MB", b / 1_048_576.0)
    }

    fun temperature(c: Float): String = String.format(Locale.getDefault(), "%.1f °C", c)

    fun percent(fraction: Float): String = "${(fraction * 100f).roundToLong().coerceIn(0, 100)}%"
}
