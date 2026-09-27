package eu.kanade.tachiyomi.network.proxy.runtime

import eu.kanade.tachiyomi.network.proxy.ProxyEndpoint
import eu.kanade.tachiyomi.network.proxy.ProxyEndpointState
import eu.kanade.tachiyomi.network.proxy.XrayErrorCategory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

/**
 * T-06 and T-10: the transition table, the states it rejects, and the projection the HTTP layer sees.
 *
 * The machine runs against fakes, so nothing here touches a core; what is pinned is which state each
 * event produces, that every failure stays enabled and unserved, and that `Ready` is the only state
 * whose projection is `Available`.
 */
class ProxyRuntimeStateMachineTest {

    // ------------------------------------------------------------- T1/T2 enable

    @Test
    fun `enable from disabled reaches ready and publishes the loopback endpoint`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.gate.state() shouldBe ProxyEndpointState.Disabled

        f.runtime.enable(runtimeNodeTemplate())

        f.runtime.state.value shouldBe RuntimeState.Ready(10808)
        f.gate.state() shouldBe ProxyEndpointState.Available(ProxyEndpoint.loopbackSocks(10808))
        f.store.enabledSync() shouldBe true
    }

    // --------------------------------------------------- T3 a rejected node

    @Test
    fun `a node the core rejects fails and stays enabled and unserved`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.adapter.startErrors.addLast(xrayException(XrayErrorCategory.ConfigValidationFailed))

        val result = f.runtime.enable(runtimeNodeTemplate())

        result shouldBe RuntimeState.Failed(ProxyFailureReason.NodeRejected)
        f.gate.state() shouldBe ProxyEndpointState.Unavailable
        // The user asked for the proxy and it did not come up; that is not the user turning it off.
        f.store.enabledSync() shouldBe true
    }

    // ------------------------------------------------------------ T9/T10 disable

    @Test
    fun `disable from ready reaches disabled and records the intent first`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.runtime.enable(runtimeNodeTemplate())

        f.runtime.disable()

        f.runtime.state.value shouldBe RuntimeState.Disabled
        f.gate.state() shouldBe ProxyEndpointState.Disabled
        f.store.enabledSync() shouldBe false
    }

    @Test
    fun `disable from disabled is a no-op`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.runtime.disable() shouldBe RuntimeState.Disabled
        f.runtime.disable() shouldBe RuntimeState.Disabled
        f.store.persistCalls shouldBe 0
    }

    // ------------------------------------------------------- T11 the only way back

    @Test
    fun `disable is the only transition that ends in disabled`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.adapter.startErrors.addLast(xrayException(XrayErrorCategory.ConfigValidationFailed))
        f.runtime.enable(runtimeNodeTemplate())
        f.runtime.state.value shouldBe RuntimeState.Failed(ProxyFailureReason.NodeRejected)

        f.runtime.disable()

        f.runtime.state.value shouldBe RuntimeState.Disabled
        f.gate.state() shouldBe ProxyEndpointState.Disabled
        f.store.enabledSync() shouldBe false
    }

    // ------------------------------------------------------------- T12 retry

    @Test
    fun `retry from failed runs the same node again`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.adapter.startErrors.addLast(xrayException(XrayErrorCategory.ConfigValidationFailed))
        f.runtime.enable(runtimeNodeTemplate())

        val result = f.runtime.retry()

        // A retry is a fresh start, so it asks for a fresh port rather than reusing the one the failed
        // attempt took - which is also why it is not the same config twice.
        result shouldBe RuntimeState.Ready(10809)
        f.gate.state() shouldBe ProxyEndpointState.Available(ProxyEndpoint.loopbackSocks(10809))
        // Retry is only meaningful while the proxy is still meant to be on.
        f.store.enabledSync() shouldBe true
    }

    @Test
    fun `retry with nothing to retry is rejected`(): Unit = runBlocking {
        val f = RuntimeFixture()
        shouldThrow<IllegalProxyTransitionException> { f.runtime.retry() }
    }

    @Test
    fun `retry after the proxy was disabled is rejected even if a node was tried before`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.adapter.startErrors.addLast(xrayException(XrayErrorCategory.ConfigValidationFailed))
        f.runtime.enable(runtimeNodeTemplate())
        f.runtime.disable()
        f.store.enabled = false

        shouldThrow<IllegalProxyTransitionException> { f.runtime.retry() }
    }

    // --------------------------------------------------- T6/T8 switch nodes

    @Test
    fun `a switch moves to the new node without a rollback path`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.runtime.enable(runtimeNodeTemplate("tokyo-1"))

        val result = f.runtime.switchNode(runtimeNodeTemplate("osaka-1"))

        result shouldBe RuntimeState.Ready(10809)
        f.gate.state() shouldBe ProxyEndpointState.Available(ProxyEndpoint.loopbackSocks(10809))
        f.adapter.stopCount shouldBe 1
        // And the machine got there without ever having been disabled.
        f.store.enabledSync() shouldBe true
    }

    @Test
    fun `a switch whose node is rejected fails and stays enabled`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.runtime.enable(runtimeNodeTemplate("tokyo-1"))
        f.adapter.startErrors.addLast(xrayException(XrayErrorCategory.ConfigValidationFailed))

        val result = f.runtime.switchNode(runtimeNodeTemplate("osaka-1"))

        result shouldBe RuntimeState.Failed(ProxyFailureReason.NodeRejected)
        f.gate.state() shouldBe ProxyEndpointState.Unavailable
        f.store.enabledSync() shouldBe true
    }

    // ------------------------------------------------- T13 nothing survives a process

    @Test
    fun `a fresh machine over a persisted true starts failed and unserved`(): Unit = runBlocking {
        // No restore exists in this phase: a process that comes back with the flag still on has no
        // node to start with, and must not pretend otherwise or, worse, go direct.
        val f = RuntimeFixture(store = FakeStore(initial = true))
        f.runtime.state.value shouldBe RuntimeState.Failed(ProxyFailureReason.SelectedNodeUnavailable)
        f.gate.state() shouldBe ProxyEndpointState.Unavailable
    }

    // --------------------------------------------------- illegal transitions

    @Test
    fun `enable while the proxy is ready is rejected`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.runtime.enable(runtimeNodeTemplate())

        shouldThrow<IllegalProxyTransitionException> { f.runtime.enable(runtimeNodeTemplate("osaka-1")) }

        f.runtime.state.value shouldBe RuntimeState.Ready(10808)
        f.gate.state() shouldBe ProxyEndpointState.Available(ProxyEndpoint.loopbackSocks(10808))
        f.adapter.stopCount shouldBe 0
    }

    @Test
    fun `a switch from disabled is rejected`(): Unit = runBlocking {
        val f = RuntimeFixture()
        shouldThrow<IllegalProxyTransitionException> { f.runtime.switchNode(runtimeNodeTemplate()) }
        f.gate.state() shouldBe ProxyEndpointState.Disabled
        f.store.persistCalls shouldBe 0
    }

    // --------------------------------------------------------- T10 projection

    @Test
    fun `every non-ready state projects to unavailable`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.adapter.startErrors.addLast(xrayException(XrayErrorCategory.ConfigValidationFailed))

        // Failed
        f.runtime.enable(runtimeNodeTemplate())
        f.gate.state() shouldBe ProxyEndpointState.Unavailable

        // Disabled: the one state that is not Unavailable
        f.runtime.disable()
        f.gate.state() shouldBe ProxyEndpointState.Disabled
    }

    @Test
    fun `starting is served nothing while the core is coming up`() = runBlocking {
        val f = RuntimeFixture(store = FakeStore(initial = true))
        f.adapter.pauseOnStart = true

        val job = launch { f.runtime.enable(runtimeNodeTemplate()) }
        while (f.adapter.inFlightStarts.isEmpty()) yield()

        // Mid-transition, the machine is Starting and the HTTP layer is told nothing is served.
        f.runtime.state.value shouldBe RuntimeState.Starting
        f.gate.state() shouldBe ProxyEndpointState.Unavailable

        f.adapter.inFlightStarts.single().complete(Unit)
        job.join()

        f.runtime.state.value shouldBe RuntimeState.Ready(10808)
        f.gate.state() shouldBe ProxyEndpointState.Available(ProxyEndpoint.loopbackSocks(10808))
    }

    @Test
    fun `switching and stopping are both served nothing`() = runBlocking {
        val f = RuntimeFixture()
        f.wireSnapshots()
        f.runtime.enable(runtimeNodeTemplate("tokyo-1"))
        f.adapter.startErrors.clear()

        f.runtime.switchNode(runtimeNodeTemplate("osaka-1"))
        f.runtime.disable()

        // Everything the fakes observed mid-transition - evicting during a switch, evicting during a
        // disable, starting the new node - was while the HTTP layer was told Unavailable. `Ready` is
        // the only state that is ever published as available, and `Disabled` only after the tunnel is
        // actually gone.
        val observed = (f.evictor.events + f.adapter.events).filter { it.contains("while ") }
        observed.isNotEmpty() shouldBe true
        observed.forEach { it shouldContain "gate=Unavailable" }
        f.runtime.state.value shouldBe RuntimeState.Disabled
        f.gate.state() shouldBe ProxyEndpointState.Disabled
    }

    private infix fun String.shouldContain(needle: String) {
        if (!contains(needle)) throw AssertionError("expected '$this' to contain '$needle'")
    }
}
