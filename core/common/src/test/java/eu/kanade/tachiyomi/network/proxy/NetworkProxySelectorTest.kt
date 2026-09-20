package eu.kanade.tachiyomi.network.proxy

import org.junit.jupiter.api.Assertions.assertEquals
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
 * Pins down the property that makes installing [NetworkProxySelector] safe: with no endpoint
 * configured it must be indistinguishable from not installing a selector at all.
 */
class NetworkProxySelectorTest {

    private val requestUri = URI("https://example.com/path")

    private val systemProxy = Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("system-proxy", 3128))

    @Test
    fun `uses the configured endpoint when the source has one`() {
        val fallback = RecordingProxySelector()
        val selector = NetworkProxySelector(
            { ProxyEndpoint(ProxyEndpoint.Type.SOCKS5, "127.0.0.1", 1080) },
            fallback,
        )

        val proxies = selector.select(requestUri)

        assertEquals(1, proxies.size)
        assertEquals(Proxy.Type.SOCKS, proxies.single().type())
        assertTrue(fallback.selectedUris.isEmpty(), "the fallback must not be consulted")
    }

    @Test
    fun `delegates the selection to the fallback selector when no endpoint is configured`() {
        val fallback = RecordingProxySelector(selected = listOf(systemProxy))
        val selector = NetworkProxySelector({ null }, fallback)

        val proxies = selector.select(requestUri)

        assertEquals(listOf(systemProxy), proxies)
        assertEquals(listOf(requestUri), fallback.selectedUris)
    }

    @Test
    fun `does not substitute a direct connection for the system selector`() {
        // Regression guard: returning Proxy.NO_PROXY here would silently drop a system proxy the
        // app honoured before any proxy feature existed.
        val selector = NetworkProxySelector({ null }, RecordingProxySelector(selected = listOf(systemProxy)))

        val proxies = selector.select(requestUri)

        assertTrue(proxies.none { it == Proxy.NO_PROXY }, "expected the system proxy, got $proxies")
    }

    @Test
    fun `passes a direct-only fallback result through unchanged`() {
        val selector = NetworkProxySelector({ null }, RecordingProxySelector(selected = listOf(Proxy.NO_PROXY)))

        assertEquals(listOf(Proxy.NO_PROXY), selector.select(requestUri))
    }

    @Test
    fun `forwards connection failures to the fallback selector`() {
        val fallback = RecordingProxySelector()
        val selector = NetworkProxySelector(
            { ProxyEndpoint(ProxyEndpoint.Type.SOCKS5, "127.0.0.1", 1080) },
            fallback,
        )

        selector.connectFailed(
            requestUri,
            InetSocketAddress.createUnresolved("127.0.0.1", 1080),
            IOException("connection refused"),
        )

        assertEquals(listOf(requestUri), fallback.failedUris)
    }

    @Test
    fun `rejects a null uri the same way OkHttp's own fallback does`() {
        val selector = NetworkProxySelector({ null }, RecordingProxySelector())

        assertThrows(IllegalArgumentException::class.java) { selector.select(null) }
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
