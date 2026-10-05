package dev.jaganet.server.services

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object Crypto {
    private val random = SecureRandom()
    private const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

    fun newToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
    fun sixDigits(): String = random.nextInt(1_000_000).toString().padStart(6, '0')

    fun hmac(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(secret.toByteArray(), "HmacSHA256")) }
        return mac.doFinal(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun safeEqual(a: String, b: String) = MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    fun referralCode(email: String): String {
        val stem = email.substringBefore('@').filter { it.isLetter() }.take(5).uppercase().ifEmpty { "JAGA" }
        return stem + "-" + (1..4).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    }
}
