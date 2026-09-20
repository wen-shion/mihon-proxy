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
 * Pins down two properties of [NetworkProxySelector]:
 *
 * - while the proxy is disabled it must be indistinguishable from not installing a selector at all;
 * - while a proxy is enabled but unavailable it must fail rather than fall back to the platform
 *   selector or to a direct connection.
 */
class NetworkProxySelectorTest {

    private val requestUri = URI("https://example.com/path")

    private val systemProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("system-proxy", 3128))

    private val localEndpoint = ProxyEndpoint(ProxyEndpoint.Type.SOCKS5, "127.0.0.1", 1080)

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
    fun `reports a failure to the fallback selector when the proxy is disabled`() {
        val fallback = RecordingProxySelector()
        val selector = NetworkProxySelector({ ProxyEndpointState.Disabled }, fallback)

        selector.connectFailed(requestUri, systemProxy.address(), IOException("connection refused"))

        assertEquals(listOf(requestUri), fallback.failedUris)
    }

    @Test
    fun `does not report its own endpoint's failure to the fallback selector`() {
        val fallback = RecordingProxySelector()
        val selector = NetworkProxySelector({ ProxyEndpointState.Available(localEndpoint) }, fallback)
        // A freshly built address: attribution must match by value, not by instance identity.
        val refusedByOurEndpoint = localEndpoint.toProxy().address()

        selector.connectFailed(requestUri, refusedByOurEndpoint, IOException("connection refused"))

        assertTrue(
            fallback.failedUris.isEmpty(),
            "the platform selector must not be blamed for an endpoint it never chose",
        )
    }

    @Test
    fun `reports a failure it cannot attribute to its own endpoint`() {
        val fallback = RecordingProxySelector()
        val selector = NetworkProxySelector({ ProxyEndpointState.Available(localEndpoint) }, fallback)
        val someOtherAddress = InetSocketAddress.createUnresolved("elsewhere", 9999)

        selector.connectFailed(requestUri, someOtherAddress, IOException("connection refused"))

        assertEquals(listOf(requestUri), fallback.failedUris)
    }

    @Test
    fun `reports a failure that carries no address`() {
        val fallback = RecordingProxySelector()
        val selector = NetworkProxySelector({ ProxyEndpointState.Available(localEndpoint) }, fallback)

        selector.connectFailed(requestUri, null, IOException("connection refused"))

        assertEquals(listOf(requestUri), fallback.failedUris)
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
