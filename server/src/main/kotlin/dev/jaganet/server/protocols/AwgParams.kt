package dev.jaganet.server.protocols

import java.security.SecureRandom
import java.util.Base64

/**
 * Generates a fresh AmneziaWG obfuscation profile for a new node.
 * `./gradlew :server:awgParams` prints one as JSON for servers.protocols.amneziawg.obfuscation
 * and as awg-quick lines for the node's own interface config.
 */
object AwgParams {
    private val r = SecureRandom()
    private fun between(a: Int, b: Int) = a + r.nextInt(b - a + 1)

    fun generate(): Map<String, String> {
        // Paddings: ≥ 12 so header protection can be enabled; S1 + 56 ≠ S2 keeps init/response sizes distinct.
        val s1 = between(15, 150)
        var s2: Int
        do s2 = between(15, 150) while (s1 + 56 == s2)
        // Four disjoint header ranges, above the WireGuard message types 1–4.
        val headers = (0 until 4).map { i ->
            val base = 0x10000000L + i * 0x30000000L + between(0, 0x0FFFFFFF)
            "$base-${base + between(1000, 100_000)}"
        }
        return linkedMapOf(
            "Jc" to between(4, 12).toString(),
            "Jmin" to between(40, 80).toString(),
            "Jmax" to between(500, 1000).toString(), // below a typical 1280–1500 MTU: no fragmentation
            "S1" to s1.toString(),
            "S2" to s2.toString(),
            "S3" to between(12, 64).toString(),
            "S4" to between(12, 32).toString(),
            "H1" to headers[0],
            "H2" to headers[1],
            "H3" to headers[2],
            "H4" to headers[3],
            "HeaderProtectionKey" to Base64.getEncoder().encodeToString(ByteArray(32).also(r::nextBytes)),
        )
    }
}

fun main() {
    val p = AwgParams.generate()
    println("// servers.protocols.amneziawg.obfuscation")
    println(p.entries.joinToString(",\n", "{\n", "\n}") { (k, v) -> "  \"$k\": \"$v\"" })
    println("\n# awg-quick [Interface] lines for the node")
    p.forEach { (k, v) -> println("$k = $v") }
}
