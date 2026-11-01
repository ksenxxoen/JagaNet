package dev.jaganet.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SupportTest {
    private data class Sent(val to: String, val subject: String, val text: String, val replyTo: String)

    @Test fun `legal pages are served with the documents' text`() = harness {
        val c = b.createClient {}
        val privacy = c.get("/privacy")
        assertEquals(200, privacy.status.value)
        assertTrue("Политика конфиденциальности" in privacy.bodyAsText())
        val terms = c.get("/terms").bodyAsText()
        assertTrue("1. Общие положения" in terms && "/privacy" in terms)
        val support = b.createClient { followRedirects = false }.get("/support")
        assertEquals("/#/support", support.headers[HttpHeaders.Location])
    }

    @Test fun `the support form e-mails the owner with Reply-To, filters bots and limits the rate`() = harness {
        val sent = mutableListOf<Sent>()
        services.support.mail = { to, subject, text, replyTo -> sent += Sent(to, subject, text, replyTo) }
        val c = b.createClient {}
        suspend fun post(json: String, ip: String = "203.0.113.7") = c.post("/v1/support") {
            contentType(ContentType.Application.Json); header("X-Forwarded-For", ip); setBody(json)
        }
        assertTrue("\"available\":true" in c.get("/v1/support").bodyAsText())

        assertEquals(200, post("""{"name":"Ivan","email":"User@Example.com","subject":"Не работает","message":"Не подключается VPN на телефоне"}""").status.value)
        assertEquals(1, sent.size)
        assertEquals("owner@test.dev", sent[0].to)
        assertEquals("user@example.com", sent[0].replyTo)
        assertTrue("Не работает" in sent[0].subject && "Не подключается VPN" in sent[0].text && "Ivan" in sent[0].text)

        // Honeypot: looks fine to the bot, nothing is sent.
        assertEquals(200, post("""{"email":"bot@example.com","subject":"x","message":"buy cheap stuff now","website":"http://spam"}""").status.value)
        assertEquals(1, sent.size)

        // Limits.
        assertEquals(400, post("""{"email":"a@example.com","subject":"Hi","message":"short"}""").status.value)
        assertEquals(400, post("""{"email":"not-an-email","subject":"Hi","message":"long enough message"}""").status.value)
        assertEquals(400, post("""{"email":"a@example.com","subject":"Hi","message":"${"x".repeat(5001)}"}""").status.value)

        // Rate: 5 per hour per address.
        repeat(4) { assertEquals(200, post("""{"email":"r$it@example.com","subject":"Hi","message":"message number $it"}""", "198.51.100.1").status.value) }
        assertEquals(200, post("""{"email":"r9@example.com","subject":"Hi","message":"message number 9"}""", "198.51.100.1").status.value)
        assertEquals(429, post("""{"email":"r10@example.com","subject":"Hi","message":"message number 10"}""", "198.51.100.1").status.value)
    }

    @Test fun `SUPPORT_EMAIL overrides the owner address`() = harness(mapOf("SUPPORT_EMAIL" to "help@test.dev")) {
        var to = ""
        services.support.mail = { t, _, _, _ -> to = t }
        b.createClient {}.post("/v1/support") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"u@example.com","subject":"Hi","message":"hello support team"}""")
        }
        assertEquals("help@test.dev", to)
    }

    @Test fun `an OWNER_EMAIL that is not an address falls back to the owner accounts' e-mail`() = harness(mapOf("OWNER_EMAIL" to "Владелец")) {
        services.ctx.db.run {
            it.exec("INSERT INTO users (email, role, referral_code, created_at) VALUES ('boss@example.com','owner','SUPT01',now())")
            it.exec("INSERT INTO users (email, role, referral_code, created_at) VALUES ('владелец','owner','SUPT02',now())")
        }
        val to = mutableListOf<String>()
        services.support.mail = { t, _, _, _ -> to += t }
        val r = b.createClient {}.post("/v1/support") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"u@example.com","subject":"Hi","message":"hello support team"}""")
        }
        assertEquals(200, r.status.value)
        assertEquals(listOf("boss@example.com"), to)
    }

    @Test fun `a message nothing delivers is kept and the sender gets an error`() = harness {
        services.support.mail = { _, _, _, _ -> throw jakarta.mail.SendFailedException("Invalid Addresses") }
        val r = b.createClient {}.post("/v1/support") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"lost@example.com","subject":"Help","message":"please answer me soon"}""")
        }
        assertEquals(503, r.status.value)
        val row = services.ctx.db.run { it.one("SELECT * FROM support_messages WHERE email='lost@example.com'") }!!
        assertEquals("Help", row.str("subject"))
        assertEquals(false, row.bool("emailed"))
        assertEquals(false, row.bool("telegram"))
    }

    @Test fun `isAddress accepts only plain addresses`() {
        assertTrue(dev.jaganet.server.services.Support.isAddress("a.b+c@mail.example.ru"))
        for (bad in listOf("Владелец", "a@b", "a@b.c,d@e.f", "\"a\"@b.ru", "a @b.ru", "tg123@telegram.invalid")) {
            assertEquals(false, dev.jaganet.server.services.Support.isAddress(bad), bad)
        }
    }
}
