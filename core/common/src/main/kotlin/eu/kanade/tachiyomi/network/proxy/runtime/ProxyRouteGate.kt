package eu.kanade.tachiyomi.network.proxy.runtime

import eu.kanade.tachiyomi.network.proxy.ProxyEndpointState
import java.util.concurrent.atomic.AtomicReference

/**
 * The one place the HTTP layer asks what to do about proxying.
 *
 * Process-scoped and **synchronously** initialised: the constructor reads the persisted flag before
 * it returns, so from the first HTTP request onwards the projection is already correct. This is what
 * breaks the construction-order dependency - a selector built on this gate never needs the runtime to
 * exist, and with `enabled=true` persisted, the very first request fails closed even if the core has
 * not been started yet.
 *
 * The initial value is decided once, here:
 *
 * * persisted `false` - proxy off; nothing is served and nothing is expected → `Disabled`
 * * persisted `true` - the user wants the proxy, but nothing is serving yet → `Unavailable`
 *
 * Never "default to disabled and correct it later": the window between those two moments is a
 * direct-connection leak with the user's intent already recorded.
 *
 * The runtime is the only writer. The gate knows nothing about the runtime, which is what keeps the
 * dependency chain a chain instead of a cycle.
 */
class ProxyRouteGate(private val store: ProxyEnabledStore) {

    private val current = AtomicReference(initialState())

    /** The state the HTTP layer must act on. Deliberately not `suspend`: the ask is on the hot path. */
    fun state(): ProxyEndpointState = current.get()

    /** Publishes a new state. The runtime calls this; nothing else. */
    internal fun set(state: ProxyEndpointState) {
        current.set(state)
    }

    private fun initialState(): ProxyEndpointState =
        if (store.enabledSync()) ProxyEndpointState.Unavailable else ProxyEndpointState.Disabled
}
