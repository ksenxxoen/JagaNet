package dev.jaganet.server

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The answers that were built with buildJsonObject and are @Serializable classes now (issue #119):
 * the same keys, types and nulls as before, so the old website, the mini app and the bot see no change.
 */
class TypedResponsesTest {
    private fun json(s: String): JsonElement = Json.parseToJsonElement(s)

    @Test fun `telegram config keeps its nulls`() = harness {
        val r = b.createClient {}.get("/v1/auth/telegram/config")
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue("application/json" in r.headers["Content-Type"]!!, r.headers["Content-Type"])
        // Before: buildJsonObject { put("bot", null); put("widget", false); put("loginUrl", null) }.
        assertEquals(json("""{"bot":null,"widget":false,"loginUrl":null}"""), json(r.bodyAsText()))
    }

    @Test fun `telegram status has the same fields`() = harness {
        val token = signIn("user@test.dev").session.token
        val r = b.createClient {}.get("/v1/telegram") { header("Authorization", "Bearer $token") }
        assertEquals(HttpStatusCode.OK, r.status, r.bodyAsText())
        assertEquals(
            json("""{"linked":false,"username":null,"notify":true,"telegramOnly":false,"botUrl":null,"bot":null,"lang":"ru","invites":true}"""),
            json(r.bodyAsText()),
        )
    }

    @Test fun `login link and health keep their keys and types`() = harness {
        val token = signIn("user@test.dev").session.token
        val c = b.createClient {}
        val link = json(c.post("/v1/telegram/login-link") { header("Authorization", "Bearer $token") }.bodyAsText()).jsonObject
        assertEquals(setOf("url"), link.keys)
        assertTrue(link["url"]!!.jsonPrimitive.isString)
        assertTrue("?lang=ru#/login?token=" in link["url"]!!.jsonPrimitive.content, link.toString())

        // Without the bot the t.me link is unavailable, as before.
        assertEquals(HttpStatusCode.ServiceUnavailable, c.post("/v1/telegram/link") { header("Authorization", "Bearer $token") }.status)

        val health = json(c.get("/health").bodyAsText()).jsonObject
        assertEquals(listOf("ok", "mode", "protocols"), health.keys.toList())
        assertEquals("true", health["ok"]!!.jsonPrimitive.content)
        assertEquals("test", health["mode"]!!.jsonPrimitive.content)
        assertTrue(health["protocols"]!!.jsonArray.isNotEmpty() && health["protocols"]!!.jsonArray.all { it.jsonPrimitive.isString })
    }
}
