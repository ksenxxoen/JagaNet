package dev.jaganet.server

import dev.jaganet.api.AppLoginPollReq
import dev.jaganet.api.AppLoginStartReq
import dev.jaganet.api.DeviceInfo
import dev.jaganet.api.Platform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Signing the desktop app in from the website, where the person is already signed in. */
class AppLoginTest {
    @Test fun `the app gets a session once the website allows it`() = harness {
        val app = client()
        val req = app.startAppLogin(AppLoginStartReq(DeviceInfo("Office PC", Platform.DESKTOP)))
        assertTrue(req.url.endsWith("#/app-login?id=${req.id}"))
        assertEquals(4, req.code.length)
        assertNull(app.pollAppLogin(AppLoginPollReq(req.id, req.secret)).session) // not allowed yet

        val owner = signIn("owner@test.dev").api
        val info = owner.appLogin(req.id)
        assertEquals("Office PC", info.deviceName)
        assertEquals(req.code, info.code) // the person sees the same code on both screens
        assertEquals("NOT_FOUND", code { app.pollAppLogin(AppLoginPollReq(req.id, "wrong")) })
        owner.allowAppLogin(req.id)

        val session = assertNotNull(app.pollAppLogin(AppLoginPollReq(req.id, req.secret)).session)
        assertEquals("owner@test.dev", session.user.email)
        val signedIn = client(session.token)
        assertEquals("Office PC", signedIn.devices().devices.single { it.isCurrent }.name)
        // Used once.
        assertEquals("NOT_FOUND", code { app.pollAppLogin(AppLoginPollReq(req.id, req.secret)) })
        assertEquals("NOT_FOUND", code { owner.appLogin(req.id) })
        assertEquals("UNAUTHORIZED", code { client().appLogin(req.id) })
    }
}
