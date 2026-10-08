package dev.jaganet.server.services

import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
import dev.jaganet.server.Smtp
import jakarta.mail.Message
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

fun interface Mailer {
    suspend fun sendLoginCode(email: String, code: String, lang: Lang)

    /** Can it deliver mail right now? When not, e-mail sign-in says the service is unavailable. */
    fun ready(): Boolean = true
}

/** Development / simulation: print to the server log. */
class ConsoleMailer(private val log: (String) -> Unit = ::println) : Mailer {
    override suspend fun sendLoginCode(email: String, code: String, lang: Lang) = log("[mail] sign-in code for $email: $code")
}

/** Plain-text e-mail over SMTP (any provider: Brevo, Mailgun, Postmark, Amazon SES, Gmail, Zoho…). */
class EmailSender(private val smtp: Smtp) {
    private val session: Session = Session.getInstance(
        Properties().apply {
            put("mail.smtp.host", smtp.host)
            put("mail.smtp.port", smtp.port.toString())
            put("mail.smtp.auth", (smtp.user != null).toString())
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
            put("mail.smtp.writetimeout", "15000")
            when (smtp.security) {
                "ssl" -> put("mail.smtp.ssl.enable", "true")
                "none" -> {}
                else -> { put("mail.smtp.starttls.enable", "true"); put("mail.smtp.starttls.required", "true") }
            }
        },
    )

    suspend fun send(to: List<String>, subject: String, text: String) = withContext(Dispatchers.IO) {
        if (to.isEmpty()) return@withContext
        val msg = MimeMessage(session).apply {
            setFrom(InternetAddress(smtp.from, "JagaNet"))
            to.forEach { addRecipient(Message.RecipientType.TO, InternetAddress(it)) }
            setSubject(subject, "UTF-8")
            setText(text, "UTF-8")
        }
        if (smtp.user != null) Transport.send(msg, smtp.user, smtp.password) else Transport.send(msg)
    }
}

/** Sign-in codes by e-mail through the SMTP settings in force (the admin panel can change them). */
class LiveMailer(private val smtp: () -> Smtp?) : Mailer {
    override fun ready() = smtp() != null

    override suspend fun sendLoginCode(email: String, code: String, lang: Lang) {
        val s = smtp() ?: error("e-mail is not set up")
        EmailSender(s).send(
            listOf(email),
            I18n.tr(lang, "Your JagaNet sign-in code {code}", "code" to code),
            I18n.tr(lang, "Your sign-in code is {code}. It works for 10 minutes.", "code" to code) + "\n\n" +
                I18n.tr(lang, "If you didn't ask for it, just ignore this e-mail."),
        )
    }
}
