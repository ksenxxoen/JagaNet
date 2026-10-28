package dev.jaganet.server

import dev.jaganet.api.ConnectionEventReq
import dev.jaganet.api.ConnectionEventType
import dev.jaganet.api.Protocols
import dev.jaganet.api.WireGuard
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.server.protocols.SimulatedDriver
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The apps report failed connections (handshake_failed, no_rx); the owner sees them in the admin panel. */
class ConnectionFailuresTest {
    private fun wg(n: Int) = TunnelProvisionReq(Protocols.WIREGUARD, clientParams = Protocols.encode(WireGuard.ClientParams(key(n))))

    private suspend fun Harness.sessions(): List<Pair<String, String?>> = ctx.db.run { sql ->
        sql.query("SELECT started_at, ended_at FROM connection_sessions ORDER BY started_at").map { it.instant("started_at").toString() to it.instantOrNull("ended_at")?.toString() }
    }

    private suspend fun Harness.failureRows(): Int = ctx.db.run { it.one("SELECT count(*)::int AS n FROM connection_failures")!!.int("n") }

    @Test fun `older apps send only connected and disconnected, without the new fields`() = harness {
        val a = signIn("old@example.com")
        a.api.provisionTunnel(wg(1))
        // Exactly what Boop 1.1 and the mobile apps put on the wire.
        val raw = b.createClient {}
        suspend fun send(json: String) = raw.post("/v1/tunnel/events") {
            header("Authorization", "Bearer ${a.session.token}"); contentType(ContentType.Application.Json); setBody(json)
        }
        assertEquals(HttpStatusCode.OK, send("""{"type":"connected","at":"$clock"}""").status)
        val driver = drivers[Protocols.WIREGUARD] as SimulatedDriver
        assertTrue(driver.peers[key(1)]!!.connected)
        clock = clock.plus(Duration.ofMinutes(10))
        assertEquals(HttpStatusCode.OK, send("""{"type":"disconnected","at":"$clock","peakDownBps":1000}""").status)
        assertTrue(!driver.peers[key(1)]!!.connected)
        assertEquals(listOf("2026-10-15T12:00:00Z" to "2026-10-15T12:10:00Z"), sessions())
        assertEquals(0, failureRows())

        // The shared client (the mobile apps) leaves the new fields out entirely.
        clock = clock.plus(Duration.ofMinutes(1))
        a.api.connectionEvent(ConnectionEventReq(ConnectionEventType.CONNECTED, clock.toString()))
        assertEquals(2, sessions().size)
        assertNull(sessions().last().second)
        assertEquals(0, failureRows())
        // An unknown type is still a bad request, as before.
        assertEquals(HttpStatusCode.BadRequest, send("""{"type":"teleported","at":"$clock"}""").status)
    }

    @Test fun `a failure is stored, sessions and the node stay, one per minute per device`() = harness {
        val a = signIn("a@example.com", device = "Laptop")
        a.api.provisionTunnel(wg(2))
        a.api.connectionEvent(ConnectionEventReq(ConnectionEventType.CONNECTED, clock.toString()))
        val before = sessions()
        val driver = drivers[Protocols.WIREGUARD] as SimulatedDriver
        assertTrue(driver.peers[key(2)]!!.connected)

        clock = clock.plus(Duration.ofSeconds(30))
        a.api.connectionEvent(
            ConnectionEventReq(
                ConnectionEventType.NO_RX, clock.toString(), endpoint = "198.51.100.7:51820", serverId = "n1",
                reason = "no_rx", network = "WiFi ", appVersion = "1.2.1", platform = "windows",
            ),
        )
        assertEquals(before, sessions()) // the session is still open
        assertTrue(driver.peers[key(2)]!!.connected) // the driver was not told anything
        assertEquals(1, failureRows())

        // Rate limit: more within the minute are dropped quietly (2xx, nothing stored).
        clock = clock.plus(Duration.ofSeconds(20))
        assertEquals("OK", code { a.api.connectionEvent(ConnectionEventReq(ConnectionEventType.HANDSHAKE_FAILED, clock.toString())) })
        assertEquals(1, failureRows())
        clock = clock.plus(Duration.ofSeconds(41))
        a.api.connectionEvent(ConnectionEventReq(ConnectionEventType.HANDSHAKE_FAILED, clock.minusSeconds(5).toString(), reason = "blocked", network = "satellite"))
        assertEquals(2, failureRows())
        assertEquals(before, sessions())

        // Another device of the same user has its own minute.
        val b2 = signIn("a@example.com", device = "Phone")
        b2.api.connectionEvent(ConnectionEventReq(ConnectionEventType.HANDSHAKE_FAILED, clock.toString(), serverId = "nope"))
        assertEquals(3, failureRows())

        val owner = signIn("owner@test.dev").api
        val all = owner.connectionFailures()
        assertEquals(3, all.failures.size)
        val first = all.failures.last()
        assertEquals(ConnectionEventType.NO_RX, first.type)
        assertEquals("a@example.com", first.email)
        assertEquals("Laptop", first.deviceName)
        assertEquals("n1", first.serverId)
        assertEquals("Node 1", first.serverName)
        assertEquals("198.51.100.7:51820", first.endpoint)
        assertEquals("wifi", first.network)
        assertEquals("1.2.1", first.appVersion)
        assertEquals("windows", first.platform)
        assertEquals("unknown", all.failures[1].network) // made-up network kinds are not stored
        assertNull(all.failures[0].serverId) // unknown node and no tunnel: no node
        assertEquals(2, all.nodes.single { it.serverId == "n1" }.last15m)

        // Filters: by node, by user (e-mail part or id).
        assertEquals(2, owner.connectionFailures(server = "n1").failures.size)
        assertEquals(3, owner.connectionFailures(user = "A@EXAMPLE").failures.size)
        assertEquals(0, owner.connectionFailures(user = "nobody").failures.size)
        assertEquals(3, owner.connectionFailures(user = a.session.user.id).failures.size)

        // The 15 minute counter forgets; the list keeps 30 days.
        clock = clock.plus(Duration.ofMinutes(16))
        assertEquals(0, owner.connectionFailures().nodes.single { it.serverId == "n1" }.last15m)
        assertEquals(0, services.tunnels.pruneFailures())
        clock = clock.plus(Duration.ofDays(31))
        assertEquals(3, services.tunnels.pruneFailures())
        assertEquals(0, owner.connectionFailures().failures.size)
    }

    @Test fun `only the owner sees the failures`() = harness {
        val a = signIn("user@example.com")
        assertEquals("FORBIDDEN", code { a.api.connectionFailures() })
        assertEquals("UNAUTHORIZED", code { client().connectionFailures() })
        assertEquals("OK", code { signIn("owner@test.dev").api.connectionFailures() })
    }
}
