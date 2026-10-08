package dev.jaganet.server.monitor

import com.sun.management.OperatingSystemMXBean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.management.ManagementFactory
import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.cert.X509Certificate
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Cumulative counters of a network interface (from /proc/net/dev). */
data class IfaceCounters(val rxBytes: Long, val txBytes: Long, val errors: Long, val drops: Long)

data class PingResult(val host: String, val avgMs: Double?, val lossPct: Double)

/** Everything the monitor measures on the machine. Tests use a fake. */
interface SystemProbe {
    fun cpu(): Double
    /** used, total bytes */
    fun memory(): Pair<Long, Long>
    /** free, total bytes of the root file system */
    fun disk(): Pair<Long, Long>
    fun wanInterface(): String?
    fun counters(iface: String): IfaceCounters?
    /** What the network card reports, Mbit/s; null on most virtual servers. */
    fun linkSpeedMbps(iface: String): Long?
    suspend fun ping(host: String): PingResult
    /** Resolution time in ms, null if it failed. */
    suspend fun dns(name: String): Long?
    suspend fun httpOk(url: String): Boolean
    /** Expiry of the HTTPS certificate of host:443. */
    suspend fun certExpiry(host: String): Instant?
}

/** Linux: /proc, /sys, the ping command and plain network calls. */
class LinuxProbe : SystemProbe {
    private val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

    override fun cpu() = os.cpuLoad.takeIf { it >= 0 } ?: 0.0

    override fun memory(): Pair<Long, Long> {
        // MemAvailable counts reclaimable cache as free, like `free -h` does.
        val info = runCatching { File("/proc/meminfo").readLines() }.getOrNull()
        fun kb(k: String) = info?.firstOrNull { it.startsWith("$k:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.times(1024)
        val total = kb("MemTotal") ?: os.totalMemorySize
        val available = kb("MemAvailable") ?: os.freeMemorySize
        return (total - available) to total
    }

    override fun disk(): Pair<Long, Long> = File("/").let { it.usableSpace to it.totalSpace }

    override fun wanInterface(): String? = runCatching {
        File("/proc/net/route").readLines().drop(1).map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size > 2 && it[1] == "00000000" }?.get(0)
    }.getOrNull()

    override fun counters(iface: String): IfaceCounters? = runCatching {
        val f = File("/proc/net/dev").readLines().firstOrNull { it.trim().startsWith("$iface:") } ?: return null
        val v = f.substringAfter(':').trim().split(Regex("\\s+")).map { it.toLong() }
        // rx: bytes packets errs drop fifo frame compressed multicast | tx: bytes packets errs drop …
        IfaceCounters(rxBytes = v[0], txBytes = v[8], errors = v[2] + v[10], drops = v[3] + v[11])
    }.getOrNull()

    override fun linkSpeedMbps(iface: String): Long? =
        runCatching { File("/sys/class/net/$iface/speed").readText().trim().toLong() }.getOrNull()?.takeIf { it > 0 }

    override suspend fun ping(host: String): PingResult = withContext(Dispatchers.IO) {
        val out = runCatching {
            val p = ProcessBuilder("ping", "-n", "-q", "-c", "5", "-i", "0.2", "-W", "1", host).redirectErrorStream(true).start()
            if (!p.waitFor(15, TimeUnit.SECONDS)) { p.destroyForcibly(); "" } else p.inputStream.bufferedReader().readText()
        }.getOrDefault("")
        val loss = Regex("([\\d.]+)% packet loss").find(out)?.groupValues?.get(1)?.toDoubleOrNull() ?: 100.0
        val avg = Regex("= [\\d.]+/([\\d.]+)/").find(out)?.groupValues?.get(1)?.toDoubleOrNull()
        PingResult(host, avg, loss)
    }

    override suspend fun dns(name: String): Long? = withContext(Dispatchers.IO) {
        val t0 = System.nanoTime()
        runCatching { InetAddress.getAllByName(name) }.getOrNull()?.let { (System.nanoTime() - t0) / 1_000_000 }
    }

    override suspend fun httpOk(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(15)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() in 200..399
        }.getOrDefault(false)
    }

    override suspend fun certExpiry(host: String): Instant? = withContext(Dispatchers.IO) {
        runCatching {
            (SSLSocketFactory.getDefault().createSocket(host, 443) as SSLSocket).use { s ->
                s.soTimeout = 10_000
                s.startHandshake()
                (s.session.peerCertificates.first() as X509Certificate).notAfter.toInstant()
            }
        }.getOrNull()
    }
}
