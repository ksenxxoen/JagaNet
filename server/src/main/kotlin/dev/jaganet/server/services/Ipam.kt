package dev.jaganet.server.services

/** Minimal IPv4 address allocation inside a node's subnet (e.g. 10.8.0.0/24). */
object Ipam {
    private fun toLong(ip: String) = ip.split('.').fold(0L) { a, o -> a * 256 + o.toLong() }
    private fun toIp(n: Long) = listOf(24, 16, 8, 0).joinToString(".") { ((n shr it) and 255).toString() }

    /** First free host. .0 is the network, .1 the node itself, the last one broadcast. */
    fun allocate(subnet: String, used: Collection<String>): String? {
        val (base, bitsStr) = subnet.split('/')
        val bits = bitsStr.toInt()
        require(bits in 8..30) { "bad subnet $subnet" }
        val size = 1L shl (32 - bits)
        val net = toLong(base) - toLong(base) % size
        val taken = used.map(::toLong).toSet()
        return (2 until size - 1).firstOrNull { net + it !in taken }?.let { toIp(net + it) }
    }
}
