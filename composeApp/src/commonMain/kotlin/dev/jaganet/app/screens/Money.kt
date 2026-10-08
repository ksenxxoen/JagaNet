package dev.jaganet.app.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
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
import dev.jaganet.api.CreateReferralLinkReq
import dev.jaganet.api.MoneyAmount
import dev.jaganet.api.PaymentStatus
import dev.jaganet.api.PlanId
import dev.jaganet.api.Platform
import dev.jaganet.api.ProductId
import dev.jaganet.api.ReferralDay
import dev.jaganet.api.ReferralLink
import dev.jaganet.api.ReferralPeriod
import dev.jaganet.api.ReferralStatsRes
import dev.jaganet.api.ReferredStatus
import dev.jaganet.api.ReferredUser
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
import dev.jaganet.app.ui.Field
import dev.jaganet.app.ui.Gap
import dev.jaganet.app.ui.Ic
import dev.jaganet.app.ui.Icon
import dev.jaganet.app.ui.Load
import dev.jaganet.app.ui.Loaded
import dev.jaganet.app.ui.Progress
import dev.jaganet.app.ui.Radio
import dev.jaganet.app.ui.Screen
import dev.jaganet.app.ui.SectionLabel
import dev.jaganet.app.ui.Segmented
import dev.jaganet.app.ui.T
import dev.jaganet.app.ui.TS
import dev.jaganet.app.ui.Title
import dev.jaganet.app.ui.load
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

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

/* ---------- referral program ---------- */

@Composable
fun ReferralScreen(s: AppState) {
    var period by remember { mutableStateOf(ReferralPeriod.D30) }
    var version by remember { mutableStateOf(0) }
    val stats = load(period, version, s.dataVersion) { s.api.referralStats(period) }
    val reload: () -> Unit = { version++ }
    Screen(onBack = { s.router.back() }) {
        Gap(8.dp)
        Title(t("Referral program"))
        (stats as? Load.Ok)?.value?.let { r ->
            if (r.daysEarned > 0) T(tp(r.daysEarned, "{n} day of Pro earned|{n} days of Pro earned"), TS.Small, FontWeight.SemiBold, C.green, modifier = Modifier.padding(top = 4.dp))
        }
        Gap(18.dp)
        Segmented(
            listOf(ReferralPeriod.D7 to t("7 days"), ReferralPeriod.D30 to t("30 days"), ReferralPeriod.D90 to t("90 days"), ReferralPeriod.ALL to t("All time")),
            period,
        ) { period = it }
        Gap(14.dp)
        Loaded(stats) { r -> ReferralBody(s, r, reload) }
    }
}

@Composable
private fun ReferralBody(s: AppState, r: ReferralStatsRes, reload: () -> Unit) {
    val f = r.totals
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            listOf(t("Clicks") to f.clicks.toString(), t("Unique visitors") to f.visitors.toString()),
            listOf(t("Sign-ups") to f.signups.toString(), t("Paid") to f.paidUsers.toString()),
            listOf(t("Purchases") to f.purchases.toString(), t("Revenue") to fRevenue(f.revenue)),
        ).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (k, v) -> RefTile(k, v, Modifier.weight(1f).fillMaxHeight()) }
            }
        }
        Card(padding = 14.dp) {
            T(t("Conversion"), TS.Caption, color = C.muted)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                RefMini(t("Visitors to sign-ups"), pct(f.signups, f.visitors), Modifier.weight(1f), TS.Stat)
                RefMini(t("Sign-ups to paid"), pct(f.paidUsers, f.signups), Modifier.weight(1f), TS.Stat)
            }
        }
    }

    SectionLabel(t("Clicks per day"))
    Card(padding = 16.dp) { RefChart(r.days) }

    SectionLabel(t("Your links"))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        r.links.forEach { RefLinkCard(s, it, reload) }
    }

    SectionLabel(t("New link"))
    RefNewLink(s, reload)

    SectionLabel(t("Top sources"))
    RefCounts(r.sources.map { sourceName(it.key) to it.count })

    SectionLabel(t("Sign-up channels"))
    RefCounts(r.channels.map { channelName(it.key) to it.count })

    SectionLabel(t("Recent referrals"))
    RefRecent(r.recent)
}

@Composable
private fun RefTile(label: String, value: String, modifier: Modifier) = Card(modifier, padding = 14.dp) {
    T(label, TS.Caption, color = C.muted, maxLines = 1)
    T(value, if (value.length > 12) TS.Small else TS.Stat, FontWeight.Medium, mono = true, modifier = Modifier.padding(top = 4.dp), maxLines = 2)
}

@Composable
private fun RefMini(label: String, value: String, modifier: Modifier, style: TS = TS.Body) = Column(modifier) {
    T(label, TS.Caption, color = C.muted, maxLines = 1)
    T(value, style, FontWeight.Medium, mono = true, modifier = Modifier.padding(top = 2.dp), maxLines = 2)
}

@Composable
private fun RefLegend(color: Color, label: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(color))
    T(label, TS.Label, color = C.muted)
}

/** Daily clicks (light bars) with sign-ups (dark part) and days with a payment (dot on top). */
@Composable
private fun RefChart(days: List<ReferralDay>) {
    if (days.isEmpty()) {
        T(t("No clicks in this period yet."), TS.Small, color = C.muted)
        return
    }
    // Long periods are summed into at most 60 bars so they stay readable on a phone.
    val per = (days.size + 59) / 60
    val bars = days.chunked(per).map { c -> ReferralDay(c.first().day, c.sumOf { it.clicks }, c.sumOf { it.signups }, c.sumOf { it.paid }) }
    val max = bars.maxOf { maxOf(it.clicks, it.signups) }.coerceAtLeast(1)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        RefLegend(C.chartGreen, t("Clicks"))
        RefLegend(C.green, t("Sign-ups"))
        RefLegend(C.warn, t("Paid"))
    }
    Gap(12.dp)
    Row(
        Modifier.fillMaxWidth().height(124.dp).semantics { contentDescription = t("Clicks per day") },
        horizontalArrangement = Arrangement.spacedBy(if (bars.size > 31) 1.dp else 3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        bars.forEach { b ->
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                if (b.paid > 0) Box(Modifier.padding(bottom = 3.dp).size(5.dp).clip(CircleShape).background(C.warn))
                val h = 108f * b.clicks / max
                val sh = 108f * b.signups / max
                Box(
                    Modifier.fillMaxWidth().height(maxOf(h, sh, 2f).dp).clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(if (b.clicks > 0) C.chartGreen else C.lineSoft),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    if (b.signups > 0) Box(Modifier.fillMaxWidth().height(sh.coerceAtLeast(2f).dp).background(C.green))
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        T(fDate(days.first().day), TS.Caption, color = C.muted)
        T(fDate(days.last().day), TS.Caption, color = C.muted)
    }
}

@Composable
private fun RefLinkCard(s: AppState, l: ReferralLink, reload: () -> Unit) {
    var copied by remember(l.id) { mutableStateOf<String?>(null) }
    var renaming by remember(l.id) { mutableStateOf(false) }
    var newName by remember(l.id) { mutableStateOf(l.name) }
    var confirmArchive by remember(l.id) { mutableStateOf(false) }
    val a = rememberAction()
    val scope = rememberCoroutineScope()
    val save = {
        if (newName.isNotBlank() && !a.busy) scope.launch {
            a.busy = true; a.error = null
            try {
                s.api.renameReferralLink(l.id, newName.trim())
                renaming = false
                reload()
            } catch (e: Exception) {
                a.error = e.message
            } finally { a.busy = false }
        }
    }
    val f = l.funnel
    Card(padding = 16.dp) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            T(linkName(l.name), TS.Body, FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            T(l.code, TS.Label, color = C.muted, mono = true, maxLines = 1)
        }
        T(l.webUrl, TS.Caption, color = C.muted, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
        Gap(12.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RefMini(t("Clicks"), "${f.clicks}", Modifier.weight(1f))
            RefMini(t("Visitors"), "${f.visitors}", Modifier.weight(1f))
            RefMini(t("Sign-ups"), "${f.signups}", Modifier.weight(1f))
        }
        Gap(8.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RefMini(t("Paid"), "${f.paidUsers}", Modifier.weight(1f))
            RefMini(t("Purchases"), "${f.purchases}", Modifier.weight(1f))
            RefMini(t("Revenue"), fRevenue(f.revenue), Modifier.weight(1f))
        }
        Gap(14.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(if (copied == "web") t("Copied") else t("Copy link"), { s.platform.copy(l.webUrl); copied = "web" }, Modifier.weight(1f), ButtonKind.Secondary)
            Button(t("Share"), { s.platform.share(t("Join me on JagaNet: {url}", "url" to l.webUrl)) }, Modifier.weight(1f), ButtonKind.Secondary, icon = Ic.Share)
        }
        l.telegramUrl?.let { tg ->
            Gap(8.dp)
            Button(if (copied == "tg") t("Copied") else t("Copy Telegram link"), { s.platform.copy(tg); copied = "tg" }, Modifier.fillMaxWidth(), ButtonKind.Secondary)
        }
        if (!l.main) {
            when {
                renaming -> {
                    Gap(14.dp)
                    Field(t("Link name"), newName, { newName = it.take(40) }, "Instagram", onDone = { save() })
                    Gap(10.dp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(t("Cancel"), { renaming = false; newName = l.name; a.error = null }, Modifier.weight(1f), ButtonKind.Secondary)
                        Button(t("Save"), { save() }, Modifier.weight(1f), ButtonKind.Primary, enabled = newName.isNotBlank(), busy = a.busy)
                    }
                }
                confirmArchive -> {
                    Gap(14.dp)
                    T(t("Archive this link?"), TS.Body, FontWeight.SemiBold)
                    T(t("The link stops working and leaves this list."), TS.Label, color = C.muted, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(t("Cancel"), { confirmArchive = false; a.error = null }, Modifier.weight(1f), ButtonKind.Secondary)
                        Button(t("Archive"), {
                            scope.launch {
                                a.busy = true; a.error = null
                                try {
                                    s.api.archiveReferralLink(l.id)
                                    confirmArchive = false
                                    reload()
                                } catch (e: Exception) {
                                    a.error = e.message
                                } finally { a.busy = false }
                            }
                        }, Modifier.weight(1f), ButtonKind.Danger, busy = a.busy)
                    }
                }
                else -> Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(t("Rename"), { renaming = true; newName = l.name }, Modifier.weight(1f), ButtonKind.Ghost)
                    Button(t("Archive"), { confirmArchive = true }, Modifier.weight(1f), ButtonKind.Danger)
                }
            }
            ErrorNote(a.error)
        }
    }
}

@Composable
private fun RefNewLink(s: AppState, reload: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    val a = rememberAction()
    val scope = rememberCoroutineScope()
    val create = {
        if (name.isNotBlank() && !a.busy) scope.launch {
            a.busy = true; a.error = null
            try {
                s.api.createReferralLink(CreateReferralLinkReq(name.trim(), code.trim().ifEmpty { null }))
                name = ""; code = ""
                reload()
            } catch (e: Exception) {
                a.error = e.message
            } finally { a.busy = false }
        }
    }
    Card(padding = 16.dp) {
        Field(t("Link name"), name, { name = it.take(40) }, "Instagram")
        Gap(10.dp)
        Field(t("Custom code, optional"), code, { v -> code = v.uppercase().filter { it in 'A'..'Z' || it in '0'..'9' || it == '-' }.take(32) }, "INSTA-2026", onDone = { create() })
        T(t("3 to 32 Latin letters, digits or hyphens."), TS.Caption, color = C.muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        ErrorNote(a.error)
        Gap(12.dp)
        Button(t("Create link"), { create() }, Modifier.fillMaxWidth(), ButtonKind.Primary, icon = Ic.Plus, enabled = name.isNotBlank(), busy = a.busy)
    }
}

@Composable
private fun RefCounts(items: List<Pair<String, Int>>) = Card(padding = 16.dp) {
    if (items.isEmpty()) T(t("No data for this period."), TS.Small, color = C.muted)
    val max = items.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (k, n) ->
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    T(k, TS.Small, maxLines = 1, modifier = Modifier.weight(1f))
                    T("$n", TS.Small, mono = true)
                }
                Gap(6.dp)
                Progress(n.toFloat() / max)
            }
        }
    }
}

@Composable
private fun RefRecent(list: List<ReferredUser>) {
    var all by remember { mutableStateOf(false) }
    val shown = if (all) list else list.take(8)
    Card {
        if (list.isEmpty()) T(t("No one has signed up with your links yet."), TS.Small, color = C.muted, modifier = Modifier.padding(16.dp))
        shown.forEachIndexed { i, u ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(u.who, TS.Small, FontWeight.SemiBold, maxLines = 1)
                    T(listOf(linkName(u.linkName), channelName(u.channel), fDate(u.joinedAt)).joinToString(", "), TS.Caption, color = C.muted, maxLines = 2, modifier = Modifier.padding(top = 2.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    val (label, bg, fg) = when (u.status) {
                        ReferredStatus.ACTIVE -> Triple(t("Pro active"), C.greenTint, C.greenDark)
                        ReferredStatus.LAPSED -> Triple(t("Pro ended"), C.lineSoft, C.muted)
                        ReferredStatus.REGISTERED -> Triple(t("Signed up"), C.lineSoft, C.ink)
                    }
                    T(label, TS.Caption, FontWeight.SemiBold, fg, modifier = Modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp))
                    if (u.purchases > 0) T(tp(u.purchases, "{n} purchase|{n} purchases"), TS.Caption, color = C.muted, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (i < shown.lastIndex) Divider()
        }
        if (!all && list.size > shown.size) {
            Divider()
            T(
                t("Show all"), TS.Label, FontWeight.SemiBold, C.green, align = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { all = true }.padding(14.dp),
            )
        }
    }
}

/** The server names the automatic link "Main link"; the others carry the user's own names. */
private fun linkName(name: String): String = if (name == "Main link") t("Main link") else name

private fun sourceName(key: String): String = when (key) { "direct" -> t("Direct"); "telegram" -> t("Telegram"); else -> key }

private fun channelName(key: String): String = when (key) { "website" -> t("Website"); "app" -> t("App"); "telegram" -> t("Telegram"); else -> key }

private fun fRevenue(list: List<MoneyAmount>): String = if (list.isEmpty()) "-" else list.joinToString(", ") { fMoney(it.minor, it.currency) }

private fun pct(part: Int, whole: Int): String = if (whole <= 0) "-" else "${(part * 100.0 / whole).roundToInt()}%"

/** The list price in the interface language; falls back to the server's English label. */
private fun productPrice(p: dev.jaganet.api.Product): String =
    if (p.priceMinor != null && p.currency != null) fMoney(p.priceMinor!!, p.currency!!) else p.displayPrice.substringBefore('/')
