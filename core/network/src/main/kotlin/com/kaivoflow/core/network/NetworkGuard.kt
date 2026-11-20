package com.kaivoflow.core.network

import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The outbound network rules for every Step, in plain JDK code so they run in JVM tests.
 *
 * Default: public hosts over https only. Loopback, LAN, `.local` style names and single-label names need an
 * explicit entry in the [HostAllowList] (the person typed it, for example for Ollama). Link-local (including
 * the cloud metadata address) and reserved ranges are blocked even when listed. Redirects are checked at every
 * hop, and the resolved addresses are checked again at connect time ([GuardedDns]) so a public name that
 * answers with a private address (DNS rebinding) is refused.
 *
 * Original code. Behaviour follows docs/audit-v2/03 (I-02, I-03, I-07).
 */
fun interface HostAllowList {
    fun isAllowed(host: String): Boolean

    companion object {
        val None = HostAllowList { false }
    }
}

/** Mutable allow-list kept in memory; the app wraps it with persistence ([PrefsHostAllowList]). */
open class MutableHostAllowList(initial: Collection<String> = emptyList()) : HostAllowList {
    private val hosts = CopyOnWriteArraySet(initial.mapNotNull { NetworkGuard.normalizeHost(it) })

    override fun isAllowed(host: String): Boolean = NetworkGuard.normalizeHost(host)?.let { it in hosts } == true

    /** Adds one exact host name or IP literal. Wildcards and ranges are refused. Returns false for a bad entry. */
    open fun allow(host: String): Boolean {
        val h = NetworkGuard.normalizeHost(host) ?: return false
        if (NetworkGuard.classify(h) in NetworkGuard.NEVER_ALLOWED) return false
        return hosts.add(h)
    }

    /** Adds the host of [url] when it is loopback or LAN (what a local model server is). Returns true when added. */
    fun allowLocalFromUrl(url: String): Boolean {
        val host = NetworkGuard.parse(url)?.second ?: return false
        if (NetworkGuard.classify(host) !in setOf(NetHostClass.Loopback, NetHostClass.Private)) return false
        return allow(host)
    }

    open fun revoke(host: String) { NetworkGuard.normalizeHost(host)?.let { hosts.remove(it) } }
    fun all(): Set<String> = hosts.toSortedSet()
}

enum class NetHostClass { Public, Loopback, Private, LinkLocal, Reserved }

sealed interface GuardVerdict {
    data class Allowed(val host: String, val cleartext: Boolean) : GuardVerdict
    data class Blocked(val reason: String) : GuardVerdict
}

/** Thrown from DNS lookup when a resolved address is not allowed. Extends the DNS failure type so OkHttp passes it through. */
class BlockedAddressException(message: String) : UnknownHostException(message)

object NetworkGuard {
    val NEVER_ALLOWED = setOf(NetHostClass.LinkLocal, NetHostClass.Reserved)
    private val INTERNAL_SUFFIXES = listOf(".local", ".internal", ".lan", ".home", ".corp", ".intranet", ".home.arpa", ".private")
    private val URL_RE = Regex("(?i)^(https?)://([^/?#]*)([/?#].*)?$")
    private val NUMERIC_LABEL = Regex("(?i)^(0x[0-9a-f]+|[0-9]+)$")

    /** Lower-case host without brackets or trailing dot; null when it is empty, has a wildcard, space or port. */
    fun normalizeHost(raw: String): String? {
        var h = raw.trim().lowercase().removePrefix("[").removeSuffix("]").trimEnd('.')
        if (h.isEmpty() || h.length > 253) return null
        if (h.any { it.isWhitespace() || it == '*' || it == '/' || it == '@' || it == '?' || it == '#' || it == '\\' }) return null
        if (h.count { it == ':' } == 1) return null // host:port is not a host
        return h
    }

    /** Splits `scheme://[user@]host[:port]/...`. Null when the shape is not an http(s) URL. */
    internal fun parse(url: String): Triple<String, String, Int?>? {
        val raw = url.trim()
        if (raw.any { it.isWhitespace() || it.code < 32 || it == '\\' }) return null
        val m = URL_RE.matchEntire(raw) ?: return null
        val scheme = m.groupValues[1].lowercase()
        var authority = m.groupValues[2]
        authority = authority.substringAfterLast('@')
        if (authority.isEmpty()) return null
        val host: String
        val port: Int?
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) return null
            host = authority.substring(1, end).lowercase()
            val rest = authority.substring(end + 1)
            port = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.drop(1).toIntOrNull() ?: return null
                else -> return null
            }
        } else {
            val idx = authority.lastIndexOf(':')
            host = (if (idx >= 0) authority.substring(0, idx) else authority).lowercase().trimEnd('.')
            port = if (idx >= 0) authority.substring(idx + 1).toIntOrNull() ?: return null else null
        }
        if (host.isEmpty() || (port != null && port !in 1..65535)) return null
        return Triple(scheme, host, port)
    }

    fun checkUrl(url: String, allow: HostAllowList): GuardVerdict {
        val (scheme, host, _) = parse(url) ?: return GuardVerdict.Blocked("only http and https addresses are allowed")
        return checkHost(scheme, host, allow)
    }

    fun checkHost(scheme: String, host: String, allow: HostAllowList): GuardVerdict {
        val cls = classify(host)
        val listed = allow.isAllowed(host)
        val cleartext = scheme == "http"
        return when (cls) {
            NetHostClass.LinkLocal -> GuardVerdict.Blocked("link-local and cloud metadata addresses are always blocked")
            NetHostClass.Reserved -> GuardVerdict.Blocked("this address range is reserved and blocked")
            NetHostClass.Loopback, NetHostClass.Private ->
                if (listed) GuardVerdict.Allowed(host, cleartext)
                else GuardVerdict.Blocked("\"$host\" is on this phone or your local network; add it to Allowed local hosts to use it")
            NetHostClass.Public ->
                if (cleartext && !listed) GuardVerdict.Blocked("cleartext http is only allowed for hosts you added to Allowed local hosts; use https")
                else GuardVerdict.Allowed(host, cleartext)
        }
    }

    /** Checks one resolved address of [host]. Used at connect time. */
    fun checkAddress(host: String, address: InetAddress, allow: HostAllowList): GuardVerdict {
        val literal = address.hostAddress ?: return GuardVerdict.Blocked("unreadable address")
        val cls = classify(literal.substringBefore('%'))
        return when {
            cls in NEVER_ALLOWED -> GuardVerdict.Blocked("\"$host\" resolves to a blocked address")
            (cls == NetHostClass.Loopback || cls == NetHostClass.Private) && !allow.isAllowed(host) && !allow.isAllowed(literal) ->
                GuardVerdict.Blocked("\"$host\" resolves to a local address; add it to Allowed local hosts to use it")
            else -> GuardVerdict.Allowed(host, false)
        }
    }

    fun classify(hostIn: String): NetHostClass {
        val host = hostIn.lowercase().trimEnd('.')
        if (host.contains(':')) return classifyV6(host)
        val labels = host.split('.')
        if (labels.all { NUMERIC_LABEL.matches(it) } && labels.size <= 4) {
            val ip = parseIpv4(host) ?: return NetHostClass.Reserved
            return classifyV4(ip)
        }
        if (host == "localhost" || host.endsWith(".localhost")) return NetHostClass.Loopback
        if (!host.contains('.')) return NetHostClass.Private
        if (INTERNAL_SUFFIXES.any { host.endsWith(it) }) return NetHostClass.Private
        return NetHostClass.Public
    }

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
        val last = nums.last()
        if (last >= (1L shl (8 * (5 - nums.size)))) return null
        return acc or last
    }

    private fun classifyV4(ip: Long): NetHostClass {
        val a = (ip shr 24).toInt() and 255
        val b = (ip shr 16).toInt() and 255
        val c = (ip shr 8).toInt() and 255
        return when {
            a == 127 -> NetHostClass.Loopback
            a == 0 -> NetHostClass.Reserved
            a == 10 -> NetHostClass.Private
            a == 172 && b in 16..31 -> NetHostClass.Private
            a == 192 && b == 168 -> NetHostClass.Private
            a == 169 && b == 254 -> NetHostClass.LinkLocal
            a == 100 && b == 100 && c == 100 && (ip and 255L) == 200L -> NetHostClass.LinkLocal
            a == 100 && b in 64..127 -> NetHostClass.Private
            a == 192 && b == 0 && c == 0 -> NetHostClass.Reserved
            a == 198 && b in 18..19 -> NetHostClass.Reserved
            a >= 224 -> NetHostClass.Reserved
            else -> NetHostClass.Public
        }
    }

    private fun classifyV6(host: String): NetHostClass {
        val g = parseIpv6(host) ?: return NetHostClass.Reserved
        if (g.all { it == 0 }) return NetHostClass.Reserved
        if (g.take(7).all { it == 0 } && g[7] == 1) return NetHostClass.Loopback
        if (g.take(5).all { it == 0 } && g[5] == 0xffff) return classifyV4((g[6].toLong() shl 16) or g[7].toLong())
        if (g.take(6).all { it == 0 }) return classifyV4((g[6].toLong() shl 16) or g[7].toLong())
        if (g[0] and 0xffc0 == 0xfe80) return NetHostClass.LinkLocal
        if (g[0] == 0xfd00 && g[1] == 0x0ec2) return NetHostClass.LinkLocal
        if (g[0] and 0xfe00 == 0xfc00) return NetHostClass.Private
        if (g[0] and 0xff00 == 0xff00) return NetHostClass.Reserved
        return NetHostClass.Public
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

/** What to do with a redirect response. */
sealed interface RedirectDecision {
    data class Follow(val url: String, val method: String, val headers: Map<String, String>, val keepBody: Boolean) : RedirectDecision
    data class Refuse(val reason: String) : RedirectDecision
}

/**
 * Redirect policy: at most [maxHops], every target passes [NetworkGuard], https never drops to http, and
 * credentials never travel to a different origin. 301, 302 and 303 turn a non-GET/HEAD request into GET without
 * a body; 307 and 308 keep method and body.
 */
object RedirectPolicy {
    const val DEFAULT_MAX_HOPS = 5
    private val STATUSES = setOf(301, 302, 303, 307, 308)
    private val SENSITIVE_NAME = Regex("(?i)(authorization|cookie|api[-_]?key|token|secret|auth|session|password|credential)")

    fun isRedirect(status: Int) = status in STATUSES

    fun decide(
        currentUrl: String, status: Int, location: String?, method: String, headers: Map<String, String>,
        hop: Int, allow: HostAllowList, maxHops: Int = DEFAULT_MAX_HOPS,
    ): RedirectDecision {
        if (location.isNullOrBlank()) return RedirectDecision.Refuse("the server redirected without saying where")
        if (hop >= maxHops) return RedirectDecision.Refuse("too many redirects (more than $maxHops)")
        val from = NetworkGuard.parse(currentUrl) ?: return RedirectDecision.Refuse("the address is not valid")
        val target = resolve(currentUrl, location.trim()) ?: return RedirectDecision.Refuse("the redirect address is not valid")
        val to = NetworkGuard.parse(target) ?: return RedirectDecision.Refuse("the redirect points to something other than http or https")
        if (from.first == "https" && to.first == "http") return RedirectDecision.Refuse("the redirect would move from https to http")
        when (val v = NetworkGuard.checkHost(to.first, to.second, allow)) {
            is GuardVerdict.Blocked -> return RedirectDecision.Refuse("the redirect was blocked: ${v.reason}")
            is GuardVerdict.Allowed -> Unit
        }
        val sameOrigin = from.first == to.first && from.second == to.second && effectivePort(from) == effectivePort(to)
        val outHeaders = if (sameOrigin) headers else headers.filterKeys { !SENSITIVE_NAME.containsMatchIn(it) }
        val upper = method.uppercase()
        val toGet = status in setOf(301, 302, 303) && upper != "GET" && upper != "HEAD"
        val newHeaders = if (toGet) outHeaders.filterKeys { !it.equals("Content-Type", true) && !it.equals("Content-Length", true) } else outHeaders
        return RedirectDecision.Follow(target, if (toGet) "GET" else upper, newHeaders, keepBody = !toGet)
    }

    private fun effectivePort(t: Triple<String, String, Int?>) = t.third ?: if (t.first == "https") 443 else 80

    /** Absolute, protocol-relative, root-relative and path-relative Location values against [base]. */
    internal fun resolve(base: String, location: String): String? {
        if (location.any { it.isWhitespace() || it.code < 32 }) return null
        if (Regex("(?i)^[a-z][a-z0-9+.-]*:").containsMatchIn(location)) return location
        val m = Regex("(?i)^(https?)://([^/?#]*)([^?#]*)").find(base) ?: return null
        val scheme = m.groupValues[1]; val auth = m.groupValues[2]; val path = m.groupValues[3]
        return when {
            location.startsWith("//") -> "$scheme:$location"
            location.startsWith("/") -> "$scheme://$auth$location"
            location.startsWith("?") -> "$scheme://$auth$path$location"
            else -> "$scheme://$auth${path.substringBeforeLast('/', "")}/$location"
        }
    }
}
