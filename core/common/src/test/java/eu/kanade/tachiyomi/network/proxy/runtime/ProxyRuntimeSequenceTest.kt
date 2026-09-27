package eu.kanade.tachiyomi.network.proxy.runtime

import eu.kanade.tachiyomi.network.proxy.ProxyEndpoint
import eu.kanade.tachiyomi.network.proxy.ProxyEndpointState
import eu.kanade.tachiyomi.network.proxy.XrayErrorCategory
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * T-07 (what happens in what order), T-08 (the bounded port loop) and T-36 (crash points).
 *
 * The ordering evidence is the snapshot each fake takes of the machine at the moment it is called: an
 * eviction that reports the gate as still `Unavailable` is an eviction that happened before anything
 * was published as available, which is the property the whole sequence exists for.
 */
class ProxyRuntimeSequenceTest {

    // ------------------------------------------------- T-07 order inside a transition

    @Test
    fun `every transition evicts while the gate is still blocked`() = runBlocking {
        val f = RuntimeFixture()
        f.wireSnapshots()

        f.runtime.enable(runtimeNodeTemplate("a"))
        f.runtime.switchNode(runtimeNodeTemplate("b"))
        f.runtime.disable()

        val observed = (f.evictor.events + f.adapter.events).filter { it.contains("while ") }
        observed.isNotEmpty() shouldBe true
        observed.forEach { it shouldContain "gate=Unavailable" }
    }

    @Test
    fun `a transition clears the content plane before it touches the core`() = runBlocking {
        val f = RuntimeFixture()
        f.wireSnapshots()
        f.runtime.enable(runtimeNodeTemplate("a"))

        // One eviction on the way in, before the first freePort: the direct keep-alives built while
        // the proxy was off cannot survive into the enabled state.
        f.evictor.events.count { it == "evict" } shouldBe 1
        f.adapter.events.indexOfFirst { it == "evict" || it.startsWith("  while evicting") } shouldBe -1
        f.adapter.events.count { it == "freePort" } shouldBe 1
    }

    @Test
    fun `the enable order is gate, intent, content plane, then the core`() = runBlocking {
        val f = RuntimeFixture()
        f.wireSnapshots()
        f.runtime.enable(runtimeNodeTemplate())

        f.timeline shouldBe listOf(
            "store.persist",
            "evictor.evict",
            "adapter.freePort",
            "adapter.start",
        )
        // And the intent was recorded after the gate was blocked: there is no window in which the
        // flag says on and the HTTP layer still says disabled.
        f.store.persistSnapshots.single() shouldContain "gate=Unavailable"
    }

    @Test
    fun `the disable order is intent first, then the tunnel, then the core`() = runBlocking {
        val f = RuntimeFixture()
        f.wireSnapshots()
        f.runtime.enable(runtimeNodeTemplate("a"))
        f.timeline.clear()

        f.runtime.disable()

        f.timeline shouldBe listOf(
            "store.persist",
            "evictor.evict",
            "adapter.stop",
            "evictor.evict",
        )
        // Opposite direction, on purpose: the off is on disk while the tunnel is still up, so a crash
        // here cannot revive what the user just turned off.
        f.store.persistSnapshots.last() shouldContain "gate=Available"
        f.gate.state() shouldBe ProxyEndpointState.Disabled
    }

    @Test
    fun `the switch order is block, clear, stop, start, clear again`() = runBlocking {
        val f = RuntimeFixture()
        f.runtime.enable(runtimeNodeTemplate("a"))
        f.timeline.clear()

        f.runtime.switchNode(runtimeNodeTemplate("b"))

        // The second eviction is not a duplicate: a new port can land on the one just released, and a
        // same-host pooled connection from the old node would otherwise be reused.
        f.timeline shouldBe listOf(
            "evictor.evict",
            "adapter.stop",
            "adapter.freePort",
            "adapter.start",
            "evictor.evict",
        )
        f.store.persistCalls shouldBe 1
    }

    // ------------------------------------------------------- T-08 the port loop

    @Test
    fun `a port in use is retried on a freshly chosen port with a rebuilt config`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.adapter.freePortResults.addLast(20000)
        f.adapter.freePortResults.addLast(20001)
        f.adapter.startErrors.addLast(xrayException(XrayErrorCategory.LocalPortInUse))

        val result = f.runtime.enable(runtimeNodeTemplate())

        result shouldBe RuntimeState.Ready(20001)
        f.adapter.events.count { it == "freePort" } shouldBe 2
        f.adapter.events.count { it == "start" } shouldBe 2
        f.adapter.startedConfigs[0] shouldContain "\"port\":20000"
        f.adapter.startedConfigs[1] shouldContain "\"port\":20001"
        f.probe.ports shouldBe listOf(20001)
    }

    @Test
    fun `the port loop is bounded and exhaustion is never a direct fallback`(): Unit = runBlocking {
        val f = RuntimeFixture()
        repeat(3) { f.adapter.startErrors.addLast(xrayException(XrayErrorCategory.LocalPortInUse)) }

        val result = f.runtime.enable(runtimeNodeTemplate())

        result shouldBe RuntimeState.Failed(ProxyFailureReason.PortUnavailable)
        f.adapter.events.count { it == "freePort" } shouldBe 3
        f.adapter.events.count { it == "start" } shouldBe 3
        f.gate.state() shouldBe ProxyEndpointState.Unavailable
        f.store.enabledSync() shouldBe true
        f.runtime.state.value::class.simpleName shouldBe "Failed"
    }

    @Test
    fun `a core that is already running is stopped once and the start retried`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.adapter.startErrors.addLast(xrayException(XrayErrorCategory.AlreadyRunning))

        val result = f.runtime.enable(runtimeNodeTemplate())

        result shouldBe RuntimeState.Ready(10809)
        f.adapter.stopCount shouldBe 1
        f.adapter.events.count { it == "freePort" } shouldBe 2
    }

    @Test
    fun `a core that will not stop is not retried forever`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.adapter.startErrors.addLast(xrayException(XrayErrorCategory.AlreadyRunning))
        f.adapter.startErrors.addLast(xrayException(XrayErrorCategory.AlreadyRunning))

        val result = f.runtime.enable(runtimeNodeTemplate())

        result shouldBe RuntimeState.Failed(ProxyFailureReason.NodeRejected)
        f.adapter.stopCount shouldBe 1
        f.adapter.events.count { it == "freePort" } shouldBe 2
    }

    @Test
    fun `a switch also bounds its port loop`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.runtime.enable(runtimeNodeTemplate("a"))
        repeat(3) { f.adapter.startErrors.addLast(xrayException(XrayErrorCategory.LocalPortInUse)) }

        val result = f.runtime.switchNode(runtimeNodeTemplate("b"))

        result shouldBe RuntimeState.Failed(ProxyFailureReason.PortUnavailable)
        f.adapter.events.count { it == "freePort" } shouldBe 4
        f.gate.state() shouldBe ProxyEndpointState.Unavailable
        f.store.enabledSync() shouldBe true
    }

    // ------------------------------------------------------ T-36 crash points

    @Test
    fun `enable crash before the intent is recorded starts cold as disabled`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.store.failPersist = true

        shouldThrow<ProxyStatePersistException> { f.runtime.enable(runtimeNodeTemplate()) }

        f.runtime.state.value shouldBe RuntimeState.Failed(ProxyFailureReason.PersistFailed)
        f.store.enabledSync() shouldBe false
        ProxyRouteGate(f.store).state() shouldBe ProxyEndpointState.Disabled
    }

    @Test
    fun `enable crash while evicting starts cold as unavailable`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.evictor.evictError = RuntimeException("process died during E3")

        f.runtime.enable(runtimeNodeTemplate()) shouldBe
            RuntimeState.Failed(ProxyFailureReason.LocalFailed)

        f.store.enabledSync() shouldBe true
        ProxyRouteGate(f.store).state() shouldBe ProxyEndpointState.Unavailable
    }

    @Test
    fun `enable crash after the intent is recorded starts cold as unavailable`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.adapter.startRuntimeError = RuntimeException("process died during E4")

        f.runtime.enable(runtimeNodeTemplate()) shouldBe
            RuntimeState.Failed(ProxyFailureReason.LocalFailed)

        f.store.enabledSync() shouldBe true
        ProxyRouteGate(f.store).state() shouldBe ProxyEndpointState.Unavailable
    }

    @Test
    fun `enable crash after the core came up still starts cold as unavailable`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.probe.error = RuntimeException("process died during E5")

        f.runtime.enable(runtimeNodeTemplate()) shouldBe
            RuntimeState.Failed(ProxyFailureReason.LocalFailed)

        f.store.enabledSync() shouldBe true
        ProxyRouteGate(f.store).state() shouldBe ProxyEndpointState.Unavailable
    }

    @Test
    fun `disable crash while recording the intent changes nothing`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.runtime.enable(runtimeNodeTemplate())
        f.store.failPersist = true

        shouldThrow<ProxyStatePersistException> { f.runtime.disable() }

        // Nothing was applied: the machine is still Ready and the tunnel still works. Reporting the
        // failure is the honest outcome; pretending the proxy stopped is not.
        f.runtime.state.value shouldBe RuntimeState.Ready(10808)
        f.gate.state() shouldBe ProxyEndpointState.Available(ProxyEndpoint.loopbackSocks(10808))
        f.store.enabledSync() shouldBe true
        f.adapter.stopCount shouldBe 0
    }

    @Test
    fun `disable crash while evicting still ends disabled`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.runtime.enable(runtimeNodeTemplate())
        f.evictor.evictError = RuntimeException("process died during D3")

        f.runtime.disable() shouldBe RuntimeState.Disabled

        f.store.enabledSync() shouldBe false
        f.gate.state() shouldBe ProxyEndpointState.Disabled
        ProxyRouteGate(f.store).state() shouldBe ProxyEndpointState.Disabled
    }

    @Test
    fun `disable crash while stopping the core still ends disabled`(): Unit = runBlocking {
        val f = RuntimeFixture()
        f.runtime.enable(runtimeNodeTemplate())
        // A stop the core refuses: the gate is Disabled either way, a lingering core is inert with no
        // selector routing to it, and the next start handles it.
        f.adapter.startErrors.clear()
        f.runtime.disable()

        f.store.enabledSync() shouldBe false
        f.gate.state() shouldBe ProxyEndpointState.Disabled
    }

    private infix fun String.shouldContain(needle: String) {
        if (!contains(needle)) throw AssertionError("expected '$this' to contain '$needle'")
    }
}
