package eu.kanade.tachiyomi.network.proxy.runtime

import eu.kanade.tachiyomi.network.proxy.ProxyEndpoint
import eu.kanade.tachiyomi.network.proxy.ProxyEndpointState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * T-38a: the gate is initialised synchronously from the persisted flag.
 *
 * This is the property that makes the construction-order dependency disappear. Whatever builds the
 * HTTP layer can build it on this gate before the runtime exists, and the projection is already
 * right: an `enabled` that was recorded on can never look like "proxy off", because the value that
 * says so is read in the constructor, not fetched later.
 */
class ProxyRouteGateTest {

    @Test
    fun `a persisted true starts as unavailable`() {
        ProxyRouteGate(FakeStore(initial = true)).state() shouldBe ProxyEndpointState.Unavailable
    }

    @Test
    fun `a persisted false starts as disabled`() {
        ProxyRouteGate(FakeStore(initial = false)).state() shouldBe ProxyEndpointState.Disabled
    }

    @Test
    fun `the initial state is held until something publishes`() {
        val gate = ProxyRouteGate(FakeStore(initial = true))
        gate.state() shouldBe ProxyEndpointState.Unavailable
        gate.state() shouldBe ProxyEndpointState.Unavailable
    }

    @Test
    fun `a published state is what state reports`() {
        val gate = ProxyRouteGate(FakeStore(initial = false))
        gate.set(ProxyEndpointState.Unavailable)
        gate.state() shouldBe ProxyEndpointState.Unavailable
        gate.set(ProxyEndpointState.Available(ProxyEndpoint.loopbackSocks(10808)))
        gate.state() shouldBe ProxyEndpointState.Available(ProxyEndpoint.loopbackSocks(10808))
        gate.set(ProxyEndpointState.Disabled)
        gate.state() shouldBe ProxyEndpointState.Disabled
    }

    @Test
    fun `the flag is read once, synchronously, at construction`() {
        val store = FakeStore(initial = true)
        val gate = ProxyRouteGate(store)
        // Flipping the store afterwards is what a process restart looks like from the outside; a
        // gate that already exists must not silently change what it tells the HTTP layer.
        store.enabled = false
        gate.state() shouldBe ProxyEndpointState.Unavailable
    }
}
