package eu.kanade.tachiyomi.network.proxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * Pins down three properties of [NetworkProxySelector]:
 *
 * - while the proxy is disabled it must be indistinguishable from not installing a selector at all;
 * - while a proxy is enabled but unavailable it must fail rather than fall back to the platform
 *   selector or to a direct connection;
 * - a connect failure is attributed by the **provenance of its address**, never by the current proxy
 *   state, so an endpoint's failure is not handed to the platform selector even when the endpoint
 *   has since been switched, become unavailable, or been turned off — and a failure whose
 *   provenance is unknown is withheld rather than blamed on the platform selector.
 */
class NetworkProxySelectorTest {

    private val requestUri = URI("https://example.com/path")

    private val systemProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("system-proxy", 3128))

    private val localEndpoint = ProxyEndpoint(ProxyEndpoint.Type.SOCKS5, "127.0.0.1", 1080)

    private val secondEndpoint = ProxyEndpoint(ProxyEndpoint.Type.SOCKS5, "127.0.0.1", 1081)

    @Test
    fun `uses the configured endpoint when the source has one`() {
        val fallback = RecordingProxySelector()
        val selector = NetworkProxySelector({ ProxyEndpointState.Available(localEndpoint) }, fallback)

        val proxies = selector.select(requestUri)

        assertEquals(1, proxies.size)
        assertEquals(Proxy.Type.SOCKS, proxies.single().type())
        assertTrue(fallback.selectedUris.isEmpty(), "the fallback must not be consulted")
    }

    @Test
    fun `delegates the selection to the fallback selector when the proxy is disabled`() {
        val fallback = RecordingProxySelector(selected = listOf(systemProxy))
        val selector = NetworkProxySelector({ ProxyEndpointState.Disabled }, fallback)

        val proxies = selector.select(requestUri)

        assertEquals(listOf(systemProxy), proxies)
        assertEquals(listOf(requestUri), fallback.selectedUris)
    }

    @Test
    fun `does not substitute a direct connection for the system selector`() {
        // Regression guard: returning Proxy.NO_PROXY here would silently drop a system proxy the
        // app honoured before any proxy feature existed.
        val selector = NetworkProxySelector(
            { ProxyEndpointState.Disabled },
            RecordingProxySelector(selected = listOf(systemProxy)),
        )

        val proxies = selector.select(requestUri)

        assertTrue(proxies.none { it == Proxy.NO_PROXY }, "expected the system proxy, got $proxies")
    }

    @Test
    fun `passes a direct-only fallback result through unchanged`() {
        val selector = NetworkProxySelector(
            { ProxyEndpointState.Disabled },
            RecordingProxySelector(selected = listOf(Proxy.NO_PROXY)),
        )

        assertEquals(listOf(Proxy.NO_PROXY), selector.select(requestUri))
    }

    @Test
    fun `throws instead of falling back when a proxy is enabled but unavailable`() {
        val fallback = RecordingProxySelector(selected = listOf(systemProxy))
        val selector = NetworkProxySelector({ ProxyEndpointState.Unavailable }, fallback)

        assertThrows(ProxyUnavailableException::class.java) { selector.select(requestUri) }
        assertTrue(fallback.selectedUris.isEmpty(), "the fallback must not be consulted")
    }

    @Test
    fun `never turns an unavailable proxy into a direct connection`() {
        // The failure mode being guarded: OkHttp's RouteSelector catches IllegalArgumentException
        // from select() and reads it as "no usable proxy", which would produce Proxy.NO_PROXY and
        // send the request untunnelled. Captured as Throwable so both checks below are meaningful.
        val selector = NetworkProxySelector(
            { ProxyEndpointState.Unavailable },
            RecordingProxySelector(selected = listOf(Proxy.NO_PROXY)),
        )

        val thrown = assertThrows(Throwable::class.java) { selector.select(requestUri) }

        assertTrue(thrown is ProxyUnavailableException, "expected ProxyUnavailableException, got $thrown")
        assertFalse(thrown is IllegalArgumentException, "an unavailable proxy must not look like 'no proxy'")
    }

    @Test
    fun `rejects a null uri the same way OkHttp's own fallback does`() {
        val selector = NetworkProxySelector({ ProxyEndpointState.Disabled }, RecordingProxySelector())

        assertThrows(IllegalArgumentException::class.java) { selector.select(null) }
    }

    @Test
    fun `reports a platform failure to the platform selector`() {
        val fallback = RecordingProxySelector(selected = listOf(systemProxy))
        val selector = NetworkProxySelector({ ProxyEndpointState.Disabled }, fallback)
        selector.select(requestUri)

        // A freshly built but equal address: provenance must match by value, not by identity.
        selector.connectFailed(
            requestUri,
            InetSocketAddress.createUnresolved("system-proxy", 3128),
            IOException("connection refused"),
        )

        assertEquals(listOf(requestUri), fallback.failedUris)
    }

    @Test
    fun `does not report its own endpoint's failure to the platform selector`() {
        val fallback = RecordingProxySelector()
        val selector = NetworkProxySelector({ ProxyEndpointState.Available(localEndpoint) }, fallback)
        selector.select(requestUri)

        selector.connectFailed(requestUri, localEndpoint.toProxy().address(), IOException("connection refused"))

        assertTrue(
            fallback.failedUris.isEmpty(),
            "the platform selector must not be blamed for an endpoint it never chose",
        )
    }

    @Test
    fun `does not report the previous endpoint after the endpoint switched`() {
        val fallback = RecordingProxySelector()
        var state: ProxyEndpointState = ProxyEndpointState.Available(localEndpoint)
        val selector = NetworkProxySelector({ state }, fallback)
        selector.select(requestUri)

        state = ProxyEndpointState.Available(secondEndpoint)
        selector.select(requestUri)

        selector.connectFailed(requestUri, localEndpoint.toProxy().address(), IOException("connection refused"))

        assertTrue(fallback.failedUris.isEmpty(), "a switched endpoint does not make the old failure the platform's")
    }

    @Test
    fun `does not report the previous endpoint after the proxy became unavailable`() {
        val fallback = RecordingProxySelector()
        var state: ProxyEndpointState = ProxyEndpointState.Available(localEndpoint)
        val selector = NetworkProxySelector({ state }, fallback)
        selector.select(requestUri)

        state = ProxyEndpointState.Unavailable

        selector.connectFailed(requestUri, localEndpoint.toProxy().address(), IOException("connection refused"))

        assertTrue(
            fallback.failedUris.isEmpty(),
            "becoming unavailable does not transfer our past failures to the platform selector",
        )
    }

    @Test
    fun `does not report the previous endpoint after the proxy was disabled`() {
        val fallback = RecordingProxySelector()
        var state: ProxyEndpointState = ProxyEndpointState.Available(localEndpoint)
        val selector = NetworkProxySelector({ state }, fallback)
        selector.select(requestUri)

        state = ProxyEndpointState.Disabled

        selector.connectFailed(requestUri, localEndpoint.toProxy().address(), IOException("connection refused"))

        assertTrue(
            fallback.failedUris.isEmpty(),
            "turning the proxy off does not transfer its past failures to the platform selector",
        )
    }

    @Test
    fun `does not report a failure of unknown provenance`() {
        val fallback = RecordingProxySelector()
        val selector = NetworkProxySelector({ ProxyEndpointState.Available(localEndpoint) }, fallback)
        selector.select(requestUri)

        selector.connectFailed(
            requestUri,
            InetSocketAddress.createUnresolved("never-observed", 9999),
            IOException("connection refused"),
        )

        assertTrue(fallback.failedUris.isEmpty(), "not being ours is not evidence of being the platform's")
    }

    @Test
    fun `does not report a failure that carries no address`() {
        val fallback = RecordingProxySelector()
        val selector = NetworkProxySelector({ ProxyEndpointState.Available(localEndpoint) }, fallback)
        selector.select(requestUri)

        selector.connectFailed(requestUri, null, IOException("connection refused"))

        assertTrue(fallback.failedUris.isEmpty(), "no address means no provenance to attribute")
    }

    @Test
    fun `the system selector helper does not replace the platform default`() {
        val platform = ProxySelector.getDefault()

        if (platform != null) {
            assertSame(platform, systemProxySelector())
        } else {
            assertSame(DirectProxySelector, systemProxySelector())
        }
    }

    private class RecordingProxySelector(
        private val selected: List<Proxy> = emptyList(),
    ) : ProxySelector() {

        val selectedUris = mutableListOf<URI>()
        val failedUris = mutableListOf<URI>()

        override fun select(uri: URI?): List<Proxy> {
            selectedUris += requireNotNull(uri)
            return selected
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            failedUris += requireNotNull(uri)
        }
    }
}
