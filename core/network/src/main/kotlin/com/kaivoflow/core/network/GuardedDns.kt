package com.kaivoflow.core.network

import okhttp3.Dns
import java.net.InetAddress

/**
 * OkHttp name lookup that refuses answers the network rules do not allow, at connect time. This closes the gap
 * between the URL check and the connection (DNS rebinding) and also covers hosts written in odd numeric forms,
 * which the resolver turns into plain addresses. The check runs on every connection, so redirects are covered.
 */
class GuardedDns(
    private val allowList: HostAllowList,
    private val delegate: Dns = Dns.SYSTEM,
) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = delegate.lookup(hostname)
        for (a in addresses) {
            when (val v = NetworkGuard.checkAddress(hostname, a, allowList)) {
                is GuardVerdict.Blocked -> throw BlockedAddressException(v.reason)
                is GuardVerdict.Allowed -> Unit
            }
        }
        return addresses
    }
}
