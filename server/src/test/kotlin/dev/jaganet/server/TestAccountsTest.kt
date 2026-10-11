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
        assertTrue(made.all { it.email.matches(Regex("^test-[a-z0-9]{8}@jaganet\\.invalid$")) }, made.toString())
        assertEquals(clock.plusSeconds(14 * 86400L).toString(), made[0].expiresAt)
        // The key pages work.
        val page = b.createClient {}.get(made[0].keys[1].substringAfter(cfg.publicUrl.trimEnd('/')))
        assertEquals(200, page.status.value, page.bodyAsText())

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
