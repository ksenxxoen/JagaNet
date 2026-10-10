package dev.jaganet.server

import dev.jaganet.api.CreateOrderReq
import dev.jaganet.api.CreateReferralLinkReq
import dev.jaganet.api.MoneyAmount
import dev.jaganet.api.ReferralPeriod
import dev.jaganet.api.ReferredStatus
import dev.jaganet.server.telegram.TelegramBot
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferralTest {
    private suspend fun Harness.visit(path: String, ip: String, ua: String = "Browser", referer: String? = null) =
        b.createClient { followRedirects = false }.get(path) {
            header("X-Forwarded-For", ip); header(HttpHeaders.UserAgent, ua)
            referer?.let { header(HttpHeaders.Referrer, it) }
        }

    @Test fun `tracks the whole funnel of a link from click to revenue`() = harness {
        val alex = signIn("alex@example.com")
        val insta = alex.api.createReferralLink(CreateReferralLinkReq("Instagram", "alex-insta"))
        assertEquals("ALEX-INSTA", insta.code)
        assertTrue(insta.webUrl.endsWith("/r/ALEX-INSTA"))

        // Three visits from two people; one came from instagram.com, one tagged utm_source.
        val r = visit("/r/alex-insta", "1.1.1.1", referer = "https://www.instagram.com/p/123")
        assertEquals(HttpStatusCode.Found, r.status)
        assertEquals("/?ref=ALEX-INSTA", r.headers[HttpHeaders.Location])
        visit("/r/ALEX-INSTA", "1.1.1.1", referer = "https://www.instagram.com/p/123")
        visit("/r/ALEX-INSTA?utm_source=stories", "2.2.2.2")
        // Unknown codes go to the home page and count nothing.
        assertEquals("/", visit("/r/NOPE", "3.3.3.3").headers[HttpHeaders.Location])

        // One visitor signs up with the code, buys a month on the website.
        val sam = signIn("sam@example.com", referral = "alex-insta")
        val order = sam.api.createOrder(CreateOrderReq(tariff(MONTH)))
        services.payments.markPaid(order.id)
        // Someone else signs up through the main link and doesn't pay.
        val main = alex.api.referralStats(ReferralPeriod.D30).links.single { it.main }
        signIn("kim@example.com", referral = main.code)

        val st = alex.api.referralStats(ReferralPeriod.D30)
        val f = st.links.single { it.code == "ALEX-INSTA" }.funnel
        assertEquals(3, f.clicks)
        assertEquals(2, f.visitors)
        assertEquals(1, f.signups)
        assertEquals(1, f.paidUsers)
        assertEquals(1, f.purchases)
        assertEquals(listOf(MoneyAmount(30_000, "RUB")), f.revenue)
        assertEquals(1, st.links.single { it.main }.funnel.signups)
        assertEquals(2, st.totals.signups)
        assertEquals(1, st.totals.paidUsers)

        // Daily series covers the period and adds up to the totals.
        assertEquals(30, st.days.size)
        assertEquals(Triple(3, 2, 1), Triple(st.days.sumOf { it.clicks }, st.days.sumOf { it.signups }, st.days.sumOf { it.paid }))

        assertEquals(mapOf("instagram.com" to 2, "stories" to 1), st.sources.associate { it.key to it.count })
        assertEquals(mapOf("app" to 2), st.channels.associate { it.key to it.count })
        val recent = st.recent.associateBy { it.who }
        assertEquals(ReferredStatus.ACTIVE, recent["sa***@example.com"]!!.status)
        assertEquals("Instagram", recent["sa***@example.com"]!!.linkName)
        assertEquals(ReferredStatus.REGISTERED, recent["ki***@example.com"]!!.status)

        // Paying also gave both sides the invite reward, as before.
        assertTrue(st.daysEarned > 0)

        // 40 days later the 30-day view is empty, "all time" still has everything.
        clock = clock.plus(Duration.ofDays(40))
        assertEquals(0, alex.api.referralStats(ReferralPeriod.D30).totals.clicks)
        assertEquals(3, alex.api.referralStats(ReferralPeriod.ALL).totals.clicks)
    }

    @Test fun `links can be renamed and archived, codes are unique, the main link stays`() = harness {
        val a = signIn("a@example.com")
        val l = a.api.createReferralLink(CreateReferralLinkReq("YouTube"))
        assertEquals(7, l.code.length)
        assertEquals("BAD_REQUEST", code { signIn("b@example.com").api.createReferralLink(CreateReferralLinkReq("Mine", l.code)) })
        assertEquals("BAD_REQUEST", code { a.api.createReferralLink(CreateReferralLinkReq("Bad code", "a b")) })
        a.api.renameReferralLink(l.id, "YouTube Shorts")
        assertEquals("YouTube Shorts", a.api.referralStats(ReferralPeriod.D30).links.single { !it.main }.name)
        val main = a.api.referralStats(ReferralPeriod.D30).links.single { it.main }
        assertEquals("NOT_FOUND", code { a.api.archiveReferralLink(main.id) })

        a.api.archiveReferralLink(l.id)
        assertEquals(1, a.api.referralStats(ReferralPeriod.D30).links.size)
        // An archived link no longer counts clicks or brings sign-ups.
        assertEquals("/", visit("/r/${l.code}", "9.9.9.9").headers[HttpHeaders.Location])
        val c = signIn("c@example.com", referral = l.code)
        c.api.me()
        assertNull(ctx.db.run { sql -> sql.one("SELECT referral_link_id FROM users WHERE email='c@example.com'") }?.strOrNull("referral_link_id"))
    }

    @Test fun `a Telegram start link counts a click and brings the sign-up`() = harness {
        val a = signIn("a@example.com")
        val main = a.api.referralStats(ReferralPeriod.D30).links.single { it.main }
        val bot = TelegramBot(services, FakeTelegram())
        bot.handle(buildJsonObject {
            put("update_id", 1)
            putJsonObject("message") {
                put("message_id", 1)
                putJsonObject("from") { put("id", 77); put("is_bot", false); put("first_name", "T") }
                putJsonObject("chat") { put("id", 77) }
                put("text", "/start ${main.code}")
            }
        })
        val st = a.api.referralStats(ReferralPeriod.D30)
        assertEquals(1, st.totals.clicks)
        assertEquals(1, st.totals.signups)
        assertEquals(mapOf("telegram" to 1), st.sources.associate { it.key to it.count })
        assertEquals(mapOf("telegram" to 1), st.channels.associate { it.key to it.count })
        assertEquals("Telegram", st.recent.single().who)
    }

    @Test fun `the owner sees the whole program, others can't`() = harness {
        val a = signIn("a@example.com")
        val main = a.api.referralStats(ReferralPeriod.D30).links.single { it.main }
        visit("/r/${main.code}", "1.1.1.1")
        signIn("b@example.com", referral = main.code)
        assertEquals("FORBIDDEN", code { a.api.adminReferrals(ReferralPeriod.D30) })
        val o = signIn("owner@test.dev").api.adminReferrals(ReferralPeriod.D30)
        assertEquals(1, o.totals.clicks)
        assertEquals(1, o.totals.signups)
        assertEquals("a@example.com", o.topReferrers.first().email)
        assertEquals(1, o.topReferrers.first().funnel.signups)
    }
}
