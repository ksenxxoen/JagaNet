package dev.jaganet.server

import dev.jaganet.api.DiagnosticsReq
import dev.jaganet.server.services.Diagnostics
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Сообщить о проблеме" in the Boop apps: POST /v1/diagnostics, and the owner's list and report view. */
class DiagnosticsTest {
    private fun report(text: String = "Boop 1.2.1\nlog line", comment: String? = null) = DiagnosticsReq(
        text = text, comment = comment, appVersion = "1.2.1", platform = "windows", osVersion = "Windows 11 (10.0.22631)",
        state = "problem", problem = "blocked", serverId = "n1", country = "de", host = "n1.example.com",
        resolved = listOf("192.0.2.10", "192.0.2.11"), handshakeAgeSec = 95, rxBytes = 1_200_000, txBytes = 340_000,
        probe = "blocked (panel no, web yes, 1.2 s)",
    )

    private suspend fun Harness.rows(): Int = ctx.db.run { it.one("SELECT count(*)::int AS n FROM diagnostics")!!.int("n") }

    @Test fun `a report is stored with the user and device and the owner reads it`() = harness {
        val a = signIn("a@example.com", device = "Laptop")
        val id = a.api.sendDiagnostics(report(comment = "  Nothing opens since this morning  ")).id
        assertEquals(1, rows())

        val owner = signIn("owner@test.dev").api
        val list = owner.adminDiagnostics().reports
        assertEquals(1, list.size)
        val x = list.single()
        assertEquals(id, x.id)
        assertEquals("a@example.com", x.email)
        assertEquals("Laptop", x.deviceName)
        assertEquals(a.session.user.id, x.userId)
        assertEquals("1.2.1", x.appVersion)
        assertEquals("windows", x.platform)
        assertEquals("Windows 11 (10.0.22631)", x.osVersion)
        assertEquals("problem", x.state)
        assertEquals("blocked", x.problem)
        assertEquals("n1", x.serverId)
        assertEquals("Node 1", x.serverName)
        assertEquals("DE", x.country)
        assertTrue(x.hasComment)
        assertEquals(clock.toString(), x.at)

        val full = owner.adminDiagnosticsReport(id)
        assertEquals(x, full.report)
        assertEquals("Nothing opens since this morning", full.comment)
        assertEquals("n1.example.com", full.host)
        assertEquals(listOf("192.0.2.10", "192.0.2.11"), full.resolved)
        assertEquals(95L, full.handshakeAgeSec)
        assertEquals(1_200_000L, full.rxBytes)
        assertEquals(340_000L, full.txBytes)
        assertEquals("blocked (panel no, web yes, 1.2 s)", full.probe)
        assertEquals("Boop 1.2.1\nlog line", full.text)

        // Only the text is required; an unknown node is not stored, made-up numbers neither.
        val bare = a.api.sendDiagnostics(DiagnosticsReq(text = "only text", serverId = "nope", handshakeAgeSec = -5, country = "AUTO")).id
        val b = owner.adminDiagnosticsReport(bare)
        assertNull(b.report.serverId)
        assertEquals("auto", b.report.country)
        assertNull(b.handshakeAgeSec)
        assertNull(b.comment)
        assertTrue(!b.report.hasComment)
        assertEquals(emptyList(), b.resolved)

        // Filter by user: an e-mail part or the id.
        assertEquals(2, owner.adminDiagnostics(user = "A@EXAMPLE").reports.size)
        assertEquals(2, owner.adminDiagnostics(user = a.session.user.id).reports.size)
        assertEquals(0, owner.adminDiagnostics(user = "nobody").reports.size)
        assertEquals("NOT_FOUND", code { owner.adminDiagnosticsReport("999999") })
        assertEquals("NOT_FOUND", code { owner.adminDiagnosticsReport("abc") })
    }

    @Test fun `a signed-in device is needed and the text must not be empty`() = harness {
        assertEquals("UNAUTHORIZED", code { client().sendDiagnostics(report()) })
        assertEquals("UNAUTHORIZED", code { client("not-a-token").sendDiagnostics(report()) })
        val a = signIn("a@example.com")
        assertEquals("BAD_REQUEST", code { a.api.sendDiagnostics(DiagnosticsReq(text = "   ")) })
        assertEquals(0, rows())
    }

    @Test fun `the size is limited to 256 KB`() = harness {
        val a = signIn("a@example.com")
        val raw = b.createClient {}
        suspend fun send(body: String) = raw.post("/v1/diagnostics") {
            header("Authorization", "Bearer ${a.session.token}"); header("Accept-Language", "ru")
            contentType(ContentType.Application.Json); setBody(body)
        }
        val big = send("""{"text":"${"x".repeat(Diagnostics.MAX_BYTES)}"}""")
        assertEquals(HttpStatusCode.PayloadTooLarge, big.status)
        assertTrue("BAD_REQUEST" in big.bodyAsText())
        assertTrue("Отчёт слишком большой" in big.bodyAsText())
        assertEquals(0, rows())
        // Just under the limit is fine.
        val ok = send("""{"text":"${"x".repeat(Diagnostics.MAX_BYTES - 100)}"}""")
        assertEquals(HttpStatusCode.OK, ok.status)
        assertEquals(1, rows())
        // Unknown fields from a newer app are ignored.
        assertEquals(HttpStatusCode.OK, send("""{"text":"t","somethingNew":1}""").status)
    }

    @Test fun `five reports per device per hour`() = harness {
        val a = signIn("a@example.com", device = "Laptop")
        repeat(Diagnostics.PER_HOUR) {
            a.api.sendDiagnostics(report())
            clock = clock.plus(Duration.ofMinutes(1))
        }
        assertEquals("TOO_MANY_ATTEMPTS", code { a.api.sendDiagnostics(report()) })
        assertEquals(5, rows())
        // Another device of the same person has its own hour.
        val other = signIn("a@example.com", device = "Desk")
        assertEquals("OK", code { other.api.sendDiagnostics(report()) })
        // An hour after the first one, one more goes through.
        clock = clock.plus(Duration.ofMinutes(55))
        assertEquals("OK", code { a.api.sendDiagnostics(report()) })
        assertEquals("TOO_MANY_ATTEMPTS", code { a.api.sendDiagnostics(report()) })
        assertEquals(7, rows())
    }

    @Test fun `reports are kept 30 days`() = harness {
        val a = signIn("a@example.com")
        a.api.sendDiagnostics(report())
        clock = clock.plus(Duration.ofDays(29))
        a.api.sendDiagnostics(report())
        assertEquals(0, services.diagnostics.prune())
        clock = clock.plus(Duration.ofDays(2))
        assertEquals(1, services.diagnostics.prune())
        assertEquals(1, signIn("owner@test.dev").api.adminDiagnostics().reports.size)
    }

    @Test fun `only the owner sees the reports`() = harness {
        val a = signIn("user@example.com")
        val id = a.api.sendDiagnostics(report()).id
        assertEquals("FORBIDDEN", code { a.api.adminDiagnostics() })
        assertEquals("FORBIDDEN", code { a.api.adminDiagnosticsReport(id) })
        assertEquals("UNAUTHORIZED", code { client().adminDiagnostics() })
        assertEquals("UNAUTHORIZED", code { client().adminDiagnosticsReport(id) })
        val owner = signIn("owner@test.dev").api
        assertEquals("OK", code { owner.adminDiagnostics() })
        assertEquals("OK", code { owner.adminDiagnosticsReport(id) })
    }
}
