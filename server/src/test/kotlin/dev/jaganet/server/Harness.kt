package dev.jaganet.server

import dev.jaganet.api.ApiClient
import dev.jaganet.api.ApiException
import dev.jaganet.api.DeviceInfo
import dev.jaganet.api.EmailStartReq
import dev.jaganet.api.EmailVerifyReq
import dev.jaganet.api.ErrorCode
import dev.jaganet.api.Platform
import dev.jaganet.api.SessionRes
import dev.jaganet.server.db.Db
import dev.jaganet.server.db.Embedded
import dev.jaganet.server.db.Jsonb
import dev.jaganet.server.http.Services
import dev.jaganet.server.http.jaganet
import dev.jaganet.server.protocols.SimulatedDriver
import dev.jaganet.server.services.Mailer
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

/** One embedded PostgreSQL for the test JVM; one fresh database per test. */
object TestPg {
    val pg by lazy { Embedded.start() }
    private val n = AtomicInteger()
    fun freshDb(): Db {
        val name = "t${n.incrementAndGet()}"
        pg.postgresDatabase.connection.use { it.createStatement().execute("CREATE DATABASE $name") }
        return Db(pg.getDatabase("postgres", name)).also { it.migrate() }
    }
}

fun key(n: Int): String = Base64.getEncoder().encodeToString(ByteArray(32) { n.toByte() })

class Harness(val b: ApplicationTestBuilder) {
    var clock: Instant = Instant.parse("2026-10-15T12:00:00Z")
    val mail = mutableMapOf<String, String>()
    val cfg = Config.load(mapOf("JAGANET_MODE" to "test", "OWNER_EMAIL" to "owner@test.dev", "EXPOSE_OTP" to "0", "PRICE_RUB_MONTHLY" to "30000", "PRICE_RUB_YEARLY" to "250000", "PRICE_EUR_MONTHLY" to "500", "PRICE_EUR_YEARLY" to "4800"))
    val drivers = SimulatedDriver.registry { clock }
    /** false = e-mail "not set up" (sign-in by e-mail unavailable unless codes are shown). */
    var mailReady = true
    val ctx = Ctx(cfg, TestPg.freshDb(), drivers, object : Mailer {
        override suspend fun sendLoginCode(email: String, code: String, lang: dev.jaganet.api.i18n.Lang) { mail[email] = code }
        override fun ready() = mailReady
    }, { clock })
    val services = Services(ctx)

    init {
        runBlocking {
            ctx.db.run {
                it.exec(
                    "INSERT INTO servers (id, name, city, country_code, subnet, protocols, max_peers) VALUES ('n1','Node 1','Testville','DE','10.8.0.0/29',?,5)",
                    Jsonb("""{"wireguard":{"endpoint":"n1:51820","publicKey":"${key(99)}"},"jaga-custom":{"endpoint":"n1:443"},
                        "amneziawg":{"endpoint":"n1:51821","publicKey":"${key(98)}","obfuscation":{"Jc":"5","S1":"86","H1":"1000-2000"}}}"""),
                )
            }
        }
        b.application { jaganet(services) }
    }

    fun client(token: String? = null) = ApiClient("", b.createClient {}, { token })

    suspend fun tariff(name: String): String = services.tariffs.all().first { it.name == name }.id

    /** Buys [name] through the checkout, as the payment service would confirm it. */
    suspend fun buy(api: ApiClient, name: String = MONTH): dev.jaganet.api.OrderRes =
        api.createOrder(dev.jaganet.api.CreateOrderReq(tariff(name))).also { services.payments.markPaid(it.id) }

    data class Signed(val session: SessionRes, val api: ApiClient)

    suspend fun signIn(email: String, device: String = "Phone", referral: String? = null): Signed {
        client().startEmail(EmailStartReq(email, referral))
        val s = client().verifyEmail(EmailVerifyReq(email, mail[email]!!, DeviceInfo(device, Platform.IOS)))
        return Signed(s, client(s.token))
    }
}

/** The two tariffs made on first start. */
const val MONTH = "Pro на месяц"
const val YEAR = "Pro на год"

fun harness(block: suspend Harness.() -> Unit) = testApplication { Harness(this).block() }

/** "OK" or the API error code. */
suspend fun code(block: suspend () -> Any?): String = try {
    block(); "OK"
} catch (e: ApiException) {
    e.code.name
}

val ErrorCode.n get() = name
