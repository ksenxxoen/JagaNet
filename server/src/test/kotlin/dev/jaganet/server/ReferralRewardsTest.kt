package dev.jaganet.server

import dev.jaganet.api.BillingSource
import dev.jaganet.api.PlanId
import dev.jaganet.api.ReferralPeriod
import dev.jaganet.api.ReferralRewardSettings
import dev.jaganet.server.services.Channel
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Invite a friend, get +14 days" (issue #40). */
class ReferralRewardsTest {
    private suspend fun Harness.codeOf(s: Harness.Signed) = s.api.invite().code
    private suspend fun Harness.rewards(q: String = "TRUE") = ctx.db.run { it.query("SELECT * FROM referral_rewards WHERE $q ORDER BY created_at") }
    private suspend fun Harness.referralSubs(userId: String) =
        ctx.db.run { it.query("SELECT * FROM subscriptions WHERE user_id=?::uuid AND source='referral' ORDER BY started_at", userId) }
    private fun Harness.Signed.id() = session.user.id

    @Test fun `the inviter gets 14 days exactly once, on the friend's first payment`() = harness {
        val sent = mutableListOf<Pair<String, String>>()
        services.referralRewards.email = { to, _, text -> sent += to to text; true }
        val alex = signIn("alex@example.com")
        val inv = alex.api.invite()
        assertTrue(inv.enabled)
        assertEquals(14, inv.rewardDays)
        assertEquals("first_payment", inv.condition)
        assertTrue(inv.webUrl.endsWith("/r/${inv.code}"))

        val sam = signIn("sam@example.com", referral = inv.code)
        // Signing up gives nothing: only paying does.
        assertTrue(rewards().isEmpty())
        assertEquals(PlanId.FREE, alex.api.me().entitlement.plan)

        val order = buy(sam.api)
        val r = rewards().single()
        assertEquals(listOf(alex.id(), sam.id(), "referrer", "first_payment", 14, "granted"), listOf(r.str("user_id"), r.str("invitee_id"), r.str("kind"), r.str("reason"), r.int("days"), r.str("status")))
        assertEquals(order.id, r.str("order_id"))
        // Alex had no subscription: 14 days from now.
        val e = alex.api.me().entitlement
        assertEquals(PlanId.PRO, e.plan)
        assertEquals(BillingSource.REFERRAL, e.source)
        assertEquals(clock.plus(Duration.ofDays(14)), Instant.parse(e.expiresAt))
        // Told by e-mail (no Telegram), once.
        assertEquals(listOf("alex@example.com"), sent.map { it.first })
        assertTrue("14" in sent.single().second)
        assertEquals("email", rewards().single().str("notified_via"))

        // A repeated webhook, a second purchase: nothing more.
        assertNull(services.payments.markPaid(order.id))
        clock = clock.plusSeconds(60)
        buy(sam.api)
        assertEquals(1, rewards().size)
        assertEquals(1, referralSubs(alex.id()).size)
        assertEquals(1, sent.size)

        alex.api.invite().let { assertEquals(listOf(1, 1, 14), listOf(it.invited, it.paid, it.daysEarned)); assertEquals("granted", it.rewards.single().status) }
        alex.api.referrals().let { assertEquals(listOf(14, 14), listOf(it.rewardDays, it.daysEarned)) }
        assertEquals(14, alex.api.referralStats(ReferralPeriod.D30).daysEarned)
    }

    @Test fun `an active subscription is extended from its end, an ended one from now`() = harness {
        val alex = signIn("alex@example.com")
        buy(alex.api) // a month
        val paidEnd = Instant.parse(alex.api.me().entitlement.expiresAt)
        buy(signIn("sam@example.com", referral = codeOf(alex)).api)
        assertEquals(paidEnd.plus(Duration.ofDays(14)), Instant.parse(alex.api.me().entitlement.expiresAt))
        assertEquals(paidEnd, referralSubs(alex.id()).single().instant("started_at"))

        // Everything ended two months later: the next reward starts today.
        clock = clock.plus(Duration.ofDays(70))
        assertEquals(PlanId.FREE, alex.api.me().entitlement.plan)
        buy(signIn("kim@example.com", referral = codeOf(alex)).api)
        assertEquals(clock.plus(Duration.ofDays(14)), Instant.parse(alex.api.me().entitlement.expiresAt))
        assertEquals(PlanId.PRO, alex.api.me().entitlement.plan)
    }

    @Test fun `no self-invite and the inviter never changes`() = harness {
        val alex = signIn("alex@example.com")
        val bob = signIn("bob@example.com")
        val sam = signIn("sam@example.com", referral = codeOf(alex))
        // Signing in again with another code keeps the first inviter (and an existing account gets none).
        signIn("sam@example.com", referral = codeOf(bob))
        signIn("alex@example.com", referral = codeOf(bob))
        ctx.db.run {
            assertEquals(alex.id(), it.one("SELECT referred_by::text AS r FROM users WHERE id=?::uuid", sam.id())!!.str("r"))
            assertNull(it.one("SELECT referred_by::text AS r FROM users WHERE id=?::uuid", alex.id())!!.strOrNull("r"))
        }
        // The database refuses both, whatever code path would try.
        assertFailsWith<Exception> { ctx.db.run { it.exec("UPDATE users SET referred_by=id WHERE id=?::uuid", alex.id()) } }
        assertFailsWith<Exception> { ctx.db.run { it.exec("UPDATE users SET referred_by=?::uuid WHERE id=?::uuid", bob.id(), sam.id()) } }
        // Deleting the inviter's account still clears the field.
        ctx.db.run { it.exec("DELETE FROM users WHERE id=?::uuid", alex.id()) }
        assertNull(ctx.db.run { it.one("SELECT referred_by FROM users WHERE id=?::uuid", sam.id()) }!!.strOrNull("referred_by"))
        // Paying with no inviter rewards nobody.
        buy(sam.api)
        assertTrue(rewards().isEmpty())
    }

    @Test fun `the monthly cap holds, the next month counts again`() = harness(mapOf("REFERRAL_MONTHLY_CAP" to "2")) {
        val alex = signIn("alex@example.com")
        val code = codeOf(alex)
        for (n in 1..3) buy(signIn("friend$n@example.com", referral = code).api)
        assertEquals(listOf("granted", "granted", "capped"), rewards().map { it.str("status") })
        assertEquals(2, referralSubs(alex.id()).size)
        alex.api.invite().let { assertEquals(listOf(3, 3, 28), listOf(it.invited, it.paid, it.daysEarned)) }
        // The capped friend never counts again.
        clock = Instant.parse("2026-11-02T12:00:00Z")
        buy(signIn("friend3@example.com").api)
        assertEquals(3, rewards().size)
        buy(signIn("friend4@example.com", referral = code).api)
        assertEquals("granted", rewards().last().str("status"))
        assertEquals(42, alex.api.invite().daysEarned)
    }

    @Test fun `optional bonus for the invited friend`() = harness(mapOf("REFERRAL_INVITEE_DAYS" to "7")) {
        val alex = signIn("alex@example.com")
        val sam = signIn("sam@example.com", referral = codeOf(alex))
        assertEquals(7, sam.api.invite().let { it.inviteeDays })
        buy(sam.api)
        assertEquals(listOf("referrer", "invitee"), rewards().map { it.str("kind") })
        assertEquals(listOf(alex.id(), sam.id()), rewards().map { it.str("user_id") })
        // Sam's bonus comes after the month Sam paid for.
        val paidEnd = Instant.parse(sam.api.payments().payments.first { it.source != BillingSource.REFERRAL }.expiresAt)
        assertEquals(paidEnd, referralSubs(sam.id()).single().instant("started_at"))
        assertEquals(paidEnd.plus(Duration.ofDays(7)), Instant.parse(sam.api.me().entitlement.expiresAt))
        assertEquals(7, sam.api.invite().daysEarned)
    }

    @Test fun `the sign-up rule rewards at sign-up`() = harness(mapOf("REFERRAL_REWARD_CONDITION" to "signup", "REFERRAL_REWARD_DAYS" to "3")) {
        val alex = signIn("alex@example.com")
        val sam = signIn("sam@example.com", referral = codeOf(alex))
        assertEquals("signup", rewards().single().str("reason"))
        assertEquals(clock.plus(Duration.ofDays(3)), Instant.parse(alex.api.me().entitlement.expiresAt))
        buy(sam.api)
        assertEquals(1, rewards().size)
    }

    @Test fun `a friend from the inviter's network or browser is flagged`() = harness {
        val alex = signIn("alex@example.com")
        val sam = signIn("sam@example.com", referral = codeOf(alex))
        val kim = signIn("kim@example.com", referral = codeOf(alex))
        val lee = signIn("lee@example.com", referral = codeOf(alex))
        // Every test client signs in from the same address: start from a clean slate.
        ctx.db.run { it.exec("DELETE FROM user_fingerprints") }
        suspend fun visit(s: Harness.Signed, ip: String, ua: String) = b.createClient {}.get("/v1/me") {
            header(HttpHeaders.Authorization, "Bearer ${s.session.token}"); header("X-Forwarded-For", ip); header(HttpHeaders.UserAgent, ua)
        }
        visit(alex, "203.0.113.5", "Firefox")
        visit(sam, "203.0.113.5", "Firefox") // same browser at the same address
        visit(kim, "203.0.113.5", "Chrome") // same network
        visit(lee, "198.51.100.7", "Firefox")
        buy(sam.api); buy(kim.api); buy(lee.api)
        val byInvitee = rewards().associate { it.str("invitee_id") to it.strOrNull("suspicion") }
        assertEquals("same_device", byInvitee[sam.id()])
        assertEquals("same_ip", byInvitee[kim.id()])
        assertNull(byInvitee[lee.id()])
        // Still given (the owner decides), and no address is stored.
        assertEquals(3, rewards("status='granted'").size)
        assertEquals(0, ctx.db.run { it.one("SELECT count(*)::int AS n FROM user_fingerprints WHERE hash LIKE '%203.0%'") }!!.int("n"))
        val owner = signIn("owner@test.dev")
        assertEquals(2, owner.api.adminReferralRewards(suspiciousOnly = true).rewards.size)
    }

    @Test fun `the owner sees top inviters, changes the rules and cancels a reward`() = harness {
        val alex = signIn("alex@example.com")
        val sam = signIn("sam@example.com", referral = codeOf(alex))
        buy(sam.api)
        // Alex buys a month afterwards: it stacks after the 14 reward days.
        buy(alex.api)
        val before = Instant.parse(alex.api.me().entitlement.expiresAt)
        assertEquals("FORBIDDEN", code { alex.api.adminReferralRewards() })

        val owner = signIn("owner@test.dev").api
        val a = owner.adminReferralRewards()
        assertEquals(listOf(1, 14, 0, 0), listOf(a.granted, a.daysGranted, a.capped, a.cancelled))
        assertEquals("alex@example.com", a.top.single().email)
        assertEquals(listOf(1, 1, 1, 14), a.top.single().let { listOf(it.invited, it.paid, it.rewards, it.days) })

        val id = a.rewards.single().id
        assertEquals("cancelled", owner.cancelReferralReward(id).status)
        // The 14 days are gone and the month moved back by them.
        assertEquals(before.minus(Duration.ofDays(14)), Instant.parse(alex.api.me().entitlement.expiresAt))
        assertEquals(0, alex.api.invite().daysEarned)
        assertEquals("cancelled", alex.api.invite().rewards.single().status)
        // Twice does nothing more; unknown ids are 404.
        owner.cancelReferralReward(id)
        assertEquals(before.minus(Duration.ofDays(14)), Instant.parse(alex.api.me().entitlement.expiresAt))
        assertEquals("NOT_FOUND", code { owner.cancelReferralReward("nope") })

        // Rules from the admin panel apply at once.
        assertEquals("BAD_REQUEST", code { owner.saveReferralRewardSettings(ReferralRewardSettings(14, "whenever", 0, 10)) })
        owner.saveReferralRewardSettings(ReferralRewardSettings(30, "first_payment", 5, 0))
        assertEquals(30, alex.api.invite().rewardDays)
        assertEquals(30, client().site().inviteRewardDays)
        buy(signIn("kim@example.com", referral = codeOf(alex)).api)
        assertEquals(listOf(30, 5), rewards("status='granted'").map { it.int("days") })
        // Saved: survives a restart.
        ctx.live.referralRewards = RewardRules()
        services.referralRewards.load()
        assertEquals(RewardRules(30, "first_payment", 5, 0), ctx.live.referralRewards)
    }

    @Test fun `rewards off and admin-only referrals hide everything from users`() = harness(mapOf("REFERRALS_PUBLIC" to "0", "REFERRAL_REWARD_DAYS" to "0")) {
        val alex = signIn("alex@example.com")
        assertEquals("NOT_FOUND", code { alex.api.invite() })
        assertEquals("NOT_FOUND", code { alex.api.referrals() })
        assertEquals(0, client().site().inviteRewardDays)
        val refCode = ctx.db.run { it.one("SELECT referral_code FROM users WHERE id=?::uuid", alex.id())!!.str("referral_code") }
        buy(signIn("sam@example.com", referral = refCode).api)
        assertTrue(rewards().isEmpty())
    }

    @Test fun `with rewards on, users see their invite links but not the campaign analytics`() = harness(mapOf("REFERRALS_PUBLIC" to "0")) {
        val alex = signIn("alex@example.com")
        assertEquals(14, client().site().inviteRewardDays)
        assertEquals(14, alex.api.invite().rewardDays)
        assertEquals(14, alex.api.referrals().rewardDays)
        assertEquals("NOT_FOUND", code { alex.api.referralStats(ReferralPeriod.D30) })
    }

    @Test fun `a Telegram inviter is told in the bot, and the bot shows the invite links`() = harness {
        val tg = NumberedTelegram()
        val bot = dev.jaganet.server.telegram.TelegramBot(services, tg).also { services.bot = it; it.username = "boop_test_bot" }
        bot.handle(tgMessage(71, "/start"))
        val inviterId = ctx.db.run { it.one("SELECT id FROM users WHERE telegram_id=71") }!!.str("id")
        val code = services.referralRewards.invite(inviterId).code
        bot.handle(tgCallback(71, "invite"))
        val screen = tg.texts(71).last()
        assertTrue("https://t.me/boop_test_bot?start=ref_$code" in screen, screen)
        assertTrue("/r/$code" in screen, screen)

        bot.handle(tgMessage(72, "/start ref_$code"))
        val friendId = ctx.db.run { it.one("SELECT id FROM users WHERE telegram_id=72") }!!.str("id")
        val order = services.payments.create(friendId, tariff(MONTH), Channel.TELEGRAM)
        services.payments.markPaid(order.id)
        val notice = tg.texts(71).last()
        assertTrue(notice.startsWith("🎁") && "14" in notice, notice)
        assertEquals("telegram", rewards().single().str("notified_via"))
    }
}
