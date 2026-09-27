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
 * ## Failure attribution, and its capability boundary
 *
 * [ProxySelector.connectFailed] reports only `(uri, socketAddress, exception)`. It carries **no**
 * connection identity, no route identity and no timing, so a failure cannot be tied to a particular
 * past [select] call. Attribution is therefore made by **provenance of the address**: the selector
 * remembers which addresses it handed out itself and which ones it observed from the platform
 * selector, and reports a failure onward only when the address is known to have come from the
 * platform.
 *
 * The current proxy state is deliberately **not** consulted. A connection attempted through a
 * previous endpoint can fail after the endpoint was switched, after the proxy became
 * [ProxyEndpointState.Unavailable], or after it was turned off, and such a failure belongs to this
 * selector no matter what the state is now.
 *
 * Consequences of the boundary, all erring towards *not* blaming the platform selector:
 *
 * - a failure with no address at all is not reported onward;
 * - a failure for an address never observed from either selector is not reported onward;
 * - provenance is remembered in bounded sets (see [CONNECT_FAILURE_RECORD_CAPACITY]); if an address
 *   is ever evicted, its failures stop being reported onward rather than being misattributed.
 *
 * Addresses never reach this stage for direct connections: OkHttp skips `connectFailed` entirely
 * when the failed route's proxy is of type `DIRECT`.
 *
 * ## Wiring status
 *
 * This class is **not installed** into [eu.kanade.tachiyomi.network.NetworkHelper] yet. It is
 * introduced together with its test, which pins down the behaviour above, so that a later phase can
 * install it without changing default network behaviour.
 */
class NetworkProxySelector internal constructor(
    private val source: ProxyEndpointSource,
    private val fallback: ProxySelector,
) : ProxySelector() {

    /** Creates a selector that keeps the platform default behaviour whenever [source] is disabled. */
    constructor(source: ProxyEndpointSource) : this(source, systemProxySelector())

    private val addressesWeSupplied = BoundedAddressSet(CONNECT_FAILURE_RECORD_CAPACITY)

    private val addressesFromPlatform = BoundedAddressSet(CONNECT_FAILURE_RECORD_CAPACITY)

    override fun select(uri: URI?): List<Proxy> {
        requireNotNull(uri) { "uri must not be null" }

        return when (val state = source.state()) {
            ProxyEndpointState.Disabled -> {
                // Delegate verbatim. A `null` result is normalised the same way OkHttp normalises
                // it, so a misbehaving delegate cannot turn into an unintended direct connection.
                val proxies = fallback.select(uri) ?: DirectProxySelector.select(uri)
                proxies.forEach { proxy ->
                    if (proxy.type() != Proxy.Type.DIRECT) {
                        proxy.address()?.let(addressesFromPlatform::add)
                    }
                }
                proxies
            }

            is ProxyEndpointState.Available -> {
                val proxy = state.endpoint.toProxy()
                addressesWeSupplied.add(proxy.address())
                listOf(proxy)
            }

            ProxyEndpointState.Unavailable -> throw ProxyUnavailableException()
        }
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        // No address means no provenance, so there is no evidence that the platform selector is
        // responsible. Conservative: withhold it.
        if (sa == null) return

        // The connection went through an endpoint this selector handed out, whatever the current
        // state is by now. The platform selector never chose it, so it must not be told about it.
        if (addressesWeSupplied.contains(sa)) return

        if (addressesFromPlatform.contains(sa)) {
            fallback.connectFailed(uri, sa, ioe)
        }
        // Otherwise the address is of unknown provenance. "Not ours" is not evidence that it is the
        // platform's, so it is withheld rather than blamed on the platform selector.
    }
}

/**
 * Remembers the last [capacity] distinct addresses added to it, forgetting the oldest first.
 *
 * Both [add] and [contains] are safe to call from the threads OkHttp uses for route selection and
 * for connect failures, which are not the same thread.
 */
private class BoundedAddressSet(private val capacity: Int) {

    private val members = HashSet<SocketAddress>()

    private val insertionOrder = ArrayDeque<SocketAddress>()

    @Synchronized
    fun add(address: SocketAddress) {
        if (!members.add(address)) return
        insertionOrder.addLast(address)
        if (insertionOrder.size > capacity) {
            members.remove(insertionOrder.removeFirst())
        }
    }

    @Synchronized
    fun contains(address: SocketAddress): Boolean = address in members
}

/**
 * How many addresses of each provenance are remembered for failure attribution.
 *
 * The realistic population is one endpoint plus a handful of platform proxies, so the cap exists
 * only to bound memory, not to model a connection pool.
 */
private const val CONNECT_FAILURE_RECORD_CAPACITY = 64

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
