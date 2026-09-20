package eu.kanade.tachiyomi.network.proxy

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Proxy

class ProxyEndpointTest {

    @Test
    fun `maps an HTTP endpoint to an HTTP proxy`() {
        val proxy = ProxyEndpoint(ProxyEndpoint.Type.HTTP, "127.0.0.1", 8080).toProxy()

        assertEquals(Proxy.Type.HTTP, proxy.type())
    }

    @Test
    fun `maps a SOCKS5 endpoint to a SOCKS proxy`() {
        val proxy = ProxyEndpoint(ProxyEndpoint.Type.SOCKS5, "127.0.0.1", 1080).toProxy()

        assertEquals(Proxy.Type.SOCKS, proxy.type())
    }

    @Test
    fun `keeps the proxy address unresolved so no lookup happens up front`() {
        val address = ProxyEndpoint(ProxyEndpoint.Type.HTTP, "proxy.example.com", 8080)
            .toProxy()
            .address() as InetSocketAddress

        assertTrue(address.isUnresolved)
        assertEquals("proxy.example.com", address.hostString)
        assertEquals(8080, address.port)
    }

    @Test
    fun `rejects a blank host`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProxyEndpoint(ProxyEndpoint.Type.HTTP, "   ", 8080)
        }
    }

    @Test
    fun `rejects a port outside the valid range`() {
        assertThrows(IllegalArgumentException::class.java) {
            ProxyEndpoint(ProxyEndpoint.Type.HTTP, "127.0.0.1", 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProxyEndpoint(ProxyEndpoint.Type.HTTP, "127.0.0.1", 65536)
        }
    }
}
