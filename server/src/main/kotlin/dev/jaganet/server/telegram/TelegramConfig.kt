package dev.jaganet.server.telegram

/**
 * The bot's settings from the environment (issue #38). The token itself stays in
 * [dev.jaganet.server.Config.telegramBotToken] (TELEGRAM_BOT_TOKEN) and is never logged.
 */
data class TelegramConfig(
    /** "polling" (default: works behind NAT and when Telegram is reachable only outbound) or "webhook". */
    val mode: String = POLLING,
    /** Webhook mode: the public URL Telegram posts to. Default: PUBLIC_URL + [WEBHOOK_PATH]. */
    val webhookUrl: String? = null,
    /** Webhook mode: Telegram sends it in X-Telegram-Bot-Api-Secret-Token. Default: derived from AUTH_SECRET. */
    val webhookSecret: String? = null,
    /** Chat (a person or a group) that gets support messages and replies to them. Default: the first alert chat. */
    val supportChatId: Long? = null,
    /** Show the Telegram Login Widget on the sign-in page (needs /setdomain in @BotFather first). */
    val loginWidget: Boolean = false,
    /** Bot API server; a local telegram-bot-api server or a reverse proxy can stand in for api.telegram.org. */
    val apiUrl: String = "https://api.telegram.org",
    /** HTTP proxy for the Bot API, "host:port" or "http://host:port". */
    val proxy: String? = null,
    /** The mini app (issue #87): page /tg/app, sign-in with initData, the bot's menu button and "Open Boop" buttons. Off by default. */
    val miniApp: Boolean = false,
    /** Where the mini app opens. Default: PUBLIC_URL + [MINIAPP_PATH]. */
    val miniAppUrl: String? = null,
    /** The project's Telegram group or channel (issue #91): buttons in the bot, the mini app and the website. Null = none. */
    val communityUrl: String? = null,
) {
    val webhook get() = mode == WEBHOOK

    /**
     * The mini app's address for the bot's buttons, or null when the mini app is off. Telegram takes
     * only https:// for web_app buttons (anything else makes the whole message fail), so a plain-http
     * address (a local test panel) also gives null and the bot keeps its usual buttons.
     */
    fun miniAppUrl(publicUrl: String): String? {
        if (!miniApp) return null
        val url = miniAppUrl ?: (publicUrl.trimEnd('/') + MINIAPP_PATH)
        return url.takeIf { it.startsWith("https://") }
    }

    companion object {
        const val POLLING = "polling"
        const val WEBHOOK = "webhook"
        const val WEBHOOK_PATH = "/telegram/webhook"
        const val MINIAPP_PATH = "/tg/app"

        fun load(env: Map<String, String>): TelegramConfig {
            fun v(k: String) = env[k]?.trim()?.takeIf { it.isNotEmpty() }
            val mode = v("TELEGRAM_MODE")?.lowercase()
            require(mode == null || mode == POLLING || mode == WEBHOOK) { "TELEGRAM_MODE must be polling or webhook" }
            return TelegramConfig(
                mode = mode ?: POLLING,
                webhookUrl = v("TELEGRAM_WEBHOOK_URL"),
                webhookSecret = v("TELEGRAM_WEBHOOK_SECRET"),
                supportChatId = v("TELEGRAM_SUPPORT_CHAT_ID")?.toLongOrNull(),
                loginWidget = v("TELEGRAM_LOGIN_WIDGET") == "1",
                apiUrl = v("TELEGRAM_API_URL")?.trimEnd('/') ?: "https://api.telegram.org",
                proxy = v("TELEGRAM_PROXY"),
                miniApp = v("TELEGRAM_MINIAPP") == "1",
                miniAppUrl = v("TELEGRAM_MINIAPP_URL")?.trimEnd('/'),
                communityUrl = communityUrl(v("TELEGRAM_COMMUNITY_URL")),
            )
        }

        /** Only https://t.me/... or https://telegram.me/...; anything else is ignored with a warning (never a crash). */
        fun communityUrl(raw: String?): String? {
            val url = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val ok = Regex("^https://(t\\.me|telegram\\.me)/\\S+$", RegexOption.IGNORE_CASE).matches(url)
            if (!ok) org.slf4j.LoggerFactory.getLogger("telegram").warn("TELEGRAM_COMMUNITY_URL ignored: it must be an https://t.me/... link")
            return url.takeIf { ok }
        }
    }
}
