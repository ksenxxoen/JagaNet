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
import dev.jaganet.api.PaymentStatus
import dev.jaganet.api.PlanId
import dev.jaganet.api.Platform
import dev.jaganet.api.ProductId
import dev.jaganet.app.i18n.fBytes
import dev.jaganet.app.i18n.fDate
import dev.jaganet.app.i18n.fMoney
import dev.jaganet.app.i18n.t
import dev.jaganet.app.i18n.tp
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
                Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button) { s.router.back() }.semantics { contentDescription = t("Close") },
                contentAlignment = Alignment.Center,
            ) { Icon(Ic.Close, C.ink, strokeWidth = 2f) }
        }
        T(t("Go Pro.\nNo limits, no ads."), TS.Title, FontWeight.Bold)
        Gap(18.dp)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(t("Unlimited data"), t("Up to 5 devices at once"), t("Full speed, no throttling"), t("Split tunneling and kill switch")).forEach {
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
                    val yearly = prod.id == ProductId.PRO_YEARLY
                    val price = productPrice(prod)
                    PlanOption(
                        if (yearly) t("Pro yearly") else t("Pro monthly"),
                        if (yearly) t("Best value, billed yearly") else t("Billed every month"),
                        if (yearly) t("{price} a year", "price" to price) else t("{price} a month", "price" to price),
                        pick == prod.id, yearly,
                    ) { pick = prod.id }
                }
                PlanOption(t("Free"), tp(p.free.deviceLimit, "{data} a month, {n} device|{data} a month, {n} devices", "data" to fBytes(p.free.monthlyDataLimitBytes)), fMoney(0, p.products.firstOrNull()?.currency ?: "USD"), pick == null, false) { pick = null }
            }
        }
        ErrorNote(a.error)
        Gap(18.dp)
        Button(if (pick == null) t("Continue with Free") else t("Subscribe"), {
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
        T(t("Renews automatically. Cancel anytime in your {store} account.", "store" to t(store)), TS.Caption, color = C.muted, align = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(18.dp, Alignment.CenterHorizontally)) {
            listOf(t("Restore purchase"), t("Terms"), t("Privacy")).forEach { T(it, TS.Caption, FontWeight.Medium, C.green) }
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
            t("BEST VALUE"), TS.Tab, FontWeight.Bold, Color.White,
            modifier = Modifier.align(Alignment.TopEnd).offset(x = (-14).dp, y = (-8).dp).background(C.green, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
fun AccountScreen(s: AppState) {
    val me = load(s.dataVersion) { s.api.me() }
    val payments = load(s.dataVersion) { s.api.payments().payments }
    val refs = load(Unit) { s.api.referrals() }
    val plans = load(Unit) { s.api.plans() }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Screen(onBack = { s.router.back() }) {
        Gap(8.dp)
        Title(t("Account"))
        T(s.user?.email ?: "", TS.Small, color = C.muted, modifier = Modifier.padding(top = 4.dp))
        Gap(18.dp)
        Loaded(me) { m ->
            val e = m.entitlement
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(R.hero)).background(C.ink).padding(18.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    T(t("Current plan"), TS.Label, color = C.nightMuted)
                    T(t("Active"), TS.Caption, FontWeight.SemiBold, C.ink, modifier = Modifier.background(C.greenTint, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                }
                T(
                    when { e.plan == PlanId.FREE -> t("Free"); e.source == BillingSource.REFERRAL -> t("Invite reward"); e.productId == ProductId.PRO_MONTHLY -> t("Pro monthly"); else -> t("Pro yearly") },
                    TS.Plan, FontWeight.Bold, Color.White, modifier = Modifier.padding(top = 6.dp),
                )
                Gap(14.dp)
                Row {
                    DarkKV(t("Devices"), "${m.usage.devicesUsed} / ${e.deviceLimit}", Modifier.weight(1f), mono = true)
                    DarkKV(if (e.autoRenew) t("Renews on") else if (e.expiresAt != null) t("Pro until") else t("Data"), e.expiresAt?.let(::fDate) ?: t("{data} used", "data" to fBytes(m.usage.bytesUsed)), Modifier.weight(1f))
                }
                Gap(10.dp)
                Row {
                    DarkKV(t("This month"), fBytes(m.usage.bytesUsed), Modifier.weight(1f), mono = true)
                    DarkKV(t("Billed via"), when (e.source) { BillingSource.APPLE -> t("App Store"); BillingSource.GOOGLE -> t("Google Play"); BillingSource.REFERRAL -> t("Invite reward"); BillingSource.DEV -> t("Simulation"); BillingSource.WEB -> t("Website"); BillingSource.TELEGRAM -> t("Telegram"); null -> "-" }, Modifier.weight(1f))
                }
            }
        }
        Gap(10.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(t("Change plan"), { s.router.go(Route.Plans) }, Modifier.weight(1f), ButtonKind.Secondary)
            Button(t("Manage in store"), {
                s.platform.openUrl(if (s.platform.kind == Platform.IOS) "https://apps.apple.com/account/subscriptions" else "https://play.google.com/store/account/subscriptions")
            }, Modifier.weight(1f), ButtonKind.Secondary)
        }

        SectionLabel(t("Payment history"))
        Loaded(payments) { list ->
            Card {
                if (list.isEmpty()) T(t("No payments yet."), TS.Small, color = C.muted, modifier = Modifier.padding(16.dp))
                list.forEachIndexed { i, p ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            T(if (p.source == BillingSource.REFERRAL) t("Invite reward") else if (p.productId == "pro_yearly") t("Pro yearly") else t("Pro monthly"), TS.Small)
                            T(t("{from} to {to}", "from" to fDate(p.startedAt), "to" to fDate(p.expiresAt)), TS.Caption, color = C.muted)
                            when (p.status) {
                                PaymentStatus.ACTIVE -> {}
                                PaymentStatus.EXPIRED -> T(t("Expired"), TS.Caption, color = C.muted)
                                PaymentStatus.CANCELLED -> T(t("Cancelled"), TS.Caption, color = C.muted)
                                PaymentStatus.REFUNDED -> T(t("Refunded"), TS.Caption, color = C.muted)
                            }
                        }
                        val price = (plans as? Load.Ok)?.value?.products?.firstOrNull { it.id.name.lowercase() == p.productId }?.let(::productPrice) ?: "-"
                        T(if (p.source == BillingSource.REFERRAL) t("No charge") else price, TS.Small, mono = true)
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
                T(t("Invite friends"), TS.Body, FontWeight.SemiBold, C.greenDark)
                val r = (refs as? Load.Ok)?.value
                T(if (r != null) t("{n} joined", "n" to r.subscribed) + ", " + tp(r.daysEarned, "{n} day of Pro earned|{n} days of Pro earned") else t("Free Pro time for both of you"), TS.Label, color = C.greenDark, modifier = Modifier.padding(top = 2.dp))
            }
            Icon(Ic.ChevronRight, C.greenDark, 18.dp, 2f)
        }

        Gap(20.dp)
        Button(t("Sign out"), { s.signOut() }, Modifier.fillMaxWidth(), ButtonKind.Ghost)
        if (!confirmDelete) Button(t("Delete account"), { confirmDelete = true }, Modifier.fillMaxWidth(), ButtonKind.Danger)
        else Card(padding = 16.dp) {
            T(t("Delete your account?"), TS.Body, FontWeight.SemiBold)
            T(t("Your devices are disconnected and your data is erased. Store subscriptions must be cancelled in the store."), TS.Label, color = C.muted, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(t("Cancel"), { confirmDelete = false }, Modifier.weight(1f), ButtonKind.Secondary)
                Button(t("Delete"), { scope.launch { runCatching { s.api.deleteAccount() }; s.signOut() } }, Modifier.weight(1f), ButtonKind.Danger)
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
        Title(t("Give Pro, get Pro"))
        Loaded(refs) { r ->
            T(tp(r.rewardDays, "When a friend subscribes with your code, you both get {n} day of Pro for free.|When a friend subscribes with your code, you both get {n} days of Pro for free."), TS.Small, color = C.muted, modifier = Modifier.padding(top = 6.dp))
            Gap(20.dp)
            Card(padding = 16.dp) {
                T(t("Your code"), TS.Label, color = C.muted)
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    T(r.code, TS.Plan, FontWeight.Medium, mono = true)
                    Button(if (copied) t("Copied") else t("Copy"), { s.platform.copy(r.code); copied = true }, kind = ButtonKind.Secondary)
                }
            }
            Gap(10.dp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(t("Invited") to r.invited.toString(), t("Subscribed") to r.subscribed.toString(), t("Earned") to t("{n} d", "n" to r.daysEarned)).forEach { (k, v) ->
                    Card(Modifier.weight(1f), padding = 14.dp) {
                        T(k, TS.Caption, color = C.muted)
                        T(v, TS.Stat, FontWeight.Medium, mono = true, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Gap(24.dp)
            Button(t("Share invite link"), { s.platform.share(t("Join me on JagaNet: {url}", "url" to r.shareUrl)) }, Modifier.fillMaxWidth(), icon = Ic.Share)
        }
    }
}

/** The list price in the interface language; falls back to the server's English label. */
private fun productPrice(p: dev.jaganet.api.Product): String =
    if (p.priceMinor != null && p.currency != null) fMoney(p.priceMinor!!, p.currency!!) else p.displayPrice.substringBefore('/')
