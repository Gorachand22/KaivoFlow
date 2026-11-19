package com.kaivoflow.core.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointPolicyTest {
    private val strict = EndpointPolicy()
    private fun blocked(p: EndpointPolicy, url: String) = assertTrue("$url should be blocked", p.check(url) is EndpointPolicy.Verdict.Blocked)
    private fun allowed(p: EndpointPolicy, url: String) = assertTrue("$url should be allowed: ${p.check(url)}", p.check(url) is EndpointPolicy.Verdict.Allowed)

    @Test fun `public https hosts pass and the base url is normalised`() {
        val v = strict.check("https://API.openai.com/v1/") as EndpointPolicy.Verdict.Allowed
        assertEquals("https://api.openai.com/v1", v.baseUrl)
        allowed(strict, "https://openrouter.ai:443/api/v1")
    }

    @Test fun `cleartext http to a public host is blocked`() {
        blocked(strict, "http://api.openai.com/v1")
        blocked(strict, "http://localhost.evil.com")
        allowed(strict, "https://localhost.evil.com")
    }

    @Test fun `loopback and private ranges are blocked by default`() {
        listOf(
            "http://127.0.0.1:11434", "https://127.0.0.1", "http://localhost:11434", "https://localhost", "http://localhost.",
            "http://[::1]:8080", "https://10.1.2.3", "https://172.16.0.1", "https://172.31.255.255", "https://192.168.1.10",
            "https://100.64.0.1", "https://0.0.0.0", "http://printer", "https://nas.local", "https://svc.internal",
        ).forEach { blocked(strict, it) }
        allowed(strict, "https://172.32.0.1")
        allowed(strict, "https://8.8.8.8")
    }

    @Test fun `link local and metadata addresses are blocked even when local access is allowed`() {
        val open = EndpointPolicy(allowLoopback = true, allowPrivateLan = true)
        listOf(
            "http://169.254.169.254/latest/meta-data", "https://169.254.1.1", "http://[fe80::1]", "http://[fd00:ec2::254]",
            "http://100.100.100.200", "https://0.0.0.0", "https://224.0.0.1", "https://[::]", "https://[ff02::1]",
        ).forEach { blocked(open, it) }
    }

    @Test fun `obfuscated loopback spellings are caught`() {
        listOf("http://2130706433", "http://0x7f.0.0.1", "http://0177.0.0.1", "http://127.1", "http://[::ffff:127.0.0.1]", "http://[::ffff:7f00:1]", "http://[0:0:0:0:0:0:0:1]")
            .forEach { blocked(strict, it) }
        listOf("http://2130706433", "http://0x7f.0.0.1", "http://127.1").forEach { allowed(EndpointPolicy(allowLoopback = true), it) }
        blocked(strict, "https://[::ffff:169.254.169.254]")
        blocked(EndpointPolicy(allowLoopback = true, allowPrivateLan = true), "https://[::ffff:169.254.169.254]")
    }

    @Test fun `explicit local allowance permits cleartext for ollama only`() {
        val local = EndpointPolicy(allowLoopback = true)
        allowed(local, "http://localhost:11434/v1")
        allowed(local, "http://127.0.0.1:11434")
        allowed(local, "http://[::1]:11434")
        blocked(local, "http://10.0.2.2:11434")
        blocked(local, "http://api.openai.com/v1")
        val lan = EndpointPolicy(allowPrivateLan = true)
        allowed(lan, "http://10.0.2.2:11434")
        allowed(lan, "http://192.168.1.50:11434")
        blocked(lan, "http://localhost:11434")
        assertTrue((local.check("http://localhost:11434") as EndpointPolicy.Verdict.Allowed).isLocal)
    }

    @Test fun `credentials, query, fragment and odd syntax are refused`() {
        listOf(
            "https://user:pw@api.openai.com", "https://api.openai.com@evil.com", "https://api.openai.com/v1?key=abc", "https://api.openai.com/#x",
            "ftp://api.openai.com", "api.openai.com", "", "   ", "https://", "https://api.openai.com:99999", "https://api.openai.com:abc",
            "https://api openai.com", "https://api.openai.com\\@evil.com",
        ).forEach { blocked(strict, it) }
    }

    @Test fun `a resolver catches names that point at private addresses`() {
        val rebinding = EndpointPolicy(resolver = HostResolver { listOf("10.0.0.5") })
        blocked(rebinding, "https://innocent.example.com")
        val meta = EndpointPolicy(allowPrivateLan = true, resolver = HostResolver { listOf("169.254.169.254") })
        blocked(meta, "https://innocent.example.com")
        allowed(EndpointPolicy(resolver = HostResolver { listOf("93.184.216.34") }), "https://example.com")
    }

    @Test fun `require throws a policy error`() {
        val e = runCatching { strict.require("http://169.254.169.254") }.exceptionOrNull() as ModelException
        assertEquals(ModelErrorKind.Policy, e.kind)
    }
}
