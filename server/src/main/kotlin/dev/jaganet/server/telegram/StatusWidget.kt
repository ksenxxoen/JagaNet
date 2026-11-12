package dev.jaganet.server.telegram

import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.Instant

/**
 * The subscription status card in the chat with the bot (issue #140), a Telegram HTML widget
 * (Bot API 10.4, https://core.telegram.org/bots/widgets). Not a replacement for the mini app:
 * it only shows the status, the days left, the end date and the devices.
 *
 * The HTML (resources/telegram/status-widget.html) is fixed once sent, so the texts of one
 * language are put into it at sending; a language change sends a new card. What changes is the
 * card's chat data ([chatData]), which the bot updates with editMessageWidgetData. Widget data is
 * not secret: no subscription link and no keys ever go into the card.
 */
object StatusWidget {
    const val ACTIVE = "active"
    const val NONE = "none"
    const val ENDED = "ended"

    /** What the card shows. [expires] is null for access without an end (the owner); [from] is the start of the current access, for the bar. */
    data class State(val status: String, val expires: Instant?, val from: Instant?, val used: Int, val limit: Int)

    private val template: String by lazy {
        StatusWidget::class.java.getResource("/telegram/status-widget.html")!!.readText()
    }

    /** The card's HTML in [lang]. */
    fun html(lang: Lang): String {
        fun t(en: String) = I18n.tr(lang, en)
        val texts = buildJsonObject {
            put("lang", lang.code)
            put("on", t("Subscription active"))
            put("none", t("No active subscription"))
            put("ended", t("Subscription has ended"))
            put("until", t("until {date}"))
            put("endedOn", t("ended on {date}"))
            put("inf", t("No end date"))
            put("hint", t("Choose a plan with the button below."))
            put("left", t("{n} day left|{n} days left"))
            put("dev", t("Devices {used} of {limit}"))
        }
        // "</" would end the script early; "<\/" means the same in a JS string.
        return template.replace("{{LANG}}", lang.code).replace("{{L}}", texts.toString().replace("</", "<\\/"))
    }

    /** The card's chat data, e.g. {"v":1,"st":"active","exp":"…","from":"…","dev":[2,5],"r":17…}. Far below the 1 KB limit. */
    fun chatData(state: State, revision: Long): String = buildJsonObject {
        put("v", 1)
        put("st", state.status)
        state.expires?.let { put("exp", it.toString()) }
        state.from?.let { put("from", it.toString()) }
        if (state.status == ACTIVE) putJsonArray("dev") { add(state.used); add(state.limit) }
        put("r", JsonPrimitive(revision))
    }.toString()
}
