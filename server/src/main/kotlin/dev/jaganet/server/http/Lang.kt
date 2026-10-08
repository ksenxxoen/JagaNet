package dev.jaganet.server.http

import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall

/**
 * API calls: the language the app or website asks for (Accept-Language, which both set
 * explicitly), else Russian. Pages (key link, checkout): ?lang=, then the website's "lang"
 * cookie, else Russian. A browser's own Accept-Language is ignored there on purpose:
 * Russian is the default until the user picks another language.
 */
fun ApplicationCall.apiLang(): Lang = Lang.of(request.headers[HttpHeaders.AcceptLanguage]) ?: Lang.DEFAULT

fun ApplicationCall.pageLang(): Lang =
    Lang.of(request.queryParameters["lang"]) ?: Lang.of(request.cookies["lang"]) ?: Lang.DEFAULT

/** Translates an English message; plural messages ("…|…") take their count from args["n"]. */
fun translate(lang: Lang, en: String, args: Map<String, Any?> = emptyMap()): String {
    val pairs = args.map { (k, v) -> k to v }.toTypedArray()
    val n = (args["n"] as? Number)?.toLong()
    return if ('|' in en && n != null) I18n.plural(lang, n, en, *pairs) else I18n.tr(lang, en, *pairs)
}
