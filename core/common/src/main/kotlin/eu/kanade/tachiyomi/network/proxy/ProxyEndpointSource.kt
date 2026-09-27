package eu.kanade.tachiyomi.network.proxy

/**
 * Reports what outgoing HTTP traffic should be routed through.
 *
 * This is the only seam between the HTTP stack and whatever produces an endpoint later. It stays
 * minimal on purpose:
 *
 * - no start/stop and no state machine, because the lifecycle of a protocol core is not settled yet;
 * - no preferences, because a user-facing switch must not exist before the feature behind it does;
 * - no core-specific types, so an in-process tunnel core, a manually configured proxy, and a test
 *   double are all implementable without changing this interface.
 *
 * [ProxyEndpointState.Disabled] and [ProxyEndpointState.Unavailable] are **not** interchangeable.
 * The first means "there is nothing to route through, keep the platform's behaviour". The second
 * means "routing was asked for and cannot be provided", which must not silently degrade to the
 * platform selector or to a direct connection. This interface only distinguishes the two; the
 * lifecycle that produces them is deliberately left unmodelled for now.
 *
 * It is intentionally **not** `suspend`. [java.net.ProxySelector.select] is called synchronously by
 * OkHttp, so nothing at this boundary can wait for asynchronous start-up: an implementation that
 * needs to start something must have started it before traffic needs it, and answer from
 * already-known state.
 */
fun interface ProxyEndpointSource {
    fun state(): ProxyEndpointState
}
