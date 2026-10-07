package dev.jaganet.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jaganet.api.BillingSource
import dev.jaganet.api.Format
import dev.jaganet.api.PaymentStatus
import dev.jaganet.api.PlanId
import dev.jaganet.api.Platform
import dev.jaganet.api.ProductId
import dev.jaganet.app.state.AppState
import dev.jaganet.app.state.Route
import dev.jaganet.app.theme.C
import dev.jaganet.app.theme.R
import dev.jaganet.app.ui.Button
import dev.jaganet.app.ui.ButtonKind
import dev.jaganet.app.ui.Card
import dev.jaganet.app.ui.Divider
import dev.jaganet.app.ui.ErrorNote
import dev.jaganet.app.ui.Gap
import dev.jaganet.app.ui.Ic
import dev.jaganet.app.ui.Icon
import dev.jaganet.app.ui.Load
import dev.jaganet.app.ui.Loaded
import dev.jaganet.app.ui.Radio
import dev.jaganet.app.ui.Screen
import dev.jaganet.app.ui.SectionLabel
import dev.jaganet.app.ui.T
import dev.jaganet.app.ui.TS
import dev.jaganet.app.ui.Title
import dev.jaganet.app.ui.load
import kotlinx.coroutines.launch

@Composable
fun PlansScreen(s: AppState) {
    val plans = load(Unit) { s.api.plans() }
    var pick by remember { mutableStateOf<ProductId?>(ProductId.PRO_YEARLY) }
    val a = rememberAction()
    val scope = rememberCoroutineScope()
    val store = if (s.platform.kind == Platform.IOS) "App Store" else "Google Play"
    Screen {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { s.router.back() }.semantics { contentDescription = "Close" },
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.Close, C.ink, strokeWidth = 2f) }
        }
        T("Go Pro.\nNo limits, no ads.", TS.Title, FontWeight.Bold)
        Gap(18.dp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Unlimited data", "Up to 5 devices at once", "Full speed, no throttling", "Split tunneling and kill switch").forEach {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(C.greenTint), contentAlignment = Alignment.Center) { Icon(Ic.Check, C.green, 14.dp, 2.6f) }
                    T(it, TS.Body)
                }
            }
        }
        Gap(22.dp)
        Loaded(plans) { p ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                p.products.forEach { prod ->
                    PlanOption(prod.title, if (prod.id == ProductId.PRO_YEARLY) "Best value · billed yearly" else "Billed every month", prod.displayPrice, pick == prod.id, prod.id == ProductId.PRO_YEARLY) { pick = prod.id }
                }
                PlanOption("Free", "${Format.bytes(p.free.monthlyDataLimitBytes)} a month · ${p.free.deviceLimit} device", "0", pick == null, false) { pick = null }
            }
        }
        ErrorNote(a.error)
        Gap(18.dp)
        Button(if (pick == null) "Continue with Free" else "Subscribe", {
            val product = pick
            if (product == null) s.router.back() else scope.launch {
                a.busy = true; a.error = null
                try {
                    // Simulation: the server pretends the store charged. Real builds use StoreKit / Play Billing,
                    // then send the receipt to /billing/apple|google/verify (not implemented yet).
                    s.api.devPurchase(product)
                    s.invalidate()
                    s.router.reset(Route.Home)
                } catch (e: Exception) {
                    a.error = e.message
                } finally { a.busy = false }
            }
        }, Modifier.fillMaxWidth(), ButtonKind.Primary, busy = a.busy)
        T("Renews automatically. Cancel anytime in your $store account.", TS.Caption, color = C.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
            listOf("Restore purchase", "Terms", "Privacy").forEach { T(it, TS.Caption, FontWeight.Medium, C.green) }
        }
    }
}

@Composable
private fun PlanOption(name: String, sub: String, price: String, on: Boolean, badge: Boolean, onClick: () -> Unit) {
    Box {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).clip(RoundedCornerShape(R.tile)).background(C.surface)
                .border(if (on) 2.dp else 1.dp, if (on) C.green else C.line, RoundedCornerShape(R.tile))
                .selectable(on, role = Role.RadioButton, onClick = onClick).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Radio(on)
            Column(Modifier.weight(1f)) {
                T(name, TS.Body, FontWeight.SemiBold)
                T(sub, TS.Label, color = C.muted)
            }
            T(price, TS.Small, FontWeight.Medium, mono = true)
        }
        if (badge) T(
            "BEST VALUE", TS.Tab, FontWeight.Bold, Color.White,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-14).dp, y = (-8).dp).background(C.green, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun AccountScreen(s: AppState) {
    val me = load(s.dataVersion) { s.api.me() }
    val payments = load(s.dataVersion) { s.api.payments().payments }
    val refs = load(Unit) { s.api.referrals() }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Screen(onBack = { s.router.back() }) {
        Gap(8.dp)
        Title("Account")
        T(s.user?.email ?: "", TS.Small, color = C.muted, modifier = Modifier.padding(top = 4.dp))
        Gap(18.dp)
        Loaded(me) { m ->
            val e = m.entitlement
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(R.hero)).background(C.ink).padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    T("Current plan", TS.Label, color = C.nightMuted)
                    T("Active", TS.Caption, FontWeight.SemiBold, C.ink, modifier = Modifier.background(C.greenTint, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
                T(
                    when { e.plan == PlanId.FREE -> "Free"; e.source == BillingSource.REFERRAL -> "Pro · invite reward"; e.productId == ProductId.PRO_MONTHLY -> "Pro · Monthly"; else -> "Pro · Yearly" },
                    TS.Plan, FontWeight.Bold, Color.White, modifier = Modifier.padding(top = 6.dp),
                )
                Gap(14.dp)
                Row {
                    DarkKV("Devices", "${m.usage.devicesUsed} / ${e.deviceLimit}", Modifier.weight(1f), mono = true)
                    DarkKV(if (e.autoRenew) "Renews on" else if (e.expiresAt != null) "Pro until" else "Data", e.expiresAt?.let(Format::date) ?: "${Format.bytes(m.usage.bytesUsed)} used", Modifier.weight(1f))
                }
                Gap(10.dp)
                Row {
                    DarkKV("This month", Format.bytes(m.usage.bytesUsed), Modifier.weight(1f), mono = true)
                    DarkKV("Billed via", when (e.source) { BillingSource.APPLE -> "App Store"; BillingSource.GOOGLE -> "Google Play"; BillingSource.REFERRAL -> "Invite reward"; BillingSource.DEV -> "Simulation"; BillingSource.WEB -> "Website"; BillingSource.TELEGRAM -> "Telegram"; null -> "–" }, Modifier.weight(1f))
                }
            }
        }
        Gap(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button("Change plan", { s.router.go(Route.Plans) }, Modifier.weight(1f), ButtonKind.Secondary)
            Button("Manage in store", {
                s.platform.openUrl(if (s.platform.kind == Platform.IOS) "https://apps.apple.com/account/subscriptions" else "https://play.google.com/store/account/subscriptions")
            }, Modifier.weight(1f), ButtonKind.Secondary)
        }

        SectionLabel("Payment history")
        Loaded(payments) { list ->
            Card {
                if (list.isEmpty()) T("No payments yet.", TS.Small, color = C.muted, modifier = Modifier.padding(16.dp))
                list.forEachIndexed { i, p ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            T(if (p.source == BillingSource.REFERRAL) "Invite reward" else if (p.productId == "pro_yearly") "Pro · Yearly" else "Pro · Monthly", TS.Small)
                            T(Format.date(p.startedAt) + if (p.status != PaymentStatus.ACTIVE) " · ${p.status.name.lowercase()}" else "", TS.Caption, color = C.muted)
                        }
                        T(if (p.source == BillingSource.REFERRAL) "free" else "[PRICE]", TS.Small, mono = true)
                    }
                    if (i < list.lastIndex) Divider()
                }
            }
        }

        Gap(14.dp)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(R.card)).background(C.greenTint).clickable(role = Role.Button) { s.router.go(Route.Referral) }.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                T("Invite friends", TS.Body, FontWeight.SemiBold, C.greenDark)
                val r = (refs as? Load.Ok)?.value
                T(if (r != null) "${r.subscribed} joined · ${r.daysEarned} days of Pro earned" else "Free Pro time for both of you", TS.Label, color = C.greenDark, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(Ic.ChevronRight, C.greenDark, 18.dp, 2f)
        }

        Gap(20.dp)
        Button("Sign out", { s.signOut() }, Modifier.fillMaxWidth(), ButtonKind.Ghost)
        if (!confirmDelete) Button("Delete account", { confirmDelete = true }, Modifier.fillMaxWidth(), ButtonKind.Danger)
        else Card(padding = 16.dp) {
            T("Delete your account?", TS.Body, FontWeight.SemiBold)
            T("Your devices are disconnected and your data is erased. Store subscriptions must be cancelled in the store.", TS.Label, color = C.muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button("Cancel", { confirmDelete = false }, Modifier.weight(1f), ButtonKind.Secondary)
                Button("Delete", { scope.launch { runCatching { s.api.deleteAccount() }; s.signOut() } }, Modifier.weight(1f), ButtonKind.Danger)
            }
        }
    }
}

@Composable
private fun DarkKV(k: String, v: String, modifier: Modifier, mono: Boolean = false) = Column(modifier) {
    T(k, TS.Label, color = C.nightMuted)
    T(v, TS.Label, color = Color.White, mono = mono, modifier = Modifier.padding(top = 2.dp))
}

@Composable
fun ReferralScreen(s: AppState) {
    val refs = load(Unit) { s.api.referrals() }
    var copied by remember { mutableStateOf(false) }
    Screen(onBack = { s.router.back() }) {
        Gap(8.dp)
        Title("Give Pro, get Pro")
        Loaded(refs) { r ->
            T("When a friend subscribes with your code, you both get ${r.rewardDays} days of Pro for free.", TS.Small, color = C.muted, modifier = Modifier.padding(top = 6.dp))
            Gap(20.dp)
            Card(padding = 16.dp) {
                T("Your code", TS.Label, color = C.muted)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    T(r.code, TS.Plan, FontWeight.Medium, mono = true)
                    Button(if (copied) "Copied" else "Copy", { s.platform.copy(r.code); copied = true }, kind = ButtonKind.Secondary)
                }
            }
            Gap(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Invited" to r.invited.toString(), "Subscribed" to r.subscribed.toString(), "Earned" to "${r.daysEarned} d").forEach { (k, v) ->
                    Card(Modifier.weight(1f), padding = 14.dp) {
                        T(k, TS.Caption, color = C.muted)
                        T(v, TS.Stat, FontWeight.Medium, mono = true, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Gap(24.dp)
            Button("Share invite link", { s.platform.share("Join me on JagaNet: ${r.shareUrl}") }, Modifier.fillMaxWidth(), icon = Ic.Share)
        }
    }
}
