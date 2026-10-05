package dev.jaganet.server.services

fun interface Mailer {
    suspend fun sendLoginCode(email: String, code: String)
}

/** Development / simulation: print to the server log. */
class ConsoleMailer(private val log: (String) -> Unit = ::println) : Mailer {
    override suspend fun sendLoginCode(email: String, code: String) = log("[mail] sign-in code for $email: $code")
}
// TODO(production): an SMTP / transactional-email Mailer (Postmark, SES, …).
