package dev.jaganet.server

import dev.jaganet.server.services.Blocklists
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BlocklistsTest {
    private val rkn = "# comment\n78.47.126.0/24\n203.0.113.7\n2001:db8::/32\n"
    private val cdn = "provider,cidr,region\nhetzner,95.216.0.0/16,fi\nakamai,2.16.0.0/13,\nbad,line\n"

    @Test fun `addresses are matched against both lists like cheburcheck`() = harness {
        val lists = Blocklists(ctx.let {
            Ctx(it.cfg.copy(monitor = it.cfg.monitor.copy(blocklistRknUrl = "rkn", blocklistCdnUrl = "cdn")), it.db, it.drivers, it.mailer, it.now)
        }) { url -> if (url == "rkn") rkn else cdn }
        assertFalse(lists.loaded)
        assertTrue(runBlocking { lists.refresh() })
        assertEquals(listOf("78.47.126.0/24"), lists.check("78.47.126.200").rknSubnets)
        assertEquals(listOf("203.0.113.7"), lists.check("203.0.113.7").rknSubnets)
        assertFalse(lists.check("203.0.113.8").listed)
        assertEquals(listOf("hetzner"), lists.check("95.216.10.1").providers)
        assertEquals(listOf("akamai"), lists.check("2.23.1.1").providers)
        assertEquals(listOf("2001:db8::/32"), lists.check("2001:db8:1::5").rknSubnets)
        assertFalse(lists.check("151.243.177.167").listed)
        assertFalse(lists.check("not-an-ip").listed)
    }

    @Test fun `a failed download keeps the lists it had`() = harness {
        var fail = false
        val lists = Blocklists(Ctx(cfg.copy(monitor = cfg.monitor.copy(blocklistRknUrl = "rkn", blocklistCdnUrl = "")), ctx.db, ctx.drivers, ctx.mailer, ctx.now)) {
            if (fail) error("offline") else rkn
        }
        assertTrue(runBlocking { lists.refresh() })
        fail = true
        assertFalse(runBlocking { lists.refresh() })
        assertTrue(lists.check("78.47.126.1").listed)
    }
}
