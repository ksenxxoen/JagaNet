package dev.jaganet.server

import dev.jaganet.api.PlanId
import dev.jaganet.api.ReferralPeriod
import dev.jaganet.api.TestAccountReq
import dev.jaganet.api.TestAccountsReq
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TestAccountsTest {
    @Test fun `the owner makes test accounts with a key link per device`() = harness {
        val owner = signIn("owner@test.dev").api
        assertEquals("FORBIDDEN", code { signIn("a@example.com").api.testAccounts() })
        val made = owner.createTestAccounts(TestAccountsReq(listOf(TestAccountReq(" Игорь + Таня ", 2), TestAccountReq("Леня", 1)), days = 14)).accounts
        assertEquals(listOf("Игорь + Таня", "Леня"), made.map { it.name })
        assertEquals(listOf(2, 1), made.map { it.keys.size })
        assertEquals(listOf(2, 1), made.map { it.vpnKeys.size })
        assertTrue(made[0].vpnKeys.all { it.startsWith("vpn://") })
        assertEquals(listOf(null, null), made[0].activity.map { it.lastSeenAt }) // nobody connected yet
        assertTrue(made.all { it.email.matches(Regex("^test-[a-z0-9]{8}@jaganet\\.invalid$")) }, made.toString())
        assertEquals(clock.plusSeconds(14 * 86400L).toString(), made[0].expiresAt)
        // The key pages work.
        val page = b.createClient {}.get(made[0].keys[1].substringAfter(cfg.publicUrl.trimEnd('/')))
        assertEquals(200, page.status.value, page.bodyAsText())
        assertTrue(made[0].vpnKeys[1] in page.bodyAsText().replace("&#39;", "'"))

        // Pro with the device count, unlimited data; not counted as sales.
        val ent = services.ctx.db.run { services.ent.entitlement(it, made[0].id) }
        assertEquals(PlanId.PRO, ent.plan)
        assertEquals(2, ent.deviceLimit)
        assertEquals(null, ent.monthlyDataLimitBytes)
        assertEquals(0, owner.adminFinance(ReferralPeriod.ALL).newSubscriptions)
        assertEquals(2, owner.testAccounts().accounts.size)

        // Not enough room on the servers: nothing is made.
        assertEquals("BAD_REQUEST", code { owner.createTestAccounts(TestAccountsReq(listOf(TestAccountReq("Big", 5)))) })
        assertEquals(2, owner.testAccounts().accounts.size)

        owner.deleteTestAccount(made[0].id)
        assertEquals(listOf("Леня"), owner.testAccounts().accounts.map { it.name })
        assertEquals(404, b.createClient {}.get(made[0].keys[0].substringAfter(cfg.publicUrl.trimEnd('/'))).status.value)
        assertEquals("NOT_FOUND", code { owner.deleteTestAccount(made[0].id) })
        assertEquals("BAD_REQUEST", code { owner.createTestAccounts(TestAccountsReq(listOf(TestAccountReq("X", 0)))) })
        assertEquals("BAD_REQUEST", code { owner.createTestAccounts(TestAccountsReq(emptyList())) })
    }
}

class VpnLinkTest {
    @Test fun `the AmneziaVPN key is the config compressed like qCompress`() {
        val conf = "[Interface]\nPrivateKey = abc\nAddress = 10.8.0.2/32\nJc = 5\n\n[Peer]\nPublicKey = xyz\nEndpoint = 1.2.3.4:51821\nAllowedIPs = 0.0.0.0/0\n"
        val link = dev.jaganet.server.services.Keys.vpnLink(conf)
        assertTrue(link.startsWith("vpn://") && '=' !in link && '+' !in link && '/' !in link.removePrefix("vpn://"))
        val bytes = java.util.Base64.getUrlDecoder().decode(link.removePrefix("vpn://"))
        assertEquals(conf.toByteArray().size, java.nio.ByteBuffer.wrap(bytes, 0, 4).int)
        val inflater = java.util.zip.Inflater().apply { setInput(bytes, 4, bytes.size - 4) }
        val out = ByteArray(4096)
        val n = inflater.inflate(out)
        assertEquals(conf, String(out, 0, n))
    }
}

class QuickConfigTest {
    @Test fun `zero S3 and S4 and empty values stay out of client configs`() {
        val p = dev.jaganet.api.AmneziaWG.ServerParams("pub", "1.2.3.4:51821", listOf("0.0.0.0/0"), 25, mapOf("Jc" to "7", "S1" to "10", "S3" to "0", "S4" to "0", "I1" to ""))
        val text = dev.jaganet.api.AmneziaWG.quickConfig("priv", "10.8.0.2/32", listOf("1.1.1.1"), 1280, p)
        assertTrue("Jc = 7" in text && "S1 = 10" in text, text)
        assertTrue("S3" !in text && "S4" !in text && "I1" !in text, text)
        val q = p.copy(obfuscation = mapOf("S3" to "20"))
        assertTrue("S3 = 20" in dev.jaganet.api.AmneziaWG.quickConfig("priv", "10.8.0.2/32", emptyList(), 1280, q))
    }
}
