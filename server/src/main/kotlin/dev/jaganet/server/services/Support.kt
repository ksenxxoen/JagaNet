package dev.jaganet.server.services

import dev.jaganet.api.ErrorCode
import dev.jaganet.server.AppError
import dev.jaganet.server.Ctx
import dev.jaganet.server.badRequest
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** A message from the website's support form. */
data class SupportMessage(
    val name: String?,
    val email: String,
    val subject: String,
    val message: String,
    /** The signed-in account, if any. */
    val userId: String? = null,
    val accountEmail: String? = null,
    val ip: String? = null,
)

/**
 * The website's support form: the message goes by e-mail with Reply-To set to the sender, and also to
 * the Telegram support chat when the bot has one. The e-mail goes to SUPPORT_EMAIL (one address or a
 * comma separated list); without it to OWNER_EMAIL when that is an e-mail address, else to the owner
 * accounts' e-mail addresses. Every message is saved in support_messages with what delivered it, so
 * one that nothing delivered can still be read there; the sender then gets an error, not a success.
 * Anti-spam: a few messages per hour per address and per account, length limits; the honeypot is checked by the route.
 */
class Support(
    private val ctx: Ctx,
    private val telegram: () -> (suspend (String) -> Unit)? = { null },
) {
    private val log = LoggerFactory.getLogger(Support::class.java)

    /** Tests replace the e-mail transport. Default: the SMTP settings in force. */
    var mail: (suspend (to: String, subject: String, text: String, replyTo: String) -> Unit)? = null

    /** Where the e-mail goes, only well formed addresses. */
    suspend fun recipients(): List<String> {
        ctx.cfg.supportEmail?.split(',')?.map { it.trim() }?.filter(::isAddress)?.takeIf { it.isNotEmpty() }?.let { return it }
        ctx.cfg.ownerEmail?.trim()?.takeIf(::isAddress)?.let { return listOf(it) }
        return ctx.db.run { sql -> sql.query("SELECT email FROM users WHERE role='owner' AND blocked_at IS NULL ORDER BY created_at") }
            .map { it.str("email").trim() }.filter(::isAddress).distinct()
    }

    private suspend fun emailReady() = (mail != null || ctx.live.smtp != null) && recipients().isNotEmpty()

    /** Can the form deliver anything right now? */
    suspend fun available(): Boolean = telegram() != null || emailReady()

    private val hits = ConcurrentHashMap<String, MutableList<Instant>>()

    private fun allow(key: String): Boolean {
        val now = ctx.now()
        val list = hits.computeIfAbsent(key) { mutableListOf() }
        synchronized(list) {
            list.removeAll { Duration.between(it, now) > WINDOW }
            if (list.size >= PER_WINDOW) return false
            list.add(now)
        }
        if (hits.size > 10_000) hits.entries.removeIf { (_, v) -> synchronized(v) { v.all { Duration.between(it, now) > WINDOW } } }
        return true
    }

    suspend fun send(m: SupportMessage) {
        val name = m.name?.trim()?.take(MAX_NAME)?.takeIf { it.isNotEmpty() }
        val subject = m.subject.trim().replace(Regex("\\s+"), " ")
        val text = m.message.trim()
        if (subject.isEmpty() || subject.length > MAX_SUBJECT) throw badRequest("Enter a subject of up to 150 characters")
        if (text.length < MIN_MESSAGE || text.length > MAX_MESSAGE) throw badRequest("The message must be 10 to 5000 characters long")
        if (!available()) throw AppError(503, ErrorCode.SERVICE_UNAVAILABLE, "The support form is temporarily unavailable")
        val keys = listOfNotNull(m.ip?.let { "ip:$it" }, "email:${m.email}", m.userId?.let { "user:$it" })
        if (!keys.all { allow(it) }) throw AppError(429, ErrorCode.TOO_MANY_ATTEMPTS, "Too many messages, try again later")

        val body = buildString {
            appendLine("Сообщение с формы поддержки boopvpn.app")
            appendLine()
            appendLine("Имя: ${name ?: "(не указано)"}")
            appendLine("E-mail для ответа: ${m.email}")
            m.accountEmail?.takeIf { it != m.email && !it.endsWith(".invalid") }?.let { appendLine("E-mail аккаунта: $it") }
            m.userId?.let { appendLine("Аккаунт: $it") }
            appendLine("Тема: $subject")
            appendLine()
            append(text)
        }
        // Saved first, so a message is never lost whatever happens next.
        val id = ctx.db.run { sql ->
            sql.one(
                "INSERT INTO support_messages (created_at, name, email, subject, message, user_id, ip) VALUES (?,?,?,?,?,?::uuid,?) RETURNING id",
                ctx.now(), name, m.email, subject, text, m.userId, m.ip,
            )!!.long("id")
        }
        var emailed = false
        val transport = mail ?: ctx.live.smtp?.let { smtp -> { t: String, s: String, b: String, r: String -> EmailSender(smtp).send(listOf(t), s, b, r) } }
        if (transport != null) {
            for (to in recipients()) {
                try {
                    transport(to, "[Boop поддержка] " + subject.take(MAX_SUBJECT), body, m.email)
                    emailed = true
                } catch (e: Exception) {
                    log.warn("support e-mail #$id failed: ${e.javaClass.simpleName}: ${e.message}")
                }
            }
        }
        var forwarded = false
        telegram()?.let { tg ->
            try {
                tg("📩 $body")
                forwarded = true
            } catch (e: Exception) {
                log.warn("support Telegram forward #$id failed: ${e.message}")
            }
        }
        ctx.db.run { it.exec("UPDATE support_messages SET emailed=?, telegram=? WHERE id=?", emailed, forwarded, id) }
        if (!emailed && !forwarded) {
            log.error("support message #$id was not delivered, it is kept in support_messages")
            throw AppError(503, ErrorCode.SERVICE_UNAVAILABLE, "Couldn't send the message, try again later")
        }
    }

    companion object {
        private val ADDRESS = Regex("""[^@\s,;<>"']+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}""")

        /** A single plain e-mail address (not a login name, not a placeholder *.invalid one). */
        fun isAddress(s: String) = ADDRESS.matches(s) && !s.lowercase().endsWith(".invalid")

        const val MAX_NAME = 100
        const val MAX_SUBJECT = 150
        const val MIN_MESSAGE = 10
        const val MAX_MESSAGE = 5000
        const val PER_WINDOW = 5
        val WINDOW: Duration = Duration.ofHours(1)
    }
}
