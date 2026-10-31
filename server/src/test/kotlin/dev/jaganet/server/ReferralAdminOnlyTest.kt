package dev.jaganet.server

import dev.jaganet.api.CreateReferralLinkReq
import dev.jaganet.api.ReferralPeriod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** With REFERRALS_PUBLIC unset (production default) the referral program is for admins (owners) only. */
class ReferralAdminOnlyTest {
    // Invite rewards off too: with them on, every user gets the simple "Invite a friend" page (issue #40).
    private val adminOnly = mapOf("REFERRALS_PUBLIC" to "0", "REFERRAL_REWARD_DAYS" to "0")

    @Test fun `users do not see the referral program, the owner does`() = harness(adminOnly) {
        assertFalse(client().site().referralsPublic)
        val u = signIn("a@example.com")
        assertEquals("NOT_FOUND", code { u.api.referrals() })
        assertEquals("NOT_FOUND", code { u.api.referralStats(ReferralPeriod.D30) })
        assertEquals("NOT_FOUND", code { u.api.createReferralLink(CreateReferralLinkReq("YouTube")) })
        assertEquals("NOT_FOUND", code { u.api.renameReferralLink("00000000-0000-0000-0000-000000000000", "X") })
        assertEquals("NOT_FOUND", code { u.api.archiveReferralLink("00000000-0000-0000-0000-000000000000") })

        val owner = signIn("owner@test.dev")
        assertTrue(owner.api.referrals().code.isNotEmpty())
        val l = owner.api.createReferralLink(CreateReferralLinkReq("YouTube"))
        assertEquals(2, owner.api.referralStats(ReferralPeriod.D30).links.size)
        owner.api.archiveReferralLink(l.id)
    }

    @Test fun `sign-up with an invite code still works and is attributed`() = harness(adminOnly) {
        val alex = signIn("alex@example.com")
        val refCode = services.ctx.db.run { it.one("SELECT referral_code FROM users WHERE id=?::uuid", alex.session.user.id)!!.str("referral_code") }
        val sam = signIn("sam@example.com", referral = refCode)
        val by = services.ctx.db.run { it.one("SELECT referred_by::text AS r FROM users WHERE id=?::uuid", sam.session.user.id)!!.str("r") }
        assertEquals(alex.session.user.id, by)
        assertEquals("NOT_FOUND", code { sam.api.referrals() })
        // A bad code is ignored, sign-up never fails because of it.
        signIn("kim@example.com", referral = "NO-SUCH-CODE").api.me()
    }

    @Test fun `REFERRALS_PUBLIC=1 shows it to everyone`() = harness(mapOf("REFERRALS_PUBLIC" to "1")) {
        assertTrue(client().site().referralsPublic)
        signIn("a@example.com").api.referrals()
    }
}
