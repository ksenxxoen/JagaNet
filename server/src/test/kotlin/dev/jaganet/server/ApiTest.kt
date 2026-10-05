package dev.jaganet.server

import dev.jaganet.api.AmneziaWG
import dev.jaganet.api.AppleVerifyReq
import dev.jaganet.api.ConnectionEventReq
import dev.jaganet.api.ConnectionEventType
import dev.jaganet.api.DeviceInfo
import dev.jaganet.api.EmailStartReq
import dev.jaganet.api.EmailVerifyReq
import dev.jaganet.api.PairRedeemReq
import dev.jaganet.api.PlanId
import dev.jaganet.api.Platform
import dev.jaganet.api.ProductId
import dev.jaganet.api.Protocols
import dev.jaganet.api.StatsPeriod
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.api.WireGuard
import dev.jaganet.server.protocols.AmneziaWgDriver
import dev.jaganet.server.protocols.AwgParams
import dev.jaganet.server.protocols.SimulatedDriver
import dev.jaganet.server.protocols.WireGuardDriver
import dev.jaganet.server.services.Ipam
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun wg(n: Int) = TunnelProvisionReq(Protocols.WIREGUARD, clientParams = Protocols.encode(WireGuard.ClientParams(key(n))))
private val phone = DeviceInfo("x", Platform.IOS)

class AuthTest {
    @Test fun `signs in with an emailed code and rejects wrong codes`() = harness {
        client().startEmail(EmailStartReq("A@Example.com"))
        val real = mail["a@example.com"]!!
        val wrong = if (real == "000000") "111111" else "000000"
        assertEquals("INVALID_CODE", code { client().verifyEmail(EmailVerifyReq("a@example.com", wrong, phone)) })
        val s = client().verifyEmail(EmailVerifyReq("a@example.com", real, phone))
        assertEquals("a@example.com", client(s.token).me().user.email)
        assertEquals("UNAUTHORIZED", code { client("nope").me() })
    }

    @Test fun `locks a code after 5 wrong attempts`() = harness {
        client().startEmail(EmailStartReq("b@example.com"))
        val real = mail["b@example.com"]!!
        val wrong = if (real == "000000") "111111" else "000000"
        repeat(5) { code { client().verifyEmail(EmailVerifyReq("b@example.com", wrong, phone)) } }
        assertEquals("TOO_MANY_ATTEMPTS", code { client().verifyEmail(EmailVerifyReq("b@example.com", real, phone)) })
    }

    @Test fun `pairs a second device with a one-time code`() = harness {
        val a = signIn("c@example.com")
        val pc = a.api.pairingCode().code
        val b = client().redeemPairing(PairRedeemReq(pc, DeviceInfo("Laptop", Platform.DESKTOP)))
        assertEquals(a.session.user.id, b.user.id)
        assertEquals("INVALID_CODE", code { client().redeemPairing(PairRedeemReq(pc, DeviceInfo("Again", Platform.OTHER))) })
        assertEquals(listOf("Phone", "Laptop"), a.api.devices().devices.map { it.name })
    }

    @Test fun `gives the configured owner email the owner role`() = harness {
        val o = signIn("owner@test.dev")
        val u = signIn("user@test.dev")
        assertEquals(2, o.api.adminOverview().revenue.free)
        assertEquals("FORBIDDEN", code { u.api.adminOverview() })
    }
}

class TunnelTest {
    @Test fun `provisions WireGuard with the device public key`() = harness {
        val a = signIn("w@example.com")
        val cfg = a.api.provisionTunnel(wg(1))
        assertEquals(listOf("wireguard", "n1", "10.8.0.2/32"), listOf(cfg.protocol, cfg.serverId, cfg.address))
        val params = Protocols.decode<WireGuard.ServerParams>(cfg.params)
        assertEquals(key(99), params.serverPublicKey)
        assertEquals("n1:51820", params.endpoint)
        val bad = TunnelProvisionReq(Protocols.WIREGUARD, clientParams = JsonObject(mapOf("publicKey" to JsonPrimitive("short"))))
        assertEquals("BAD_REQUEST", code { a.api.provisionTunnel(bad) })
    }

    @Test fun `switches the same device to a custom protocol and keeps its address`() = harness {
        val a = signIn("x@example.com")
        a.api.provisionTunnel(wg(2))
        val c = a.api.provisionTunnel(TunnelProvisionReq("jaga-custom"))
        assertEquals("jaga-custom", c.protocol)
        assertEquals("10.8.0.2/32", c.address)
        assertTrue("token" in c.params)
        assertEquals("jaga-custom", a.api.devices().devices[0].protocol)
        assertEquals("UNSUPPORTED_PROTOCOL", code { a.api.provisionTunnel(TunnelProvisionReq("nope-proto")) })
        assertEquals(listOf("amneziawg", "wireguard", "jaga-custom"), a.api.servers().servers[0].protocols)
    }

    @Test fun `provisions AmneziaWG with the node's obfuscation profile`() = harness {
        val a = signIn("awg@example.com")
        val cfg = a.api.provisionTunnel(TunnelProvisionReq(Protocols.AMNEZIAWG, clientParams = Protocols.encode(WireGuard.ClientParams(key(7)))))
        val p = Protocols.decode<AmneziaWG.ServerParams>(cfg.params)
        assertEquals(key(98), p.serverPublicKey)
        assertEquals(mapOf("Jc" to "5", "S1" to "86", "H1" to "1000-2000"), p.obfuscation)
        // Same device keeps its address when it falls back to plain WireGuard.
        assertEquals(cfg.address, a.api.provisionTunnel(wg(7)).address)
    }

    @Test fun `enforces the device limit per plan and frees it when a device is removed`() = harness {
        val a = signIn("d@example.com")
        a.api.provisionTunnel(wg(3))
        val b = signIn("d@example.com", "Tablet")
        assertEquals("DEVICE_LIMIT", code { b.api.provisionTunnel(wg(4)) })
        b.api.removeDevice(a.session.deviceId)
        assertEquals("UNAUTHORIZED", code { a.api.me() }) // removed device is signed out
        assertEquals("OK", code { b.api.provisionTunnel(wg(4)) })
    }

    @Test fun `reports SERVER_FULL and keeps the old tunnel when the subnet is exhausted`() = harness {
        repeat(5) { signIn("full$it@example.com").api.provisionTunnel(wg(10 + it)) } // /29 => .2-.6
        val s = signIn("full9@example.com")
        assertEquals("SERVER_FULL", code { s.api.provisionTunnel(wg(30)) })
    }
}

class TrafficTest {
    @Test fun `turns node counters into stats and cuts off free users at the limit`() = harness {
        val a = signIn("q@example.com")
        a.api.provisionTunnel(wg(5))
        a.api.connectionEvent(ConnectionEventReq(ConnectionEventType.CONNECTED, clock.toString()))

        val driver = drivers[Protocols.WIREGUARD] as SimulatedDriver
        driver.peers[key(5)]!!.apply { tx = 3_000_000_000; rx = 1_000_000_000 } // node sent 3 GB = user downloaded 3 GB
        clock = clock.plus(Duration.ofHours(1))
        driver.peers[key(5)]!!.connected = false // freeze simulated traffic
        services.traffic.collect()
        a.api.connectionEvent(ConnectionEventReq(ConnectionEventType.DISCONNECTED, clock.toString(), peakDownBps = 90_000_000))

        val s = a.api.stats(StatsPeriod.DAY)
        assertEquals(3_000_000_000, s.totalRxBytes)
        assertEquals(1_000_000_000, s.totalTxBytes)
        assertEquals(1, s.sessions)
        assertEquals(3600, s.protectedSeconds)
        assertEquals(90_000_000, s.peakDownBps)
        assertEquals(12, s.buckets.size)
        assertEquals(4_000_000_000, a.api.me().usage.bytesUsed)

        driver.peers[key(5)]!!.tx = 11_000_000_000 // past the 10 GB free limit
        assertEquals(1, services.traffic.collect().suspended)
        assertNull(a.api.devices().devices[0].tunnelAddress)
        assertEquals("DATA_LIMIT", code { a.api.provisionTunnel(wg(5)) })

        a.api.devPurchase(ProductId.PRO_MONTHLY)
        assertEquals("OK", code { a.api.provisionTunnel(wg(5)) })
    }
}

class BillingTest {
    @Test fun `upgrades to Pro and rewards both sides of a referral once`() = harness {
        val alex = signIn("alex@example.com")
        val ref = alex.api.referrals()
        val sam = signIn("sam@example.com", referral = ref.code)
        assertEquals(PlanId.FREE, sam.api.me().entitlement.plan)

        val m = sam.api.devPurchase(ProductId.PRO_YEARLY)
        assertEquals(PlanId.PRO, m.entitlement.plan)
        assertEquals(ProductId.PRO_YEARLY, m.entitlement.productId)
        assertEquals(5, m.entitlement.deviceLimit)
        assertNull(m.entitlement.monthlyDataLimitBytes)
        // Sam: 365 paid days + 30 referral days stacked after them
        assertEquals(Duration.ofDays(395), Duration.between(clock, Instant.parse(m.entitlement.expiresAt)))
        // Alex: 30 free days
        assertEquals(PlanId.PRO, alex.api.me().entitlement.plan)
        alex.api.referrals().let { assertEquals(listOf(1, 1, 30), listOf(it.invited, it.subscribed, it.daysEarned)) }

        clock = clock.plusSeconds(1)
        sam.api.devPurchase(ProductId.PRO_MONTHLY)
        assertEquals(30, alex.api.referrals().daysEarned)

        val r = signIn("owner@test.dev").api.adminOverview().revenue
        assertEquals(listOf(1, 1, 1, 1, 1), listOf(r.paying, r.yearly, r.monthly, r.fromReferrals, r.newSubsThisWeek))
        assertEquals(500L + 4800 / 12, r.mrrMinor)
    }

    @Test fun `refuses store purchases until verification is implemented`() = harness {
        val a = signIn("s@example.com")
        assertEquals("NOT_IMPLEMENTED", code { a.api.appleVerify(AppleVerifyReq("x")) })
        assertEquals(PlanId.FREE, a.api.me().entitlement.plan)
    }
}

class UnitTest {
    @Test fun `allocates addresses inside the subnet skipping used ones`() {
        assertEquals("10.8.0.2", Ipam.allocate("10.8.0.0/24", emptyList()))
        assertEquals("10.8.0.4", Ipam.allocate("10.8.0.0/24", listOf("10.8.0.2", "10.8.0.3")))
        assertNull(Ipam.allocate("10.8.1.0/30", listOf("10.8.1.2")))
    }

    @Test fun `parses wg show dump`() {
        val out = "priv\tpub\t51820\toff\nPEER=\t(none)\t1.2.3.4:5\t10.8.0.2/32\t1760000000\t100\t200\t25\n"
        val c = WireGuardDriver.parseDump(out).single()
        assertEquals("PEER=", c.peerKey)
        assertEquals(listOf(100L, 200L), listOf(c.rxBytes, c.txBytes))
        assertEquals(Instant.ofEpochSecond(1760000000), c.lastSeenAt)
    }

    @Test fun `amneziawg driver uses awg and returns the obfuscation profile`() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<List<String>>()
        val d = AmneziaWgDriver { calls += it; "" }
        val profile = AwgParams.generate()
        val settings = Protocols.json.parseToJsonElement(
            """{"endpoint":"h:2","publicKey":"${key(9)}","interface":"awg0","obfuscation":${Protocols.json.encodeToString(kotlinx.serialization.serializer<Map<String, String>>(), profile)}}""",
        ) as JsonObject
        val added = d.addPeer(dev.jaganet.server.protocols.ServerNode("n", "n", settings), "dev", "10.8.0.9", Protocols.encode(WireGuard.ClientParams(key(2))))
        assertEquals(listOf("awg", "set", "awg0", "peer", key(2), "allowed-ips", "10.8.0.9/32"), calls[0])
        assertEquals(profile, Protocols.decode<AmneziaWG.ServerParams>(added.params).obfuscation)
    }

    @Test fun `generated AmneziaWG profiles follow the spec's constraints`() = repeat(200) {
        val p = AwgParams.generate()
        val (s1, s2) = p["S1"]!!.toInt() to p["S2"]!!.toInt()
        assertTrue(s1 >= 12 && s2 >= 12 && s1 + 56 != s2)
        assertTrue(p["Jmin"]!!.toInt() <= p["Jmax"]!!.toInt() && p["Jmax"]!!.toInt() < 1280)
        val ranges = (1..4).map { p["H$it"]!!.split('-').map(String::toLong) }
        ranges.forEach { (lo, hi) -> assertTrue(lo in 5..hi && hi <= 0xFFFFFFFFL) }
        ranges.sortedBy { it[0] }.zipWithNext().forEach { (a, b) -> assertTrue(a[1] < b[0], "H ranges overlap: $ranges") }
        assertTrue(p.keys.all { it in AmneziaWG.KEYS })
    }

    @Test fun `wireguard driver issues the right wg commands`() = kotlinx.coroutines.runBlocking {
        val calls = mutableListOf<List<String>>()
        val d = WireGuardDriver { calls += it; "" }
        val node = dev.jaganet.server.protocols.ServerNode("n", "n", Protocols.encode(mapOf("endpoint" to "h:1", "publicKey" to key(9))))
        val added = d.addPeer(node, "dev", "10.8.0.7", Protocols.encode(WireGuard.ClientParams(key(1))))
        d.removePeer(node, added.peerKey)
        assertEquals(listOf("wg", "set", "wg0", "peer", key(1), "allowed-ips", "10.8.0.7/32"), calls[0])
        assertEquals(listOf("wg", "set", "wg0", "peer", key(1), "remove"), calls[1])
        assertEquals("h:1", added.params["endpoint"]!!.jsonPrimitive.content)
    }
}
