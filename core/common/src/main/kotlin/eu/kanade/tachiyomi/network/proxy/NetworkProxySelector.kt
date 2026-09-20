package eu.kanade.tachiyomi.network.proxy

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Selects a proxy per request: the endpoint reported by [source] when there is one, otherwise
 * exactly what the platform's own selector would have returned.
 *
 * ## Why the fallback must not be substituted
 *
 * OkHttp resolves the effective selector as
 * `builder.proxySelector ?: ProxySelector.getDefault() ?: NullProxySelector` (see
 * `OkHttpClient.proxySelector`). Installing a selector therefore *replaces* the system proxy lookup
 * for every request the client makes, including requests issued while no proxy feature is active.
 * Short-circuiting to [Proxy.NO_PROXY] would silently drop a system proxy the app previously
 * honoured, so this class delegates instead.
 *
 * [Proxy.NO_PROXY] is produced only when the platform exposes no default selector at all, which is
 * the same outcome OkHttp's internal `NullProxySelector` produces — see [DirectProxySelector].
 *
 * ## Wiring status
 *
 * This class is **not installed** into [eu.kanade.tachiyomi.network.NetworkHelper] yet. It is
 * introduced together with its test, which pins down the equivalence above, so that a later phase
 * can install it without changing default network behaviour.
 */
class NetworkProxySelector internal constructor(
    private val source: ProxyEndpointSource,
    private val fallback: ProxySelector,
) : ProxySelector() {

    /** Creates a selector that keeps the platform default behaviour whenever [source] is empty. */
    constructor(source: ProxyEndpointSource) : this(source, systemProxySelector())

    override fun select(uri: URI?): List<Proxy> {
        requireNotNull(uri) { "uri must not be null" }

        val endpoint = source.endpoint()
        if (endpoint != null) {
            return listOf(endpoint.toProxy())
        }

        // Delegate verbatim. A `null` result is normalised the same way OkHttp normalises it, so a
        // misbehaving delegate cannot turn into an unintended direct connection.
        return fallback.select(uri) ?: DirectProxySelector.select(uri)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        fallback.connectFailed(uri, sa, ioe)
    }
}

/**
 * The selector OkHttp falls back to when the builder does not override it.
 *
 * Exposed so tests can assert that installing [NetworkProxySelector] does not swap the platform
 * default for something else.
 */
internal fun systemProxySelector(): ProxySelector = ProxySelector.getDefault() ?: DirectProxySelector

/**
 * Stands in for the platform default selector when the platform has none, replicating OkHttp's
 * internal `NullProxySelector`.
 *
 * Returning [Proxy.NO_PROXY] from here is not a user-facing "connect directly" choice and must not
 * be used to represent one: this object is reached only when no proxy source exists at this layer,
 * which is precisely the case where OkHttp itself would return [Proxy.NO_PROXY].
 */
internal object DirectProxySelector : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        requireNotNull(uri) { "uri must not be null" }
        return listOf(Proxy.NO_PROXY)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // No proxy is involved, so there is nothing to report.
    }
}
