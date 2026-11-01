package dev.jaganet.server.services

import dev.jaganet.server.Ctx
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.math.BigInteger
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** What the lists say about one address. */
@Serializable
data class BlocklistHit(
    val ip: String,
    /** Subnets of the Roskomnadzor block list that contain the address. */
    val rknSubnets: List<String> = emptyList(),
    /** Hosting / CDN providers whose ranges (throttled in Russia) contain it. */
    val providers: List<String> = emptyList(),
    /** Subset of [rknSubnets] that makes it an alarm: the address itself, or a subnet no wider than the configured limit. */
    val rknExact: List<String> = emptyList(),
) {
    /** Registry match only through a wider subnet: a warning, not a block. */
    val rknSubnetOnly get() = rknSubnets.isNotEmpty() && rknExact.isEmpty()
    val listed get() = rknSubnets.isNotEmpty() || providers.isNotEmpty()
}

@Serializable
data class BlocklistRes(val loaded: Boolean, val updatedAt: String? = null, val hits: List<BlocklistHit> = emptyList())

/**
 * The checks of cheburcheck.ru (github.com/LowderPlay/cheburcheck) for our own addresses:
 * the Roskomnadzor block list of subnets (antifilter.network ipsum.lst) and the ranges of
 * hosting and CDN providers throttled in Russia (123jjck/cdn-ip-ranges). Downloaded every
 * 12 hours and checked in memory.
 *
 * It does not see a VPN address that Russian DPI (TSPU) blocks on its own: those never reach
 * a public list. It catches registry blocks and providers whose whole ranges are slowed down.
 */
class Blocklists(
    private val ctx: Ctx,
    private val fetch: suspend (String) -> String = ::httpGet,
) {
    private class Net(val v6: Boolean, val base: BigInteger, val mask: BigInteger, val text: String, val bits: Int) {
        fun contains(v6: Boolean, ip: BigInteger) = v6 == this.v6 && ip.and(mask) == base
    }

    @Volatile private var rkn: List<Net> = emptyList()
    @Volatile private var cdn: List<Pair<Net, String>> = emptyList()
    @Volatile var updatedAt: Instant? = null
        private set
    val loaded get() = updatedAt != null
    private val log = LoggerFactory.getLogger("blocklists")

    /** Downloads both lists; keeps the old ones if a download fails. True if anything loaded. */
    suspend fun refresh(): Boolean {
        val cfg = ctx.cfg.monitor
        if (cfg.blocklistRknUrl.isBlank() && cfg.blocklistCdnUrl.isBlank()) return false
        var ok = false
        if (cfg.blocklistRknUrl.isNotBlank()) runCatching { fetch(cfg.blocklistRknUrl) }
            .onSuccess { text -> rkn = text.lineSequence().mapNotNull { parse(it.trim()) }.toList(); ok = true }
            .onFailure { log.warn("RKN list download failed: {}", it.message) }
        if (cfg.blocklistCdnUrl.isNotBlank()) runCatching { fetch(cfg.blocklistCdnUrl) }
            .onSuccess { text ->
                // provider,cidr,region
                cdn = text.lineSequence().drop(1).mapNotNull { line ->
                    val f = line.split(',')
                    if (f.size < 2) null else parse(f[1].trim())?.let { it to f[0].trim() }
                }.toList()
                ok = true
            }
            .onFailure { log.warn("CDN list download failed: {}", it.message) }
        if (ok) {
            updatedAt = ctx.now()
            log.info("block lists: {} RKN subnets, {} provider ranges", rkn.size, cdn.size)
        }
        return ok
    }

    /** Lists containing [ip] (an address, not a name). */
    fun check(ip: String): BlocklistHit {
        val (v6, n) = toBig(ip) ?: return BlocklistHit(ip)
        val warnPrefix = ctx.cfg.monitor.rknSubnetWarnPrefix
        val matched = rkn.filter { it.contains(v6, n) }
        return BlocklistHit(
            ip,
            rknSubnets = matched.map { it.text }.distinct(),
            rknExact = matched.filter { if (it.v6) it.bits == 128 else it.bits >= warnPrefix }.map { it.text }.distinct(),
            providers = cdn.filter { it.first.contains(v6, n) }.map { it.second }.distinct().sorted(),
        )
    }

    /** Checks a host: an address, or a name resolved to all its addresses. */
    suspend fun checkHost(host: String): List<BlocklistHit> {
        val ips = if (toBig(host) != null) listOf(host)
        else withContext(Dispatchers.IO) { runCatching { InetAddress.getAllByName(host).map { it.hostAddress } }.getOrDefault(emptyList()) }
        return ips.map(::check)
    }

    companion object {
        private fun toBig(ip: String): Pair<Boolean, BigInteger>? {
            val s = ip.trim().removePrefix("[").removeSuffix("]")
            if (s.isEmpty() || !(s.all { it.isDigit() || it == '.' } || ':' in s)) return null // literals only, never DNS
            return runCatching { InetAddress.getByName(s) }.getOrNull()?.let { a -> (a.address.size == 16) to BigInteger(1, a.address) }
        }

        private fun parse(cidr: String): Net? {
            if (cidr.isEmpty() || cidr.startsWith("#")) return null
            val (addr, bitsText) = if ('/' in cidr) cidr.substringBefore('/') to cidr.substringAfter('/') else cidr to null
            val (v6, n) = toBig(addr) ?: return null
            val width = if (v6) 128 else 32
            val bits = bitsText?.toIntOrNull() ?: width
            if (bits !in 0..width) return null
            val mask = BigInteger.ONE.shiftLeft(width).subtract(BigInteger.ONE).xor(BigInteger.ONE.shiftLeft(width - bits).subtract(BigInteger.ONE))
            return Net(v6, n.and(mask), mask, cidr, bits)
        }

        private val http by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build() }

        suspend fun httpGet(url: String): String = withContext(Dispatchers.IO) {
            val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofString())
            check(res.statusCode() == 200) { "HTTP ${res.statusCode()} from $url" }
            res.body()
        }
    }
}
