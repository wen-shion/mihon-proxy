package eu.kanade.tachiyomi.network.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * T-09, the part this phase owns: the endpoint the runtime can publish is a SOCKS listener on the
 * loopback interface, and nothing else.
 *
 * There is no `Type` enumeration to test the absence of - the factory takes a port and the host is
 * fixed, which is what makes "loopback only" true by construction rather than by convention.
 */
class ProxyEndpointTest {

    @Test
    fun `the endpoint is the loopback interface on the given port`() {
        val endpoint = ProxyEndpoint.loopbackSocks(10808)
        endpoint.host shouldBe "127.0.0.1"
        endpoint.port shouldBe 10808
    }

    @Test
    fun `two endpoints with the same port are the same endpoint`() {
        ProxyEndpoint.loopbackSocks(10808) shouldBe ProxyEndpoint.loopbackSocks(10808)
        ProxyEndpoint.loopbackSocks(10808).hashCode() shouldBe ProxyEndpoint.loopbackSocks(10808).hashCode()
    }

    @Test
    fun `a port outside the valid range is rejected`() {
        listOf(0, -1, 65536).forEach { port ->
            shouldThrow<IllegalArgumentException> { ProxyEndpoint.loopbackSocks(port) }
        }
    }

    @Test
    fun `toProxy is a socks proxy whose server address is unresolved`() {
        val proxy = ProxyEndpoint.loopbackSocks(10808).toProxy()

        proxy.type() shouldBe Proxy.Type.SOCKS
        val address = proxy.address() as InetSocketAddress
        address.hostString shouldBe "127.0.0.1"
        address.port shouldBe 10808
        // Unresolved on purpose, and narrow: this keeps a selector from resolving the *proxy
        // server's* name just to describe it. It says nothing about the target host's resolution and
        // is not a DNS-leak guarantee - a comment the endpoint carries, repeated here so the removal
        // of either half shows up as a diff rather than as silence.
        address.isUnresolved shouldBe true
    }
}
