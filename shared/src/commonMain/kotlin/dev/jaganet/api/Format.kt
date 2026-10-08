package dev.jaganet.api

import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Display helpers shared by the apps, the website, the bot and the owner dashboard.
 * Every function takes the interface language (English if not given).
 */
object Format {
    private fun oneDecimal(v: Double, lang: Lang): String {
        val r = (v * 10).roundToLong()
        val sep = if (lang == Lang.EN) "." else ","
        return "${r / 10}$sep${abs(r % 10)}"
    }

    private fun unit(u: String, lang: Lang) = if (lang == Lang.RU) mapOf("B" to "Б", "KB" to "КБ", "MB" to "МБ", "GB" to "ГБ", "TB" to "ТБ")[u]!! else u

    fun bytes(b: Long, lang: Lang = Lang.EN): String = when {
        b >= 1_000_000_000_000 -> "${oneDecimal(b / 1e12, lang)} ${unit("TB", lang)}"
        b >= 1_000_000_000 -> "${oneDecimal(b / 1e9, lang)} ${unit("GB", lang)}"
        b >= 1_000_000 -> "${(b / 1e6).roundToLong()} ${unit("MB", lang)}"
        b >= 1_000 -> "${(b / 1e3).roundToLong()} ${unit("KB", lang)}"
        else -> "$b ${unit("B", lang)}"
    }

    /** Megabits per second, without the unit. "-" when unknown. */
    fun mbps(bps: Long?, lang: Lang = Lang.EN): String = when {
        bps == null -> "-"
        bps >= 100_000_000 -> "${(bps / 1e6).roundToLong()}"
        else -> oneDecimal(bps / 1e6, lang)
    }

    fun duration(seconds: Long, lang: Lang = Lang.EN): String {
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val hh = I18n.tr(lang, "{n} h", "n" to h)
        val mm = I18n.tr(lang, "{n} min", "n" to if (h > 0) m.toString().padStart(2, '0') else m)
        return when {
            h >= 100 -> hh
            h > 0 -> "$hh $mm"
            else -> mm
        }
    }

    fun clock(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        fun p(n: Long) = n.toString().padStart(2, '0')
        return "${p(s / 3600)}:${p(s % 3600 / 60)}:${p(s % 60)}"
    }

    /** epoch ms -> "HH:mm:ss" (UTC) */
    fun clockOfDay(epochMs: Long): String = clock((epochMs / 1000) % 86_400)

    @OptIn(ExperimentalTime::class)
    fun ago(iso: String?, nowMs: Long, lang: Lang = Lang.EN): String {
        if (iso == null) return I18n.tr(lang, "never")
        val sec = ((nowMs - Instant.parse(iso).toEpochMilliseconds()) / 1000).coerceAtLeast(0)
        return when {
            sec < 60 -> I18n.tr(lang, "{n} s ago", "n" to sec)
            sec < 3600 -> I18n.tr(lang, "{n} min ago", "n" to sec / 60)
            sec < 86400 -> I18n.tr(lang, "{n} h ago", "n" to sec / 3600)
            else -> I18n.plural(lang, sec / 86400, "{n} day ago|{n} days ago")
        }
    }

    private val monthsEn = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private val monthsRu = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
    private val monthsDe = listOf("Januar", "Februar", "März", "April", "Mai", "Juni", "Juli", "August", "September", "Oktober", "November", "Dezember")

    /** "2026-10-05T…" -> "5 Oct 2026" / "5 октября 2026" / "5. Oktober 2026" */
    fun date(iso: String?, lang: Lang = Lang.EN): String {
        if (iso == null) return "-"
        val (y, m, d) = iso.take(10).split('-')
        val i = m.toInt() - 1
        return when (lang) {
            Lang.RU -> "${d.toInt()} ${monthsRu[i]} $y"
            Lang.DE -> "${d.toInt()}. ${monthsDe[i]} $y"
            Lang.EN -> "${d.toInt()} ${monthsEn[i]} $y"
        }
    }

    /** "$4.99" in English, "4,99 $" in Russian and German. */
    fun money(minor: Long, currency: String, lang: Lang = Lang.EN): String {
        val symbol = when (currency) { "USD" -> "$"; "EUR" -> "€"; "GBP" -> "£"; "RUB" -> "₽"; else -> currency }
        val whole = minor / 100
        val cents = (minor % 100).toString().padStart(2, '0')
        return when {
            lang == Lang.EN && symbol.length == 1 -> "$symbol$whole.$cents"
            lang == Lang.EN -> "$whole.$cents $symbol"
            cents == "00" && currency == "RUB" -> "$whole $symbol"
            else -> "$whole,$cents $symbol"
        }
    }
}
