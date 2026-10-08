package dev.jaganet.app.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jaganet.api.Format
import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang

/** The interface language. Snapshot state: screens recompose when it changes. */
object AppLang {
    var current by mutableStateOf(Lang.DEFAULT)
}

/** Translated text. The English text is the key, placeholders are filled from args: t(english, "name" to n). */
fun t(en: String, vararg args: Pair<String, Any?>): String = I18n.tr(AppLang.current, en, *args)

/** Plural: `tp(3, "{n} device|{n} devices")`. */
fun tp(n: Number, forms: String, vararg args: Pair<String, Any?>): String = I18n.plural(AppLang.current, n.toLong(), forms, *args)

fun fDate(iso: String?): String = Format.date(iso, AppLang.current)
fun fBytes(b: Long): String = Format.bytes(b, AppLang.current)
fun fMbps(bps: Long?): String = Format.mbps(bps, AppLang.current)
fun fDuration(seconds: Long): String = Format.duration(seconds, AppLang.current)
fun fAgo(iso: String?, nowMs: Long): String = Format.ago(iso, nowMs, AppLang.current)
fun fMoney(minor: Long, currency: String): String = Format.money(minor, currency, AppLang.current)
