package eu.kanade.tachiyomi.network.proxy

/**
 * Supplies the proxy endpoint that outgoing HTTP traffic should use, or `null` when there is
 * nothing to contribute and the client must keep its own default behaviour.
 *
 * This is the only seam between the HTTP stack and whatever produces an endpoint later. It stays
 * minimal on purpose:
 *
 * - no start/stop or state machine, because the lifecycle of a protocol core is not settled yet;
 * - no preferences, because a user-facing switch must not exist before the feature behind it does;
 * - no core-specific types, so an in-process tunnel core, a manually configured proxy, and a test
 *   double are all implementable without changing this interface.
 *
 * It is intentionally **not** `suspend`. [java.net.ProxySelector.select] is called synchronously by
 * OkHttp, so nothing at this boundary can wait for asynchronous start-up. An implementation that
 * needs to start something must have started it before traffic needs it, and answer from
 * already-known state.
 *
 * Returning `null` means "not configured here". It never means "connect directly" — a direct
 * connection is whatever the platform's own selector would have chosen. See [NetworkProxySelector].
 */
fun interface ProxyEndpointSource {
    fun endpoint(): ProxyEndpoint?
}
