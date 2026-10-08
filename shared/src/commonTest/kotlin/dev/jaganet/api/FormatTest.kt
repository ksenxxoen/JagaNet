package dev.jaganet.api

import dev.jaganet.api.i18n.I18n
import dev.jaganet.api.i18n.Lang
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
    @Test fun localized() {
        assertEquals("5 октября 2026", Format.date("2026-10-05T12:00:00Z", Lang.RU))
        assertEquals("5. Oktober 2026", Format.date("2026-10-05T12:00:00Z", Lang.DE))
        assertEquals("1,4 ГБ", Format.bytes(1_384_000_000, Lang.RU))
        assertEquals("39,99 $", Format.money(3999, "USD", Lang.RU))
        assertEquals("2\u00A0490 ₽", Format.money(249_000, "RUB", Lang.RU))
        assertEquals("1.290,00 €", Format.money(129_000, "EUR", Lang.DE))
        assertEquals("1 ч 05 мин", Format.duration(3900, Lang.RU))
        assertEquals("21 устройство", I18n.plural(Lang.RU, 21, "{n} device|{n} devices"))
        assertEquals("3 устройства", I18n.plural(Lang.RU, 3, "{n} device|{n} devices"))
        assertEquals("12 устройств", I18n.plural(Lang.RU, 12, "{n} device|{n} devices"))
        assertEquals("1 Gerät", I18n.plural(Lang.DE, 1, "{n} device|{n} devices"))
    }
    @Test fun wireguardKeyIsValidated() {
        WireGuard.ClientParams("A".repeat(43) + "=")
        assertFailsWith<IllegalArgumentException> { WireGuard.ClientParams("short") }
    }
    @Test fun protocolParamsRoundTrip() {
        val p = WireGuard.ServerParams("k", "h:1", listOf("0.0.0.0/0"), 25)
        assertEquals(p, Protocols.decode<WireGuard.ServerParams>(Protocols.encode(p)))
    }
    @Test fun amneziaQuickConfig() {
        val p = AmneziaWG.ServerParams("S=", "h:1", listOf("0.0.0.0/0"), 25, mapOf("Jc" to "5", "S1" to "86", "H1" to "100-200"))
        val conf = AmneziaWG.quickConfig("P=", "10.8.0.2/32", listOf("1.1.1.1"), 1280, p)
        assertEquals(true, conf.contains("MTU = 1280\nS1 = 86\nH1 = 100-200\nJc = 5\n"))
        assertEquals(true, conf.contains("[Peer]\nPublicKey = S=\nEndpoint = h:1"))
        assertFailsWith<IllegalArgumentException> { AmneziaWG.ServerParams("S=", "h", emptyList(), 0, mapOf("Bogus" to "1")) }
    }
}
