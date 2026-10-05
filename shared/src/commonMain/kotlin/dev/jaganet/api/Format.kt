package dev.jaganet.api

import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Display helpers shared by the apps and the owner dashboard. */
object Format {
    private fun oneDecimal(v: Double): String {
        val r = (v * 10).roundToLong()
        return "${r / 10}.${abs(r % 10)}"
    }

    fun bytes(b: Long): String = when {
        b >= 1_000_000_000_000 -> "${oneDecimal(b / 1e12)} TB"
        b >= 1_000_000_000 -> "${oneDecimal(b / 1e9)} GB"
        b >= 1_000_000 -> "${(b / 1e6).roundToLong()} MB"
        b >= 1_000 -> "${(b / 1e3).roundToLong()} KB"
        else -> "$b B"
    }

    fun mbps(bps: Long?): String = when {
        bps == null -> "–"
        bps >= 100_000_000 -> "${(bps / 1e6).roundToLong()}"
        else -> oneDecimal(bps / 1e6)
    }

    fun duration(seconds: Long): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return when {
            h >= 100 -> "$h h"
            h > 0 -> "$h h ${m.toString().padStart(2, '0')} m"
            else -> "$m m"
        }
    }

    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        fun p(n: Long) = n.toString().padStart(2, '0')
        return "${p(s / 3600)}:${p(s % 3600 / 60)}:${p(s % 60)}"
    }

    @OptIn(ExperimentalTime::class)
    fun ago(iso: String?, nowMs: Long): String {
        if (iso == null) return "never"
        val sec = ((nowMs - Instant.parse(iso).toEpochMilliseconds()) / 1000).coerceAtLeast(0)
        return when {
            sec < 60 -> "$sec s ago"
            sec < 3600 -> "${sec / 60} min ago"
            sec < 86400 -> "${sec / 3600} h ago"
            else -> (sec / 86400).let { d -> "$d day${if (d == 1L) "" else "s"} ago" }
        }
    }

    /** "2026-10-05T…" -> "5 Oct 2026" */
    fun date(iso: String?): String {
        if (iso == null) return "–"
        val (y, m, d) = iso.take(10).split('-')
        val months = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        return "${d.toInt()} ${months[m.toInt() - 1]} $y"
    }

    fun money(minor: Long, currency: String): String {
        val symbol = when (currency) { "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; else -> "$currency " }
        return "$symbol${minor / 100}.${(minor % 100).toString().padStart(2, '0')}"
    }
}
