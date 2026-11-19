package com.kaivoflow.core.ai

/** Optional DNS hook for hosts that can resolve names; the pure JVM tests pass a fake. */
fun interface HostResolver {
    fun resolve(host: String): List<String>
}

enum class HostClass { Public, Loopback, Private, LinkLocal, Reserved }

/**
 * Decides whether a model base URL may receive a request carrying the user's key.
 *
 * Default: public hosts over https only. Loopback (`localhost`, `127.0.0.0/8`, `::1`) needs
 * [allowLoopback]; private LAN ranges need [allowPrivateLan]; both may then use cleartext, which is
 * what a local Ollama needs. Link-local, cloud-metadata and reserved ranges are refused whatever the
 * flags say. URLs with credentials, a query or a fragment are refused, and names are matched exactly
 * (`localhost.evil.com` is a public name, never "localhost").
 *
 * Reasoned, not testable on the JVM: a public name that resolves to a private address later (DNS
 * rebinding) is only caught when a [resolver] is supplied by the host application.
 */
data class EndpointPolicy(
    val allowLoopback: Boolean = false,
    val allowPrivateLan: Boolean = false,
    val resolver: HostResolver? = null,
) {
    sealed interface Verdict {
        data class Allowed(val baseUrl: String, val host: String, val isLocal: Boolean, val cleartext: Boolean) : Verdict
        data class Blocked(val reason: String) : Verdict
    }

    fun check(url: String): Verdict {
        val raw = url.trim()
        if (raw.isEmpty()) return blocked("the address is empty")
        if (raw.any { it.isWhitespace() || it.code < 32 || it == '\\' }) return blocked("the address has spaces or control characters")
        val m = URL_RE.matchEntire(raw) ?: return blocked("only http and https addresses are allowed")
        val scheme = m.groupValues[1].lowercase()
        val authority = m.groupValues[2]
        val path = m.groupValues[3]
        if (path.contains('?') || path.contains('#')) return blocked("a base address must not contain a query or fragment")
        if (authority.contains('@')) return blocked("addresses with a user name or password are not allowed")
        val (host, port) = splitHostPort(authority) ?: return blocked("the host or port is not valid")
        if (port != null && port !in 1..65535) return blocked("the port is not valid")
        val cls = classify(host)
        val local = cls == HostClass.Loopback || cls == HostClass.Private
        when (cls) {
            HostClass.LinkLocal -> return blocked("link-local and cloud metadata addresses are blocked")
            HostClass.Reserved -> return blocked("this address range is reserved and blocked")
            HostClass.Loopback -> if (!allowLoopback) return blocked("local addresses are blocked; allow a local model server explicitly to use one")
            HostClass.Private -> if (!allowPrivateLan) return blocked("private network addresses are blocked; allow a LAN model server explicitly to use one")
            HostClass.Public -> if (scheme != "https") return blocked("cleartext http is only allowed for an explicitly allowed local server")
        }
        if (cls == HostClass.Public && !isIpLiteral(host)) {
            val ips = runCatching { resolver?.resolve(host) }.getOrNull().orEmpty()
            for (ip in ips) when (classify(ip)) {
                HostClass.LinkLocal, HostClass.Reserved -> return blocked("the name resolves to a blocked address")
                HostClass.Loopback, HostClass.Private -> return blocked("the name resolves to a local address")
                HostClass.Public -> Unit
            }
        }
        val hostPart = if (host.contains(':')) "[$host]" else host
        val base = "$scheme://$hostPart${port?.let { ":$it" } ?: ""}${path.trimEnd('/')}"
        return Verdict.Allowed(base, host, local, cleartext = scheme == "http")
    }

    /** The checked base URL, or a [ModelException] of kind Policy. */
    fun require(url: String): Verdict.Allowed = when (val v = check(url)) {
        is Verdict.Allowed -> v
        is Verdict.Blocked -> throw ModelException(ModelErrorKind.Policy, "Model address refused: ${v.reason}.")
    }

    private fun blocked(reason: String) = Verdict.Blocked(reason)

    companion object {
        private val URL_RE = Regex("(?i)^(https?)://([^/?#]*)((?:[/?#].*)?)$")
        private val INTERNAL_SUFFIXES = listOf(".local", ".internal", ".lan", ".home", ".corp", ".intranet", ".home.arpa", ".private")

        private fun splitHostPort(authority: String): Pair<String, Int?>? {
            if (authority.isEmpty()) return null
            if (authority.startsWith("[")) {
                val end = authority.indexOf(']')
                if (end < 0) return null
                val host = authority.substring(1, end).lowercase()
                val rest = authority.substring(end + 1)
                val port = when {
                    rest.isEmpty() -> null
                    rest.startsWith(":") -> rest.drop(1).toIntOrNull() ?: return null
                    else -> return null
                }
                return host to port
            }
            val idx = authority.lastIndexOf(':')
            val hostRaw = if (idx >= 0) authority.substring(0, idx) else authority
            val port = if (idx >= 0) (authority.substring(idx + 1).toIntOrNull() ?: return null) else null
            val host = hostRaw.lowercase().trimEnd('.')
            if (host.isEmpty() || host.contains(':')) return null
            return host to port
        }

        private fun isIpLiteral(host: String) = host.contains(':') || parseIpv4(host) != null

        /** Classifies a host name or IP literal. Names are matched exactly, never by substring. */
        fun classify(hostIn: String): HostClass {
            val host = hostIn.lowercase().trimEnd('.')
            if (host.contains(':')) return classifyV6(host)
            val labels = host.split('.')
            if (labels.all { NUMERIC_LABEL.matches(it) } && labels.size <= 4) {
                val ip = parseIpv4(host) ?: return HostClass.Reserved
                return classifyV4(ip)
            }
            if (host == "localhost" || host.endsWith(".localhost")) return HostClass.Loopback
            if (!host.contains('.')) return HostClass.Private
            if (INTERNAL_SUFFIXES.any { host.endsWith(it) }) return HostClass.Private
            return HostClass.Public
        }

        private val NUMERIC_LABEL = Regex("(?i)^(0x[0-9a-f]+|[0-9]+)$")

        /** inet_aton rules: 1 to 4 parts, decimal, octal (leading 0) or hex (0x). Null when malformed. */
        internal fun parseIpv4(host: String): Long? {
            val parts = host.split('.')
            if (parts.size !in 1..4 || parts.any { it.isEmpty() }) return null
            val nums = parts.map { p ->
                val v = when {
                    p.startsWith("0x", true) -> p.drop(2).toLongOrNull(16)
                    p.length > 1 && p.startsWith("0") -> p.drop(1).toLongOrNull(8)
                    else -> p.toLongOrNull(10)
                } ?: return null
                if (v < 0) return null
                v
            }
            var acc = 0L
            for (i in 0 until nums.size - 1) {
                if (nums[i] > 255) return null
                acc = acc or (nums[i] shl (24 - 8 * i))
            }
            val lastBits = 8 * (5 - nums.size)
            val last = nums.last()
            if (last >= (1L shl lastBits)) return null
            return acc or last
        }

        private fun classifyV4(ip: Long): HostClass {
            val a = (ip shr 24).toInt() and 255
            val b = (ip shr 16).toInt() and 255
            val c = (ip shr 8).toInt() and 255
            return when {
                a == 127 -> HostClass.Loopback
                a == 0 -> HostClass.Reserved
                a == 10 -> HostClass.Private
                a == 172 && b in 16..31 -> HostClass.Private
                a == 192 && b == 168 -> HostClass.Private
                a == 169 && b == 254 -> HostClass.LinkLocal
                a == 100 && b == 100 && c == 100 && (ip and 255L) == 200L -> HostClass.LinkLocal
                a == 100 && b in 64..127 -> HostClass.Private
                a == 192 && b == 0 && c == 0 -> HostClass.Reserved
                a == 198 && b in 18..19 -> HostClass.Reserved
                a >= 224 -> HostClass.Reserved
                else -> HostClass.Public
            }
        }

        private fun classifyV6(host: String): HostClass {
            val g = parseIpv6(host) ?: return HostClass.Reserved
            if (g.all { it == 0 }) return HostClass.Reserved
            if (g.take(7).all { it == 0 } && g[7] == 1) return HostClass.Loopback
            // IPv4-mapped (::ffff:a.b.c.d) and IPv4-compatible forms inherit the IPv4 verdict.
            if (g.take(5).all { it == 0 } && g[5] == 0xffff) return classifyV4(((g[6].toLong() shl 16) or g[7].toLong()))
            if (g.take(6).all { it == 0 }) return classifyV4(((g[6].toLong() shl 16) or g[7].toLong()))
            if (g[0] and 0xffc0 == 0xfe80) return HostClass.LinkLocal
            if (g[0] == 0xfd00 && g[1] == 0x0ec2) return HostClass.LinkLocal
            if (g[0] and 0xfe00 == 0xfc00) return HostClass.Private
            if (g[0] and 0xff00 == 0xff00) return HostClass.Reserved
            return HostClass.Public
        }

        private fun parseIpv6(text: String): IntArray? {
            var t = text.substringBefore('%')
            var tail: List<Int> = emptyList()
            if (t.contains('.')) {
                val idx = t.lastIndexOf(':')
                val v4 = parseIpv4(t.substring(idx + 1)) ?: return null
                tail = listOf(((v4 shr 16) and 0xffff).toInt(), (v4 and 0xffff).toInt())
                t = t.substring(0, idx + 1) + "0:0"
            }
            val halves = t.split("::")
            if (halves.size > 2) return null
            fun groups(s: String): List<Int>? =
                if (s.isEmpty()) emptyList() else s.split(':').map { if (it.isEmpty() || it.length > 4) return null else it.toIntOrNull(16) ?: return null }
            val head = groups(halves[0]) ?: return null
            val out = if (halves.size == 2) {
                val rest = groups(halves[1]) ?: return null
                val fill = 8 - head.size - rest.size
                if (fill < 1) return null
                head + List(fill) { 0 } + rest
            } else head
            if (out.size != 8) return null
            val arr = out.toIntArray()
            if (tail.isNotEmpty()) { arr[6] = tail[0]; arr[7] = tail[1] }
            return arr
        }
    }
}
