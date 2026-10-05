package dev.jaganet.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FormatTest {
    @Test fun bytes() {
        assertEquals("1.4 GB", Format.bytes(1_384_000_000))
        assertEquals("312 MB", Format.bytes(312_000_000))
    }
    @Test fun clock() = assertEquals("00:42:17", Format.clock(42 * 60 + 17))
    @Test fun date() = assertEquals("5 Oct 2026", Format.date("2026-10-05T12:00:00Z"))
    @Test fun money() = assertEquals("$39.99", Format.money(3999, "USD"))
    @Test fun wireguardKeyIsValidated() {
        WireGuard.ClientParams("A".repeat(43) + "=")
        assertFailsWith<IllegalArgumentException> { WireGuard.ClientParams("short") }
    }
    @Test fun protocolParamsRoundTrip() {
        val p = WireGuard.ServerParams("k", "h:1", listOf("0.0.0.0/0"), 25)
        assertEquals(p, Protocols.decode<WireGuard.ServerParams>(Protocols.encode(p)))
    }
}
