package eu.kanade.tachiyomi.network.proxy

import java.net.InetSocketAddress
import java.net.Proxy

/**
 * The transport-level endpoint the proxy serves, when it can serve one.
 *
 * The MVP has exactly one shape: a SOCKS listener on the loopback interface of this process. There
 * is no `Type` enumeration on purpose - an HTTP or a non-loopback variant would be a generalisation
 * nothing in this phase exercises, and an endpoint type that was never run is a direct-connection
 * path waiting to be discovered. Widening it means widening what the runtime verifies, not adding a
 * constructor here.
 *
 * Credentials are intentionally not modelled: the listener is an unauthenticated socket on
 * `127.0.0.1` that only this process can reach.
 */
data class ProxyEndpoint private constructor(
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
     * `java.net.ProxySelector` from blocking on a resolution of that host merely to describe the
     * proxy. The proxy host may still be resolved later, when a connection to it is attempted.
     *
     * This says nothing about how the **target** host is resolved - that is a separate concern
     * decided by OkHttp's route selection, not by this type. In particular, an unresolved proxy
     * address is not what gives a SOCKS route remote DNS for the target, and nothing here
     * constitutes a DNS-leak guarantee.
     */
    fun toProxy(): Proxy = Proxy(
        // `java.net.Proxy` only distinguishes HTTP from SOCKS; SOCKS4/SOCKS5 negotiation is left
        // to the JDK.
        Proxy.Type.SOCKS,
        InetSocketAddress.createUnresolved(host, port),
    )

    companion object {

        /** The only host an endpoint can have: this process' own loopback interface. */
        const val LOOPBACK_HOST = "127.0.0.1"

        private const val MAX_PORT = 65535

        /** The endpoint for a core listening on [port], or throws if the port is out of range. */
        fun loopbackSocks(port: Int): ProxyEndpoint = ProxyEndpoint(LOOPBACK_HOST, port)
    }
}
