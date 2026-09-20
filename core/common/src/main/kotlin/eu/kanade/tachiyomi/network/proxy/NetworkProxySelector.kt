package eu.kanade.tachiyomi.network.proxy

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Selects a proxy per request: the endpoint reported by [source] when one is available, otherwise
 * exactly what the platform's own selector would have returned, and a hard failure when a proxy is
 * active but cannot be served.
 *
 * ## Why the fallback must not be substituted
 *
 * OkHttp resolves the effective selector as
 * `builder.proxySelector ?: ProxySelector.getDefault() ?: NullProxySelector` (see
 * `OkHttpClient.proxySelector`). Installing a selector therefore *replaces* the system proxy lookup
 * for every request the client makes, including requests issued while no proxy feature is active.
 * Short-circuiting to [Proxy.NO_PROXY] would silently drop a system proxy the app previously
 * honoured, so this class delegates instead — but only while [ProxyEndpointState.Disabled] is
 * reported.
 *
 * [Proxy.NO_PROXY] is produced only when the platform exposes no default selector at all, which is
 * the same outcome OkHttp's internal `NullProxySelector` produces — see [DirectProxySelector].
 *
 * ## Unavailable is not Disabled
 *
 * [ProxyEndpointState.Unavailable] throws [ProxyUnavailableException] instead of falling back.
 * Traffic that was asked to go through a tunnel must fail rather than leave untunnelled.
 *
 * ## Failure attribution
 *
 * [connectFailed] does not forward blindly. A failure of an endpoint this selector handed out is
 * withheld from `fallback`, because the platform selector never chose that endpoint and would
 * otherwise record our failure against its own configuration. While the proxy is disabled the
 * notification is forwarded untouched, and so is anything that cannot be tied to our endpoint —
 * attribution is conservative, see [isOwnEndpointAddress].
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

    /** Creates a selector that keeps the platform default behaviour whenever [source] is disabled. */
    constructor(source: ProxyEndpointSource) : this(source, systemProxySelector())

    override fun select(uri: URI?): List<Proxy> {
        requireNotNull(uri) { "uri must not be null" }

        return when (val state = source.state()) {
            ProxyEndpointState.Disabled ->
                // Delegate verbatim. A `null` result is normalised the same way OkHttp normalises
                // it, so a misbehaving delegate cannot turn into an unintended direct connection.
                fallback.select(uri) ?: DirectProxySelector.select(uri)

            is ProxyEndpointState.Available -> listOf(state.endpoint.toProxy())

            ProxyEndpointState.Unavailable -> throw ProxyUnavailableException()
        }
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        if (isOwnEndpointAddress(sa)) {
            // The failure belongs to an endpoint this selector supplied. The platform selector did
            // not choose it, so reporting it there would attribute our failure to its configuration.
            return
        }
        fallback.connectFailed(uri, sa, ioe)
    }

    /**
     * Whether [address] is the address of the endpoint this selector is currently serving.
     *
     * Attribution is deliberately conservative: only a failure that can be tied to our own endpoint
     * is withheld. A failure reported for an address we cannot recognise — including a stale
     * endpoint address after a configuration change, or no address at all — is forwarded, because
     * we cannot prove it was ours.
     */
    private fun isOwnEndpointAddress(address: SocketAddress?): Boolean {
        if (address == null) return false
        val state = source.state()
        return state is ProxyEndpointState.Available && address == state.endpoint.toProxy().address()
    }
}

/**
 * Thrown when a proxy is active but no endpoint can be served.
 *
 * It is deliberately an [IllegalStateException] rather than an [IllegalArgumentException]: OkHttp's
 * `RouteSelector` catches the latter specifically and reads it as "no usable proxy", which would
 * turn an active-but-broken proxy into an untunnelled direct connection.
 */
class ProxyUnavailableException : IllegalStateException(
    "A proxy is enabled but no endpoint is available",
)

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
