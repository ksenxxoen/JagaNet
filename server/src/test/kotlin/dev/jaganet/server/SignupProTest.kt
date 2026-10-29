package dev.jaganet.server

import dev.jaganet.api.PlanId
import dev.jaganet.api.TestAccountReq
import dev.jaganet.api.TestAccountsReq
import dev.jaganet.server.services.Billing
import kotlin.test.Test
import kotlin.test.assertEquals

class SignupProTest {
    private suspend fun Harness.ent(id: String) = services.ctx.db.run { services.ent.entitlement(it, id) }
    private suspend fun Harness.gifts(id: String) = services.ctx.db.run { it.one("SELECT count(*)::int AS n FROM subscriptions WHERE user_id=?::uuid AND external_id LIKE 'gift:%'", id)!!.int("n") }

    @Test fun `off by default`() = harness {
        val u = signIn("a@example.com")
        assertEquals(PlanId.FREE, ent(u.session.user.id).plan)
    }

    @Test fun `new accounts get a year of Pro, once, and are not test accounts`() = harness(mapOf("SIGNUP_PRO_DAYS" to "365")) {
        val owner = signIn("owner@test.dev")
        assertEquals(0, gifts(owner.session.user.id))
        val made = owner.api.createTestAccounts(TestAccountsReq(listOf(TestAccountReq("T", 1)), days = 14)).accounts
        assertEquals(0, gifts(made[0].id)) // test accounts get no gift on creation

        val u = signIn("a@example.com")
        val e = ent(u.session.user.id)
        assertEquals(PlanId.PRO, e.plan)
        assertEquals(clock.plusSeconds(365 * 86400L).toString(), e.expiresAt)
        assertEquals(5, e.deviceLimit)
        assertEquals(null, e.monthlyDataLimitBytes)
        signIn("a@example.com") // signing in again gives nothing more
        assertEquals(1, gifts(u.session.user.id))

        // The gift (source 'test') does not turn users into test accounts.
        assertEquals(listOf(made[0].id), owner.api.testAccounts().accounts.map { it.id })
        assertEquals("NOT_FOUND", code { owner.api.deleteTestAccount(u.session.user.id) })
        assertEquals(0, owner.api.adminFinance(dev.jaganet.api.ReferralPeriod.ALL).newSubscriptions)

        // A gift never shortens a longer subscription; a test account with a gift still lists and assigns.
        services.ctx.db.tx { Billing.grantSignupPro(it, made[0].id, 1, clock) }
        assertEquals(clock.plusSeconds(14 * 86400L).toString(), ent(made[0].id).expiresAt)
        assertEquals(listOf(made[0].id), owner.api.testAccounts().accounts.map { it.id })
        owner.api.assignTestAccount(made[0].id, "a@example.com")
        assertEquals(0, owner.api.testAccounts().accounts.size)
        assertEquals(PlanId.PRO, ent(u.session.user.id).plan)
    }
}
