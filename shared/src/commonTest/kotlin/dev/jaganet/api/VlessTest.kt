package dev.jaganet.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VlessTest {
    private val pbk = "_No2ExH-ajHLTIY4xDz1EjOQ6izyfCBAueJM73gZbh0"
    private val uuid = "6286d380-3d9c-4118-b62e-8b761a279e20"
    private val node = Vless.NodeSettings(
        host = "ru1.example.com", publicKey = pbk, shortIds = listOf("a1b2c3d4", ""), sni = "www.example.com",
        transports = listOf(Vless.Transport("tcp", 443), Vless.Transport("xhttp", 8443, path = "/s3cr3t/x")),
    )

    @Test fun paramsListTransportsInOrder() {
        val p = node.params(uuid)
        assertEquals(uuid, p.uuid)
        assertEquals(listOf("tcp", "xhttp"), p.variants.map { it.transport })
        val (vision, xhttp) = p.variants
        assertEquals(Vless.Variant("tcp", "ru1.example.com", 443, "xtls-rprx-vision", "reality", "www.example.com", pbk, "a1b2c3d4", "firefox"), vision)
        assertEquals("", xhttp.flow)
        assertEquals("/s3cr3t/x", xhttp.path)
        assertEquals("auto", xhttp.mode)
        assertEquals(listOf("vision", "xhttp"), node.transports.map { it.tag })
    }

    @Test fun visionLinkExactFormat() {
        val v = node.params(uuid).variants[0]
        assertEquals(
            "vless://6286d380-3d9c-4118-b62e-8b761a279e20@ru1.example.com:443?encryption=none&security=reality" +
                "&pbk=_No2ExH-ajHLTIY4xDz1EjOQ6izyfCBAueJM73gZbh0&sid=a1b2c3d4&sni=www.example.com&fp=firefox" +
                "&type=tcp&flow=xtls-rprx-vision#Boop%20%D0%A4%D0%B8%D0%BD%D0%BB%D1%8F%D0%BD%D0%B4%D0%B8%D1%8F%20%231",
            Vless.link(uuid, v, "Boop Финляндия #1"),
        )
    }

    @Test fun xhttpLinkHasPathAndMode() {
        val v = node.params(uuid).variants[1]
        assertEquals(
            "vless://6286d380-3d9c-4118-b62e-8b761a279e20@ru1.example.com:8443?encryption=none&security=reality" +
                "&pbk=_No2ExH-ajHLTIY4xDz1EjOQ6izyfCBAueJM73gZbh0&sid=a1b2c3d4&sni=www.example.com&fp=firefox" +
                "&type=xhttp&path=%2Fs3cr3t%2Fx&mode=auto#RU-FI",
            Vless.link(uuid, v, "RU-FI"),
        )
    }

    @Test fun linksNameTransportsAndBracketIpv6() {
        val p = node.copy(host = "2001:db8::1").params(uuid)
        val links = Vless.links(p, "Boop")
        assertEquals(2, links.size)
        assertEquals(true, links[0].startsWith("vless://$uuid@[2001:db8::1]:443?"))
        assertEquals(true, links[0].endsWith("#Boop%20TCP"))
        assertEquals(true, links[1].endsWith("#Boop%20XHTTP"))
    }

    @Test fun settingsAreChecked() {
        assertNull(node.problem())
        assertEquals("publicKey", node.copy(publicKey = "short").problem())
        assertEquals("shortIds", node.copy(shortIds = listOf("ABC")).problem())
        assertEquals("shortIds", node.copy(shortIds = emptyList()).problem())
        assertEquals("host", node.copy(host = "bad host").problem())
        assertEquals("transports", node.copy(transports = emptyList()).problem())
        assertEquals("transports.path", node.copy(transports = listOf(Vless.Transport("xhttp", 8443))).problem())
        assertEquals("transports.type", node.copy(transports = listOf(Vless.Transport("grpc", 443))).problem())
        assertEquals("transports.tag", node.copy(transports = listOf(Vless.Transport("tcp", 443), Vless.Transport("tcp", 444))).problem())
        assertEquals("transports.mode", node.copy(transports = listOf(Vless.Transport("xhttp", 8443, path = "/a", mode = "fast"))).problem())
    }

    @Test fun nodeSettingsFromJson() {
        val s = Protocols.json.decodeFromString(
            Vless.NodeSettings.serializer(),
            """{"host":"ru1.example.com","publicKey":"$pbk","shortIds":["a1"],"sni":"ru1.example.com",
               "transports":[{"type":"tcp","port":443},{"type":"xhttp","port":8443,"path":"/p","mode":"packet-up"}],
               "privateKey":"never-kept","endpoint":"ru1.example.com:443"}""",
        )
        assertEquals("firefox", s.fingerprint)
        assertEquals(Vless.Transport("tcp", 443, "vision", "xtls-rprx-vision"), s.transports[0])
        assertEquals("xhttp", s.transports[1].tag)
        assertEquals(false, "never-kept" in Protocols.encode(s).toString())
    }
}
