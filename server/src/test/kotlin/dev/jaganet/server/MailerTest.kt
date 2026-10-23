package dev.jaganet.server

import dev.jaganet.server.services.fromAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

class MailerTest {
    @Test fun `the sender can be written with or without a name`() {
        fromAddress("no-reply@boopvpn.app").let { assertEquals("no-reply@boopvpn.app", it.address); assertEquals("Boop VPN", it.personal) }
        fromAddress(" Boop <no-reply@boopvpn.app> ").let { assertEquals("no-reply@boopvpn.app", it.address); assertEquals("Boop", it.personal) }
        assertFails { fromAddress("Boop no-reply") }
    }
}
