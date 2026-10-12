package dev.jaganet.server.protocols

import java.security.SecureRandom
import java.util.Base64

/**
 * Generates a fresh AmneziaWG obfuscation profile for a node: AmneziaWG 3.1, the profile the
 * AmneziaVPN app itself installs on servers (amnezia-client awgInstaller + protocolConstants).
 *  - I1: the first packet of every handshake looks like a DNS answer (icloud.com).
 *  - HeaderProtectionKey: message headers are encrypted, so H1–H4 can stay 1–4.
 *  - Random trailers and padding, randomized rekey and keepalive timings.
 * AWG 1.0 profiles (junk sizes and headers only) are recognized by some mobile networks:
 * the first handshake gets through, then the connection is cut.
 *
 * `./gradlew :server:awgParams` prints one as JSON and as awg-quick lines.
 */
object AwgParams {
    private val r = SecureRandom()
    private fun between(a: Int, b: Int) = a + r.nextInt(b - a + 1)

    /** AmneziaVPN's default first-packet signature: a DNS answer for icloud.com. */
    const val DNS_SIGNATURE = "<r 2><b 0x858000010001000000000669636c6f756403636f6d0000010001c00c000100010000105a00044d583737>"

    /** Keys only the client uses (the server leaves them out of its own interface). */
    val CLIENT_ONLY = setOf("I1", "I2", "I3", "I4", "I5")

    fun generate(): Map<String, String> {
        // Paddings: ≥ 12; S1 + 56 ≠ S2 keeps init/response sizes distinct.
        val s1 = between(15, 150)
        var s2: Int
        do s2 = between(15, 150) while (s1 + 56 == s2)
        return linkedMapOf(
            "Jc" to between(4, 6).toString(),
            "Jmin" to "10",
            "Jmax" to "50",
            "S1" to s1.toString(),
            "S2" to s2.toString(),
            "S3" to between(12, 64).toString(),
            "S4" to between(12, 32).toString(),
            "H1" to "1",
            "H2" to "2",
            "H3" to "3",
            "H4" to "4",
            "I1" to DNS_SIGNATURE,
            "HeaderProtectionKey" to Base64.getEncoder().encodeToString(ByteArray(32).also(r::nextBytes)),
            "ContentPaddingAddition" to "10-100",
            "RekeyAfterTime" to "100-120",
            "RekeyTimeout" to "3-7",
            "RejectAfterTime" to "150-180",
            "KeepaliveTimeout" to "5-15",
            "MaxHandshakeAttempts" to "15-20",
            "RandomTrailers" to "on",
            "DisableCookies" to "on",
        )
    }

    /** A UDP port for a new node: random, away from WireGuard's well-known 51820/51821. */
    fun port(): Int = between(20000, 60000).let { if (it in 51800..51900) it + 200 else it }

    /** True for a profile made before AmneziaWG 3.1 (no header protection): it should be replaced. */
    fun isLegacy(p: Map<String, String>) = p["HeaderProtectionKey"].isNullOrBlank()
}

/** Prints a profile: `json` (all keys), `conf` (the server's awg-quick lines) or both, plus `port`. */
fun main(args: Array<String>) {
    val p = AwgParams.generate()
    val json = p.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }
    when (args.firstOrNull()) {
        "json" -> println(json)
        "port" -> println(AwgParams.port())
        else -> {
            println("// servers.protocols.amneziawg.obfuscation")
            println(json)
            println("\n# awg-quick [Interface] lines for the node")
            p.filterKeys { it !in AwgParams.CLIENT_ONLY }.forEach { (k, v) -> println("$k = $v") }
        }
    }
}
