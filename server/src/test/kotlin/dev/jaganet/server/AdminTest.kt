package dev.jaganet.server

import dev.jaganet.api.AlertSettings
import dev.jaganet.api.CreateOrderReq
import dev.jaganet.api.DeviceInfo
import dev.jaganet.api.EmailStartReq
import dev.jaganet.api.LinkLoginReq
import dev.jaganet.api.ModeSettings
import dev.jaganet.api.MoneyAmount
import dev.jaganet.api.Platform
import dev.jaganet.api.PriceSettings
import dev.jaganet.api.ProductId
import dev.jaganet.api.ReferralPeriod
import dev.jaganet.api.Role
import dev.jaganet.api.SmtpSettings
import dev.jaganet.server.services.Settings
import dev.jaganet.server.telegram.TelegramBot
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val web = DeviceInfo("Website", Platform.OTHER)

class AdminTest {
    @Test fun `without e-mail, e-mail sign-in says it is unavailable, unless codes are shown on purpose`() = harness {
        mailReady = false
        assertEquals("SERVICE_UNAVAILABLE", code { client().startEmail(EmailStartReq("a@example.com")) })
        assertFalse(client().site().emailSignIn)
        ctx.live.showSignInCodes = true
        assertEquals("OK", code { client().startEmail(EmailStartReq("a@example.com")) })
        assertTrue(client().site().testCodes)
    }

    @Test fun `without a payment service, buying says it is unavailable`() = harness {
        val a = signIn("a@example.com")
        ctx.live.paymentProvider = null
        assertEquals("SERVICE_UNAVAILABLE", code { a.api.createOrder(CreateOrderReq(ProductId.PRO_MONTHLY)) })
        assertFalse(client().site().paymentsEnabled)
    }

    @Test fun `the owner signs in with a one-time link made on the server`() = harness {
        val raw = b.createClient {}
        // Through the web proxy (X-Forwarded-For) the link can't be asked for.
        assertEquals(HttpStatusCode.Forbidden, raw.get("/internal/owner-login") { header("X-Forwarded-For", "1.2.3.4") }.status)
        val link = raw.get("/internal/owner-login").bodyAsText().trim()
        val token = link.substringAfter("token=")
        val s = client().redeemLoginLink(LinkLoginReq(token, web))
        assertEquals("owner@test.dev", s.user.email)
        assertEquals(Role.OWNER, s.user.role)
        assertEquals("INVALID_CODE", code { client().redeemLoginLink(LinkLoginReq(token, web)) })
    }

    @Test fun `Telegram users sign in on the website with the bot's login link`() = harness {
        val tg = FakeTelegram()
        val bot = TelegramBot(services, tg)
        bot.handle(buildJsonObject {
            put("update_id", 1)
            putJsonObject("message") {
                put("message_id", 1); putJsonObject("from") { put("id", 5); put("is_bot", false); put("first_name", "T") }
                putJsonObject("chat") { put("id", 5) }; put("text", "/login")
            }
        })
        val url = tg.sent("sendMessage").last()["reply_markup"]!!.jsonObject["inline_keyboard"]!!.jsonArray[0].jsonArray[0].jsonObject["url"]!!.jsonPrimitive.content
        assertTrue("/?lang=ru#/login?token=" in url, url)
        val s = client().redeemLoginLink(LinkLoginReq(url.substringAfter("token="), web))
        assertEquals("tg5@telegram.invalid", s.user.email)
    }

    @Test fun `the owner changes plans, e-mail, alerts and test modes, and they stick`() = harness {
        assertEquals("FORBIDDEN", code { signIn("a@example.com").api.adminSettings() })
        val o = signIn("owner@test.dev").api
        val st = o.adminSettings()
        o.savePlanSettings(st.plans.copy(freeMonthlyGb = 5, proDeviceLimit = 7, prices = mapOf("RUB" to PriceSettings(19_900, 199_000), "EUR" to PriceSettings(399, 2_999))))
        assertEquals(19_900L, client().plans().products.single { it.id == ProductId.PRO_MONTHLY }.priceMinor)
        assertEquals(5_000_000_000L, client().plans().free.monthlyDataLimitBytes)
        assertEquals("BAD_REQUEST", code { o.savePlanSettings(st.plans.copy(prices = mapOf("RUB" to PriceSettings(0, 1)))) })

        val saved = o.saveSmtpSettings(SmtpSettings("smtp.example.com", 587, "me@example.com", "secret", "me@example.com"))
        assertTrue(saved.smtpHasPassword)
        assertNull(saved.smtp!!.password, "the password never comes back")
        // Saving again without a password keeps it.
        assertTrue(o.saveSmtpSettings(SmtpSettings("smtp.example.com", 465, "me@example.com", null, "me@example.com", "ssl")).smtpHasPassword)
        assertEquals("secret", ctx.live.smtp!!.password)

        o.saveAlertSettings(AlertSettings(listOf("Ops@Example.com"), listOf(42)))
        o.saveModeSettings(ModeSettings(showSignInCodes = false, testPayments = false))
        assertNull(ctx.live.paymentProvider)

        // A restart loads them back from the database.
        ctx.live.smtp = null; ctx.live.alertEmails = emptyList(); ctx.live.plans = cfg.plans; ctx.live.paymentProvider = "test"
        Settings(ctx).load()
        assertEquals("secret", ctx.live.smtp!!.password)
        assertEquals(listOf("ops@example.com"), ctx.live.alertEmails)
        assertEquals(7, ctx.live.plans.proDeviceLimit)
        assertNull(ctx.live.paymentProvider)
    }

    @Test fun `the owner sees money received and subscriptions bought`() = harness {
        val a = signIn("a@example.com")
        val order = a.api.createOrder(CreateOrderReq(ProductId.PRO_YEARLY))
        services.payments.markPaid(order.id)
        a.api.createOrder(CreateOrderReq(ProductId.PRO_MONTHLY)) // left unpaid
        signIn("b@example.com").api.devPurchase(ProductId.PRO_MONTHLY)

        val f = signIn("owner@test.dev").api.adminFinance(ReferralPeriod.D30)
        assertEquals(listOf(MoneyAmount(250_000, "RUB")), f.revenue)
        assertEquals(1, f.paidOrders)
        assertEquals(2, f.newSubscriptions)
        assertEquals(2, f.activeSubscribers)
        assertEquals(listOf(MoneyAmount(250_000 / 12, "RUB")), f.mrr)
        assertEquals(1, f.unpaidOrders)
        assertEquals(mapOf("web" to 1, "dev" to 1), f.byChannel.associate { it.key to it.count })
        assertEquals(MoneyAmount(250_000, "RUB"), f.recent.single { it.channel == "web" }.amount)
        assertNull(f.recent.single { it.channel == "dev" }.amount)
        assertEquals(30, f.days.size)
        assertEquals(1, f.days.sumOf { it.orders })
    }

    @Test fun `the owner always has Pro and gets a VPN key without paying`() = harness {
        val o = signIn("owner@test.dev")
        val e = o.api.me().entitlement
        assertEquals(dev.jaganet.api.PlanId.PRO, e.plan)
        assertNull(e.expiresAt)
        assertNull(e.monthlyDataLimitBytes)
        ctx.live.paymentProvider = null
        assertEquals("amneziawg", o.api.createKey().protocol)
    }
}
