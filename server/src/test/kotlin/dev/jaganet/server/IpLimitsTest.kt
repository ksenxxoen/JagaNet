package dev.jaganet.server

import dev.jaganet.server.services.IpLimitLogic
import dev.jaganet.server.services.IpSighting
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IpLimitsTest {
    private val t0 = Instant.parse("2026-11-22T10:00:00Z")
    private fun ip(addr: String, first: Long, last: Long = first, server: String? = "n1") =
        IpSighting(addr, t0.plusSeconds(first), t0.plusSeconds(last), server, "Node")

    @Test fun `earliest addresses keep their slots and a new one is refused`() {
        val d = IpLimitLogic.decide(
            listOf(ip("10.0.0.2", 20), ip("10.0.0.1", 10), ip("10.0.0.3", 30)),
            limit = 2,
            kicked = emptySet(),
        )
        assertEquals(listOf("10.0.0.1", "10.0.0.2"), d.allowed.map { it.ip })
        assertEquals(listOf("10.0.0.3"), d.rejected.map { it.ip })
        assertEquals(listOf("10.0.0.3"), d.blocked.map { it.ip })
    }

    @Test fun `the same IP on two nodes counts once, last seen wins for the node`() {
        val d = IpLimitLogic.decide(
            listOf(ip("10.0.0.1", 10, 10, "a"), ip("10.0.0.1", 10, 40, "b"), ip("10.0.0.2", 20)),
            limit = 1,
            kicked = emptySet(),
        )
        assertEquals(listOf("10.0.0.1"), d.allowed.map { it.ip })
        assertEquals("b", d.allowed.single().serverId)
        assertEquals(listOf("10.0.0.2"), d.rejected.map { it.ip })
    }

    @Test fun `a kicked IP frees a slot and stays blocked while it is still active`() {
        val d = IpLimitLogic.decide(
            listOf(ip("10.0.0.1", 10), ip("10.0.0.2", 20)),
            limit = 1,
            kicked = setOf("10.0.0.1"),
        )
        assertEquals(listOf("10.0.0.2"), d.allowed.map { it.ip })
        assertTrue(d.rejected.isEmpty())
        assertEquals(listOf("10.0.0.1"), d.blocked.map { it.ip })
    }

    @Test fun `zero or null limit means no cap, kicks still block`() {
        val open = IpLimitLogic.decide(listOf(ip("10.0.0.1", 10), ip("10.0.0.2", 20)), limit = null, kicked = emptySet())
        assertEquals(2, open.allowed.size)
        assertTrue(open.rejected.isEmpty())
        val kicked = IpLimitLogic.decide(listOf(ip("10.0.0.1", 10), ip("10.0.0.2", 20)), limit = 0, kicked = setOf("10.0.0.2"))
        assertEquals(listOf("10.0.0.1"), kicked.allowed.map { it.ip })
        assertEquals(listOf("10.0.0.2"), kicked.blocked.map { it.ip })
        assertNull(IpLimitLogic.effectiveLimit(0, 5))
        assertEquals(5, IpLimitLogic.effectiveLimit(null, 5))
        assertEquals(2, IpLimitLogic.effectiveLimit(2, 5))
    }
}
