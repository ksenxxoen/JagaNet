package dev.jaganet.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.jaganet.api.ApiException
import dev.jaganet.api.EmailStartReq
import dev.jaganet.api.EmailVerifyReq
import dev.jaganet.api.PairRedeemReq
import dev.jaganet.app.APP_NAME
import dev.jaganet.app.i18n.t
import dev.jaganet.app.state.AppState
import dev.jaganet.app.state.Route
import dev.jaganet.app.theme.C
import dev.jaganet.app.theme.Space
import dev.jaganet.app.ui.Button
import dev.jaganet.app.ui.ButtonKind
import dev.jaganet.app.ui.Card
import dev.jaganet.app.ui.ErrorNote
import dev.jaganet.app.ui.Field
import dev.jaganet.app.ui.Gap
import dev.jaganet.app.ui.Ic
import dev.jaganet.app.ui.Icon
import dev.jaganet.app.ui.Screen
import dev.jaganet.app.ui.T
import dev.jaganet.app.ui.TS
import dev.jaganet.app.ui.Title
import kotlinx.coroutines.launch

@Composable
fun Brand() = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
    Box(Modifier.size(32.dp).background(C.ink, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
        Icon(Ic.Shield, C.paper, 18.dp, 2f)
    }
    T(APP_NAME, TS.Brand, FontWeight.Bold)
}

/** Runs a suspending action with busy + error state. */
class Action {
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
fun rememberAction() = remember { Action() }

@Composable
fun SignInScreen(s: AppState) {
    var email by remember { mutableStateOf("") }
    var invite by remember { mutableStateOf("") }
    var showInvite by remember { mutableStateOf(false) }
    val a = rememberAction()
    val scope = rememberCoroutineScope()
    val submit = {
        scope.launch {
            a.busy = true; a.error = null
            try {
                val e = email.trim().lowercase()
                val r = s.api.startEmail(EmailStartReq(e, invite.trim().ifEmpty { null }))
                s.router.go(Route.Verify(e, r.devCode))
            } catch (e: Exception) {
                a.error = (e as? ApiException)?.message ?: t("Can’t reach the server. Check your connection.")
            } finally { a.busy = false }
        }
        Unit
    }
    Screen {
        Gap(24.dp)
        Brand()
        Gap(48.dp)
        Title(t("Private internet,\nno setup."))
        T(t("Sign in with your email. We’ll send a 6-digit code, so there’s no password to remember."), TS.Small, color = C.muted, modifier = Modifier.padding(top = 8.dp))
        Gap(28.dp)
        Field(t("Email"), email, { email = it }, "you@example.com", KeyboardType.Email, onDone = submit)
        if (showInvite) {
            Gap(Space.md)
            Field(t("Invite code"), invite, { invite = it.uppercase() }, "ALEX-7Q2K")
        }
        ErrorNote(a.error)
        Gap(Space.lg)
        Button(t("Email me a code"), submit, Modifier.fillMaxWidth(), enabled = email.contains('@'), busy = a.busy)
        if (!showInvite) Button(t("I have an invite code"), { showInvite = true }, Modifier.fillMaxWidth(), ButtonKind.Ghost)
        Gap(Space.xl)
        Card(padding = 16.dp) {
            T(t("Already use {app} on another device?", "app" to APP_NAME), TS.Small, FontWeight.SemiBold)
            T(t("Open Devices there, tap Add device and enter the code here."), TS.Label, color = C.muted, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
            Button(t("Sign in with a device code"), { s.router.go(Route.Pair) }, Modifier.fillMaxWidth(), ButtonKind.Secondary)
        }
    }
}

@Composable
fun VerifyScreen(s: AppState, r: Route.Verify) {
    // Simulation and demo: the server returns the code, so fill it in.
    var code by remember { mutableStateOf(r.devCode ?: "") }
    val a = rememberAction()
    val scope = rememberCoroutineScope()
    val submit = {
        scope.launch {
            a.busy = true; a.error = null
            try {
                s.signedIn(s.api.verifyEmail(EmailVerifyReq(r.email, code, s.thisDevice)))
            } catch (e: Exception) {
                a.error = (e as? ApiException)?.message ?: t("Can’t reach the server.")
            } finally { a.busy = false }
        }
        Unit
    }
    Screen(onBack = { s.router.back() }) {
        Gap(8.dp)
        Title(t("Check your email"))
        T(t("We sent a 6-digit code to {email}. It expires in 10 minutes.", "email" to r.email), TS.Small, color = C.muted, modifier = Modifier.padding(top = 6.dp))
        if (r.devCode != null) {
            Gap(Space.lg)
            Box(Modifier.fillMaxWidth().background(C.greenTint, RoundedCornerShape(12.dp)).padding(12.dp)) {
                T(t("Test mode, your code {code} is filled in for you.", "code" to r.devCode), TS.Label, FontWeight.Medium, C.greenDark)
            }
        }
        Gap(24.dp)
        Field(t("Code"), code, { v -> code = v.filter(Char::isDigit).take(6) }, "000000", KeyboardType.NumberPassword, mono = true, onDone = submit)
        ErrorNote(a.error)
        Gap(Space.lg)
        Button(t("Sign in"), submit, Modifier.fillMaxWidth(), enabled = code.length == 6, busy = a.busy)
        Button(t("Use a different email"), { s.router.back() }, Modifier.fillMaxWidth(), ButtonKind.Ghost)
    }
}

@Composable
fun PairScreen(s: AppState) {
    var code by remember { mutableStateOf("") }
    val a = rememberAction()
    val scope = rememberCoroutineScope()
    val submit = {
        scope.launch {
            a.busy = true; a.error = null
            try {
                s.signedIn(s.api.redeemPairing(PairRedeemReq(code, s.thisDevice)))
            } catch (e: Exception) {
                a.error = (e as? ApiException)?.message ?: t("Can’t reach the server.")
            } finally { a.busy = false }
        }
        Unit
    }
    Screen(onBack = { s.router.back() }) {
        Gap(8.dp)
        Title(t("Enter device code"))
        T(t("On a device that’s already signed in, open Devices and tap Add device. Enter the 6 digits shown there."), TS.Small, color = C.muted, modifier = Modifier.padding(top = 6.dp))
        Gap(24.dp)
        Field(t("Device code"), code, { v -> code = v.filter(Char::isDigit).take(6) }, "000000", KeyboardType.NumberPassword, mono = true, onDone = submit)
        ErrorNote(a.error)
        Gap(Space.lg)
        Button(t("Sign in"), submit, Modifier.fillMaxWidth(), enabled = code.length == 6, busy = a.busy)
    }
}
