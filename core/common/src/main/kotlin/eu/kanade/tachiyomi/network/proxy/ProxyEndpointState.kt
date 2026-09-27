package eu.kanade.tachiyomi.network.proxy

/**
 * What the HTTP layer should do about proxying, right now.
 *
 * The three cases are distinct on purpose. "No proxy is active" is an ordinary state in which the
 * platform's own behaviour must be preserved, whereas "a proxy is active but cannot be served" is a
 * failure that has to surface: treating it as the former would send traffic through the platform
 * selector or straight out, i.e. outside the tunnel that was asked for.
 *
 * Produced by [eu.kanade.tachiyomi.network.proxy.runtime.ProxyRouteGate], whose only writer is the
 * runtime; nothing else may publish a state.
 */
sealed interface ProxyEndpointState {

    /** No proxy is active. The platform's own proxy selection applies, unchanged. */
    data object Disabled : ProxyEndpointState

    /** A proxy endpoint is ready to be used. */
    data class Available(val endpoint: ProxyEndpoint) : ProxyEndpointState

    /**
     * A proxy is active but no endpoint can be served.
     *
     * Whatever reads this must expect traffic to fail rather than to leave untunnelled. Falling back
     * to the platform selector here is the direct-connection leak the whole gate exists to prevent.
     */
    data object Unavailable : ProxyEndpointState
}
