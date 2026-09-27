package eu.kanade.tachiyomi.network.proxy.runtime

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * Tears down content-plane connections at the moments a transition requires it.
 *
 * Cancelling and evicting is not optional hygiene. An OkHttp pooled connection that carried a
 * tunnelled request stays alive after the core behind it stops, and a same-host request will happily
 * reuse it; the reverse also holds, so a direct keep-alive built while the proxy was off would
 * survive being enabled. Every transition therefore evicts **before** it publishes a new state.
 *
 * Content plane only. The subscription fetcher owns its own client and its own lifecycle and must
 * never be cancelled from here - the proxy being broken is exactly when a refresh has to keep
 * working, which is what makes the control plane's direct path a declared exception rather than a
 * fallback.
 */
interface ConnectionEvictor {

    /**
     * Cancels every in-flight call and then evicts every pooled connection.
     *
     * Cancel first: `evictAll()` only removes idle connections and cannot end a call that is already
     * writing, so a pool evicted without a cancel would keep serving a tunnel nobody can see.
     * In-flight requests are allowed to fail - that is the accepted MVP behaviour, and the UI says so.
     */
    fun cancelAndEvict()
}

/**
 * The implementation over the one OkHttpClient the app owns.
 *
 * Taking the client rather than its parts is what makes the coverage structural: derived clients are
 * built with `newBuilder()` and share this client's [Dispatcher] and [ConnectionPool], so cancelling
 * and evicting here reaches every one of them. The client identity audit asserts that this stays
 * true.
 */
class OkHttpConnectionEvictor(private val client: OkHttpClient) : ConnectionEvictor {

    override fun cancelAndEvict() {
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }
}
