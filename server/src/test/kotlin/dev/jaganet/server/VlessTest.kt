package dev.jaganet.server

import dev.jaganet.api.NodeReq
import dev.jaganet.api.NodeState
import dev.jaganet.api.PingTarget
import dev.jaganet.api.Protocols
import dev.jaganet.api.TunnelConfig
import dev.jaganet.api.TunnelProvisionReq
import dev.jaganet.api.Vless
import dev.jaganet.api.WireGuard
import dev.jaganet.server.protocols.ServerNode
import dev.jaganet.server.protocols.VlessDriver
import dev.jaganet.server.services.Countries
import dev.jaganet.server.services.Keys
import dev.jaganet.server.services.NodePeerCounter
import dev.jaganet.server.services.NodeRegisterReq
import dev.jaganet.server.services.NodeReportReq
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VlessTest {
    private val pbk = "_No2ExH-ajHLTIY4xDz1EjOQ6izyfCBAueJM73gZbh0"
    private val settings = Vless.NodeSettings(
        host = "ru1.vpn.example.com", publicKey = pbk, shortIds = listOf("a1b2c3d4"), sni = "ru1.vpn.example.com",
        transports = listOf(Vless.Transport("tcp", 443), Vless.Transport("xhttp", 8443, path = "/s3cr3t")),
    )
    private val uuidRe = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    private fun tokenOf(command: String) = command.substringAfter("NODE_TOKEN=").substringBefore(' ')

    @Test fun `the driver makes a UUID v4 on the server and keeps it for the device`() = runBlocking {
        val d = VlessDriver()
        val node = ServerNode("n", "N", Protocols.encode(settings), remote = true)
        // Whatever the device sends, the server picks the UUID.
        val cp = d.parseClientParams(JsonObject(mapOf("uuid" to kotlinx.serialization.json.JsonPrimitive("00000000-0000-4000-8000-000000000000"))))
        val uuid = cp["uuid"]!!.jsonPrimitive.content
        assertTrue(uuidRe.matches(uuid), uuid)
        assertNotEquals(uuid, d.parseClientParams(JsonObject(emptyMap()))["uuid"]!!.jsonPrimitive.content)
        // Idempotent: the same stored client params give the same peer (resync, moves).
        val a = d.addPeer(node, "dev", "10.9.0.2", cp)
        assertEquals(a, d.addPeer(node, "dev", "10.9.0.2", cp))
        assertEquals(uuid, a.peerKey)
        val p = Protocols.decode<Vless.ServerParams>(a.params)
        assertEquals(uuid, p.uuid)
        assertEquals(listOf(443, 8443), p.variants.map { it.port })
        assertEquals(a.params, d.currentParams(node, uuid))
        assertNull(d.currentParams(node))
        assertEquals(emptyList(), d.readCounters(node))
    }

    @Test fun `vless links in key files`() {
        val p = settings.params("6286d380-3d9c-4118-b62e-8b761a279e20")
        val cfg = TunnelConfig(Protocols.VLESS, "n", "Helsinki, FI", "10.9.0.2/32", emptyList(), 1280, Protocols.encode(p))
        val text = Keys.configText("", cfg)
        assertEquals(
            "vless://6286d380-3d9c-4118-b62e-8b761a279e20@ru1.vpn.example.com:443?encryption=none&security=reality&pbk=$pbk&sid=a1b2c3d4" +
                "&sni=ru1.vpn.example.com&fp=firefox&type=tcp&flow=xtls-rprx-vision#Boop%20Helsinki%2C%20FI%20TCP\n" +
                "vless://6286d380-3d9c-4118-b62e-8b761a279e20@ru1.vpn.example.com:8443?encryption=none&security=reality&pbk=$pbk&sid=a1b2c3d4" +
                "&sni=ru1.vpn.example.com&fp=firefox&type=xhttp&path=%2Fs3cr3t&mode=auto#Boop%20Helsinki%2C%20FI%20XHTTP\n",
            text,
        )
    }

    @Test fun `countries have Russian names`() {
        assertEquals("Финляндия", Countries.ru("FI"))
        assertEquals("Россия", Countries.ru("ru"))
        assertEquals("Нидерланды", Countries.ru("NL"))
        assertEquals("Германия", Countries.ru("DE"))
        assertEquals("ZZ", Countries.ru("ZZ"))
    }

    @Test fun `a VLESS node from the admin panel to a device, old apps never see it`() = harness {
        val ownerSession = signIn("owner@test.dev").session
        val owner = client(ownerSession.token)

        // Settings are checked; a private key sent along is never stored.
        assertEquals("BAD_REQUEST", code { owner.createNode(NodeReq("RU-FI", "Moscow", "RU", protocol = "vless")) })
        assertEquals("BAD_REQUEST", code { owner.createNode(NodeReq("RU-FI", "Moscow", "RU", protocol = "vless", vless = settings.copy(publicKey = "nope"))) })
        assertEquals("BAD_REQUEST", code { owner.createNode(NodeReq("RU-FI", "Moscow", "RU", protocol = "ikev2")) })
        val raw = b.createClient {}
        val res = raw.post("/v1/admin/nodes") {
            header("Authorization", "Bearer ${ownerSession.token}"); contentType(ContentType.Application.Json)
            setBody(
                """{"name":"RU-FI","city":"Moscow","countryCode":"ru","protocol":"vless","countryName":"Россия (каскад)","vless":{"host":"ru1.vpn.example.com",
                   "publicKey":"$pbk","privateKey":"SECRET-PRIVATE-KEY","shortIds":["A1B2C3D4"],"sni":"ru1.vpn.example.com",
                   "transports":[{"type":"tcp","port":443},{"type":"xhttp","port":8443,"path":"/s3cr3t"}]}}""",
            )
        }
        assertEquals(HttpStatusCode.OK, res.status, res.bodyAsText())
        val created = Protocols.json.decodeFromString(dev.jaganet.api.NodeInstallRes.serializer(), res.bodyAsText())
        val id = created.node.id
        assertTrue(created.command.endsWith(" PROTOCOL=vless bash"), created.command)
        val stored = ctx.db.run { it.one("SELECT protocols::text AS p, subnet FROM servers WHERE id=?", id)!! }
        assertTrue("SECRET" !in stored.str("p") && "privateKey" !in stored.str("p"), stored.str("p"))
        assertEquals("10.9.0.0/16", stored.str("subnet"))
        assertEquals(listOf("a1b2c3d4"), created.node.vless!!.shortIds)
        assertEquals(listOf("vless"), created.node.protocols)
        assertEquals("ru1.vpn.example.com:443", created.node.endpoint)
        assertEquals(NodeState.OFFLINE, created.node.state) // set up, no report yet

        // The installer registers the machine; an AmneziaWG install on this token is refused.
        val token = tokenOf(created.command)
        assertFailsWith<AppError> { services.nodes.register(token, NodeRegisterReq("203.0.113.70", 51821, "amneziawg")) }
        val reg = services.nodes.register(token, NodeRegisterReq("203.0.113.70", protocol = "vless"))
        assertEquals("", reg.privateKey)
        assertEquals(443, reg.port)
        assertEquals(NodeState.ONLINE, owner.adminNodes().nodes.single { it.id == id }.state)
        assertEquals("203.0.113.70", owner.adminNodes().nodes.single { it.id == id }.publicIp)

        // Apps from before VLESS (no supportedProtocols) and AmneziaWG-only apps don't get it.
        val user = signIn("user@example.com")
        assertTrue(user.api.servers().servers.none { it.id == id })
        assertTrue(user.api.servers(listOf("amneziawg", "wireguard")).servers.none { it.id == id })
        val old = user.api.servers().servers.single { it.id == "n1" }
        assertEquals(listOf("amneziawg", "wireguard", "jaga-custom"), old.protocols)
        assertEquals(PingTarget("n1", 51821, PingTarget.ICMP), old.ping)
        assertEquals("Германия", old.countryName)
        // An app that runs VLESS gets it, with the country and where to ping.
        val list = user.api.servers(listOf("amneziawg", "vless")).servers
        val ru = list.single { it.id == id }
        assertEquals(listOf("vless"), ru.protocols)
        assertEquals("RU", ru.countryCode)
        assertEquals("Россия (каскад)", ru.countryName)
        assertEquals(PingTarget("ru1.vpn.example.com", 443, PingTarget.TCP), ru.ping)
        assertEquals("ru1.vpn.example.com", ru.host)
        assertEquals(listOf("amneziawg"), list.single { it.id == "n1" }.protocols)

        // A device: no key of its own, the server makes its UUID.
        val cfg1 = user.api.provisionTunnel(TunnelProvisionReq(Protocols.VLESS, id))
        val p = Protocols.decode<Vless.ServerParams>(cfg1.params)
        assertTrue(uuidRe.matches(p.uuid), p.uuid)
        assertEquals(
            listOf(
                Vless.Variant("tcp", "ru1.vpn.example.com", 443, "xtls-rprx-vision", "reality", "ru1.vpn.example.com", pbk, "a1b2c3d4", "firefox"),
                Vless.Variant("xhttp", "ru1.vpn.example.com", 8443, "", "reality", "ru1.vpn.example.com", pbk, "a1b2c3d4", "firefox", "/s3cr3t", "auto"),
            ),
            p.variants,
        )
        // Automatic choice by protocol works too, and GET /tunnel gives the same UUID.
        val state = user.api.currentTunnel()
        assertEquals(p.uuid, Protocols.decode<Vless.ServerParams>(state.config!!.params).uuid)
        assertEquals("RU", state.countryCode)

        // The agent gets the UUID and the inbounds with their flows.
        val peers = services.nodes.peers(token, null)
        assertEquals("vless", peers.protocol)
        assertEquals("xray", peers.iface)
        assertEquals(listOf(p.uuid), peers.peers.map { it.publicKey })
        assertEquals(listOf(dev.jaganet.server.services.NodeInbound("vision", "xtls-rprx-vision"), dev.jaganet.server.services.NodeInbound("xhttp", "")), peers.inbounds)
        assertNull(peers.limit)

        // Counters by UUID, as Xray's statsquery has them.
        services.nodes.report(token, NodeReportReq("vless", listOf(NodePeerCounter(p.uuid, rx = 2_000_000, tx = 30_000_000, lastHandshake = clock.epochSecond))))
        services.traffic.collect()
        assertEquals(32_000_000, user.api.me().usage.bytesUsed)

        // New settings reach devices (GET /tunnel) and the agent (new version).
        owner.updateNode(id, NodeReq("RU-FI", "Moscow", "RU", vless = settings.copy(transports = listOf(Vless.Transport("xhttp", 2053, path = "/n", tag = "xh")))))
        val now = Protocols.decode<Vless.ServerParams>(user.api.currentTunnel().config!!.params)
        assertEquals(p.uuid, now.uuid)
        assertEquals(listOf(2053), now.variants.map { it.port })
        assertNotEquals(peers.version, services.nodes.peers(token, null).version)
        assertEquals(PingTarget("ru1.vpn.example.com", 2053, PingTarget.TCP), user.api.servers(listOf("vless")).servers.single().ping)
        assertEquals("BAD_REQUEST", code { owner.updateNode("n1", NodeReq("Node 1", "Testville", "DE", vless = settings)) })

        // Another device on AmneziaWG still goes to the AmneziaWG node only.
        val other = signIn("other@example.com")
        val awg = other.api.provisionTunnel(TunnelProvisionReq(Protocols.AMNEZIAWG, clientParams = Protocols.encode(WireGuard.ClientParams(key(5)))))
        assertEquals("n1", awg.serverId)
        assertFailsWith<AppError> { services.nodes.register(token, NodeRegisterReq("not-an-ip", protocol = "vless")) }
    }
}
