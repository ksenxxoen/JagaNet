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
    val cfg = Config.load(mapOf("JAGANET_MODE" to "test", "OWNER_EMAIL" to "owner@test.dev", "EXPOSE_OTP" to "0", "PRICE_MONTHLY_MINOR" to "500", "PRICE_YEARLY_MINOR" to "4800"))
    val drivers = SimulatedDriver.registry { clock }
    val ctx = Ctx(cfg, TestPg.freshDb(), drivers, { e, c -> mail[e] = c }, { clock })
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

    data class Signed(val session: SessionRes, val api: ApiClient)

    suspend fun signIn(email: String, device: String = "Phone", referral: String? = null): Signed {
        client().startEmail(EmailStartReq(email, referral))
        val s = client().verifyEmail(EmailVerifyReq(email, mail[email]!!, DeviceInfo(device, Platform.IOS)))
        return Signed(s, client(s.token))
    }
}

fun harness(block: suspend Harness.() -> Unit) = testApplication { Harness(this).block() }

/** "OK" or the API error code. */
suspend fun code(block: suspend () -> Any?): String = try {
    block(); "OK"
} catch (e: ApiException) {
    e.code.name
}

val ErrorCode.n get() = name
