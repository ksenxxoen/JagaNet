package dev.jaganet.api.i18n

import dev.jaganet.api.i18n.strings.adminStrings
import dev.jaganet.api.i18n.strings.authStrings
import dev.jaganet.api.i18n.strings.commonStrings
import dev.jaganet.api.i18n.strings.devicesStrings
import dev.jaganet.api.i18n.strings.homeStrings
import dev.jaganet.api.i18n.strings.moneyStrings
import dev.jaganet.api.i18n.strings.referralStrings
import dev.jaganet.api.i18n.strings.serverStrings
import dev.jaganet.api.i18n.strings.settingsStrings
import dev.jaganet.api.i18n.strings.statsStrings
import dev.jaganet.api.i18n.strings.telegramStrings
import dev.jaganet.api.i18n.strings.webStrings

/** Interface languages. Russian is the default everywhere. */
enum class Lang(val code: String, val nativeName: String) {
    RU("ru", "Русский"),
    DE("de", "Deutsch"),
    EN("en", "English");

    companion object {
        val DEFAULT = RU

        /** "ru", "de-AT", "en-US,en;q=0.9" → the language, or null if it isn't one of ours. */
        fun of(code: String?): Lang? {
            val c = code?.trim()?.lowercase()?.take(2) ?: return null
            return entries.firstOrNull { it.code == c }
        }
    }
}

/**
 * One interface text. The English text is the key, so code stays readable:
 * t(english). Placeholders look like `{name}`. Plurals: forms separated by `|`
 * (English and German 2 forms, Russian 3: 1 устройство, 2 устройства, 5 устройств).
 */
class S(val en: String, val ru: String, val de: String)

object I18n {
    /** Every text of every product (apps, website, Telegram bot, server pages and errors). */
    val all: List<S> by lazy {
        commonStrings + authStrings + homeStrings + devicesStrings + statsStrings + moneyStrings +
            settingsStrings + adminStrings + serverStrings + telegramStrings + webStrings + referralStrings
    }

    private val tables: Map<Lang, Map<String, String>> by lazy {
        mapOf(Lang.RU to all.associate { it.en to it.ru }, Lang.DE to all.associate { it.en to it.de })
    }

    /** Translation of [en] (English text if missing), with `{placeholders}` filled from [args]. */
    fun tr(lang: Lang, en: String, vararg args: Pair<String, Any?>): String {
        val s = if (lang == Lang.EN) en else tables[lang]?.get(en) ?: en
        return fill(s, args)
    }

    /** [forms] is the English entry, e.g. "{n} device|{n} devices". */
    fun plural(lang: Lang, n: Long, forms: String, vararg args: Pair<String, Any?>): String {
        val variants = tr(lang, forms).split('|')
        val i = when (lang) {
            Lang.RU -> {
                val m10 = n % 10; val m100 = n % 100
                when {
                    m10 == 1L && m100 != 11L -> 0
                    m10 in 2..4 && m100 !in 12..14 -> 1
                    else -> 2
                }
            }
            else -> if (n == 1L) 0 else 1
        }
        return fill(variants[i.coerceAtMost(variants.lastIndex)], arrayOf("n" to n, *args))
    }

    /** The whole table for one language, for the website. */
    fun table(lang: Lang): Map<String, String> = if (lang == Lang.EN) emptyMap() else tables.getValue(lang)

    private fun fill(s: String, args: Array<out Pair<String, Any?>>): String =
        args.fold(s) { acc, (k, v) -> acc.replace("{$k}", v.toString()) }
}
