package com.kaivoflow.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

class NetworkGuardTest {
    private val none = HostAllowList.None
    private fun blocked(url: String, allow: HostAllowList = none) = NetworkGuard.checkUrl(url, allow) is GuardVerdict.Blocked
    private fun allowed(url: String, allow: HostAllowList = none) = NetworkGuard.checkUrl(url, allow) is GuardVerdict.Allowed

    @Test fun publicHttpsPasses() {
        assertTrue(allowed("https://api.openai.com/v1/models"))
        assertTrue(allowed("https://example.com:8443/x?y=1#z"))
    }

    @Test fun cleartextToPublicHostsIsBlockedUnlessListed() {
        assertTrue(blocked("http://example.com/"))
        assertTrue(allowed("http://example.com/", MutableHostAllowList(listOf("example.com"))))
    }

    @Test fun loopbackAndLanNeedAnExplicitEntry() {
        listOf("http://localhost:11434", "http://127.0.0.1/", "http://10.0.2.2:11434", "http://192.168.1.20", "http://172.16.0.5", "http://[::1]:8080", "http://printer.local/", "http://nas/").forEach { assertTrue(it, blocked(it)) }
        val allow = MutableHostAllowList(listOf("192.168.1.20", "localhost", "10.0.2.2", "nas"))
        assertTrue(allowed("http://192.168.1.20:11434/api/tags", allow))
        assertTrue(allowed("http://localhost:11434", allow))
        assertTrue(allowed("http://10.0.2.2:11434", allow))
        assertTrue(allowed("http://nas/", allow))
        assertTrue("another LAN host stays blocked", blocked("http://192.168.1.21", allow))
    }

    @Test fun metadataAndReservedAreBlockedEvenWhenListed() {
        val allow = MutableHostAllowList()
        assertFalse(allow.allow("169.254.169.254"))
        val forced = HostAllowList { true }
        listOf("http://169.254.169.254/latest/meta-data", "https://metadata.google.internal.", "http://0.0.0.0/", "http://224.0.0.1", "http://[fe80::1]/", "http://100.100.100.200/").forEach {
            val v = NetworkGuard.checkUrl(it, forced)
            if (it.contains("metadata.google")) assertTrue("internal name needs the list, forced list allows it", v is GuardVerdict.Allowed) else assertTrue(it, v is GuardVerdict.Blocked)
        }
    }

    @Test fun obfuscatedLoopbackFormsAreRecognised() {
        listOf("http://2130706433/", "http://0x7f000001/", "http://0177.0.0.1/", "http://127.1/", "http://[::ffff:127.0.0.1]/", "http://[::ffff:7f00:1]/").forEach { assertTrue(it, blocked(it)) }
    }

    @Test fun lookAlikeNamesAreNotLocal() {
        assertTrue(allowed("https://localhost.evil.com/"))
        assertEquals(NetHostClass.Public, NetworkGuard.classify("notlocal.example.com"))
    }

    @Test fun userInfoDoesNotHideTheRealHost() {
        assertTrue(blocked("https://example.com@127.0.0.1/"))
        assertTrue(blocked("https://user:pass@10.0.0.1:80/"))
    }

    @Test fun badShapesAreBlocked() {
        listOf("", "ftp://example.com", "file:///etc/passwd", "javascript:alert(1)", "https://", "https://exa mple.com", "https://example.com:99999", "http://a\\b").forEach { assertTrue(it, blocked(it)) }
    }

    @Test fun allowListRejectsWildcardsAndPorts() {
        val a = MutableHostAllowList()
        assertFalse(a.allow("*.local")); assertFalse(a.allow("192.168.1.1:11434")); assertFalse(a.allow("")); assertFalse(a.allow("a b"))
        assertTrue(a.allow(" NAS.Local. ")); assertTrue(a.isAllowed("nas.local")); a.revoke("nas.local"); assertFalse(a.isAllowed("nas.local"))
    }

    @Test fun connectTimeAddressCheckCatchesRebinding() {
        val loop = InetAddress.getByAddress("evil.example.com", byteArrayOf(127, 0, 0, 1))
        val meta = InetAddress.getByAddress("evil.example.com", byteArrayOf(169.toByte(), 254.toByte(), 169.toByte(), 254.toByte()))
        val pub = InetAddress.getByAddress("good.example.com", byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
        assertTrue(NetworkGuard.checkAddress("evil.example.com", loop, none) is GuardVerdict.Blocked)
        assertTrue(NetworkGuard.checkAddress("evil.example.com", meta, MutableHostAllowList(listOf("evil.example.com"))) is GuardVerdict.Blocked)
        assertTrue(NetworkGuard.checkAddress("good.example.com", pub, none) is GuardVerdict.Allowed)
        assertTrue("a listed host may resolve to a LAN address", NetworkGuard.checkAddress("nas.local", InetAddress.getByAddress("nas.local", byteArrayOf(192.toByte(), 168.toByte(), 1, 5)), MutableHostAllowList(listOf("nas.local"))) is GuardVerdict.Allowed)
    }

    // ---- redirects ----

    private val h = mapOf("Authorization" to "Bearer abc", "x-api-key" to "k", "Accept" to "application/json", "Content-Type" to "application/json")
    private fun decide(from: String, status: Int, loc: String?, method: String = "GET", hop: Int = 0, allow: HostAllowList = none) =
        RedirectPolicy.decide(from, status, loc, method, h, hop, allow)

    @Test fun sameOriginRedirectKeepsHeaders() {
        val d = decide("https://api.example.com/a", 302, "/b") as RedirectDecision.Follow
        assertEquals("https://api.example.com/b", d.url); assertEquals(h, d.headers)
    }

    @Test fun crossOriginRedirectDropsCredentials() {
        val d = decide("https://api.example.com/a", 302, "https://cdn.example.org/x") as RedirectDecision.Follow
        assertEquals(mapOf("Accept" to "application/json", "Content-Type" to "application/json"), d.headers)
    }

    @Test fun redirectToPrivateOrMetadataIsRefused() {
        assertTrue(decide("https://example.com/", 302, "http://169.254.169.254/latest") is RedirectDecision.Refuse)
        assertTrue(decide("https://example.com/", 301, "https://192.168.1.1/admin") is RedirectDecision.Refuse)
        assertTrue(decide("https://example.com/", 301, "https://[::1]/") is RedirectDecision.Refuse)
        assertTrue(decide("https://example.com/", 302, "https://127.0.0.1:9000/") is RedirectDecision.Refuse)
    }

    @Test fun httpsToHttpDowngradeIsRefusedEvenForAListedHost() {
        assertTrue(decide("https://nas.local/", 302, "http://nas.local/x", allow = MutableHostAllowList(listOf("nas.local"))) is RedirectDecision.Refuse)
    }

    @Test fun postBecomesGetOn302Only() {
        val a = decide("https://e.com/", 302, "/n", method = "POST") as RedirectDecision.Follow
        assertEquals("GET", a.method); assertFalse(a.keepBody); assertFalse(a.headers.containsKey("Content-Type"))
        val b = decide("https://e.com/", 307, "/n", method = "POST") as RedirectDecision.Follow
        assertEquals("POST", b.method); assertTrue(b.keepBody)
    }

    @Test fun hopLimitAndMissingLocation() {
        assertTrue(decide("https://e.com/", 302, "/x", hop = 5) is RedirectDecision.Refuse)
        assertTrue(decide("https://e.com/", 302, "/x", hop = 4) is RedirectDecision.Follow)
        assertTrue(decide("https://e.com/", 302, null) is RedirectDecision.Refuse)
        assertTrue(decide("https://e.com/", 302, "javascript:alert(1)") is RedirectDecision.Refuse)
        assertTrue(decide("https://e.com/", 302, "/a b") is RedirectDecision.Refuse)
    }

    @Test fun relativeLocationsResolve() {
        assertEquals("https://e.com/d/x", RedirectPolicy.resolve("https://e.com/d/y?q=1", "x"))
        assertEquals("https://e.com/z", RedirectPolicy.resolve("https://e.com/d/y", "//e.com/z".removePrefix("//e.com")))
        assertEquals("https://other.com/p", RedirectPolicy.resolve("https://e.com/d", "//other.com/p"))
        assertEquals("https://e.com/d/y?n=2", RedirectPolicy.resolve("https://e.com/d/y?q=1", "?n=2"))
        assertTrue(RedirectPolicy.isRedirect(308) && !RedirectPolicy.isRedirect(304))
    }
}
