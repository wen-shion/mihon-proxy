package eu.kanade.tachiyomi.network.proxy

import java.net.InetSocketAddress
import java.net.Proxy

/**
 * A transport-level proxy endpoint.
 *
 * This type is deliberately free of any protocol-core concept: it carries only what the HTTP layer
 * needs in order to hand a request to a proxy. Whatever produces the endpoint (a user-supplied
 * proxy, or an in-process tunnel core) stays behind [ProxyEndpointSource], so the core remains
 * swappable and nothing here has to change when one is chosen.
 *
 * Credentials are intentionally not modelled. Adding them is a separate decision about secret
 * storage that this phase does not take.
 */
data class ProxyEndpoint(
    val type: Type,
    val host: String,
    val port: Int,
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..MAX_PORT) { "port must be in 1..$MAX_PORT, but was $port" }
    }

    /**
     * Converts this endpoint into the [Proxy] the HTTP client can route through.
     *
     * The address is left unresolved on purpose, but the reason is narrow: [Proxy.address] is the
     * address of the **proxy server**, and building it unresolved keeps a caller such as a
     * [java.net.ProxySelector] from blocking on a resolution of that host merely to describe the
     * proxy. The proxy host may still be resolved later, when a connection to it is attempted.
     *
     * This says nothing about how the **target** host is resolved — that is a separate concern
     * decided by OkHttp's route selection, not by this type. In particular, an unresolved proxy
     * address is not what gives a SOCKS route remote DNS for the target, and nothing here
     * constitutes a DNS-leak guarantee.
     */
    fun toProxy(): Proxy = Proxy(
        when (type) {
            Type.HTTP -> Proxy.Type.HTTP
            // `java.net.Proxy` only distinguishes HTTP from SOCKS; SOCKS4/SOCKS5 negotiation is
            // left to the JDK.
            Type.SOCKS5 -> Proxy.Type.SOCKS
        },
        InetSocketAddress.createUnresolved(host, port),
    )

    enum class Type {
        HTTP,
        SOCKS5,
    }

    companion object {
        private const val MAX_PORT = 65535
    }
}
