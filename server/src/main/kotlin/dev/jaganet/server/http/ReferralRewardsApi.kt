package dev.jaganet.server.http

import dev.jaganet.api.ReferralRewardSettings
import dev.jaganet.api.Role
import dev.jaganet.server.forbidden
import dev.jaganet.server.notFound
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/**
 * "Invite a friend" (issue #40) is for everyone while rewards are on (REFERRAL_REWARD_DAYS > 0);
 * the detailed referral analytics with campaign links stay for admins unless REFERRALS_PUBLIC=1.
 */
fun Services.invitesFor(role: Role) = ctx.live.referralRewards.enabled || referralsFor(role)

/** The visitor's address, as the referral redirect sees it (behind the reverse proxy: X-Forwarded-For). */
fun ApplicationCall.clientIp(): String =
    request.headers["X-Forwarded-For"]?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() } ?: request.origin.remoteHost

/** For the invite anti-fraud flag: HMACs of where this account is used from (no IP addresses stored). */
suspend fun ApplicationCall.rememberClient(s: Services, userId: String) =
    s.referralRewards.remember(userId, clientIp(), request.headers[HttpHeaders.UserAgent])

/** /v1 endpoints of the invite rewards. */
fun Route.referralRewardsApi(s: Services) {
    // The user's own invite links and what they earned (website, apps, bot).
    get("/referrals/invite") {
        val p = call.principal(s)
        if (!s.invitesFor(p.user.role)) throw notFound("The referral program is not available")
        call.respond(s.referralRewards.invite(p.user.id))
    }
    get("/admin/referral-rewards") {
        if (call.principal(s).user.role != Role.OWNER) throw forbidden()
        call.respond(s.referralRewards.admin(call.request.queryParameters["suspicious"] == "1"))
    }
    put("/admin/referral-rewards/settings") {
        if (call.principal(s).user.role != Role.OWNER) throw forbidden()
        s.referralRewards.saveSettings(call.receive<ReferralRewardSettings>())
        call.respond(s.referralRewards.admin(false))
    }
    post("/admin/referral-rewards/{id}/cancel") {
        if (call.principal(s).user.role != Role.OWNER) throw forbidden()
        call.respond(s.referralRewards.cancel(call.parameters["id"]!!))
    }
}
