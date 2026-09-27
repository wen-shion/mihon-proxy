package eu.kanade.tachiyomi.network.proxy.runtime

import eu.kanade.tachiyomi.network.proxy.ProxyEndpoint
import eu.kanade.tachiyomi.network.proxy.ProxyEndpointState
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * The machine runs one transition at a time, and a cancelled transition is not a failure.
 *
 * Everything here runs on a single test scheduler with a start that parks, so "the second transition
 * waited" is observed directly instead of being inferred from the outcome.
 */
class ProxyRuntimeConcurrencyTest {

    private fun pausedFixture(scheduler: TestCoroutineScheduler): RuntimeFixture =
        RuntimeFixture(adapter = FakeAdapter().apply { pauseOnStart = true }, io = StandardTestDispatcher(scheduler))

    @Test
    fun `enable and enable are serialised, and the second one is refused`() = runTest {
        val f = pausedFixture(testScheduler)
        var firstOutcome: RuntimeState? = null
        var secondError: Throwable? = null

        val first = launch { firstOutcome = f.runtime.enable(runtimeNodeTemplate("a")) }
        testScheduler.advanceUntilIdle()
        f.runtime.state.value shouldBe RuntimeState.Starting

        val second = launch {
            try {
                f.runtime.enable(runtimeNodeTemplate("b"))
            } catch (error: Throwable) {
                secondError = error
            }
        }
        testScheduler.advanceUntilIdle()

        // The first is still parked inside its start and the second is still waiting on the mutex:
        // no two core lifecycle operations ever run at once.
        f.adapter.startedConfigs.size shouldBe 1
        f.runtime.state.value shouldBe RuntimeState.Starting
        f.gate.state() shouldBe ProxyEndpointState.Unavailable

        f.adapter.inFlightStarts[0].complete(Unit)
        testScheduler.advanceUntilIdle()
        first.join()
        second.join()

        firstOutcome shouldBe RuntimeState.Ready(10808)
        (secondError is IllegalProxyTransitionException) shouldBe true
        f.runtime.state.value shouldBe RuntimeState.Ready(10808)
        f.adapter.startedConfigs.size shouldBe 1
    }

    @Test
    fun `enable and disable are serialised`() = runTest {
        val f = pausedFixture(testScheduler)
        var enableOutcome: RuntimeState? = null
        var disableOutcome: RuntimeState? = null

        val enableJob = launch { enableOutcome = f.runtime.enable(runtimeNodeTemplate("a")) }
        testScheduler.advanceUntilIdle()

        val disableJob = launch { disableOutcome = f.runtime.disable() }
        testScheduler.advanceUntilIdle()
        // The disable has not touched anything while the enable is mid-flight.
        f.adapter.startedConfigs.size shouldBe 1
        f.adapter.stopCount shouldBe 0
        f.runtime.state.value shouldBe RuntimeState.Starting

        f.adapter.inFlightStarts[0].complete(Unit)
        testScheduler.advanceUntilIdle()
        enableJob.join()
        disableJob.join()

        enableOutcome shouldBe RuntimeState.Ready(10808)
        disableOutcome shouldBe RuntimeState.Disabled
        f.store.enabledSync() shouldBe false
        f.gate.state() shouldBe ProxyEndpointState.Disabled
    }

    @Test
    fun `switch and disable are serialised`() = runTest {
        val f = pausedFixture(testScheduler)
        // The initial enable must not park: only the transition under test is paused.
        f.adapter.pauseOnStart = false
        f.runtime.enable(runtimeNodeTemplate("a"))
        f.adapter.pauseOnStart = true
        f.adapter.startErrors.clear()
        f.timeline.clear()

        var switchOutcome: RuntimeState? = null
        var disableOutcome: RuntimeState? = null

        val switchJob = launch { switchOutcome = f.runtime.switchNode(runtimeNodeTemplate("b")) }
        testScheduler.advanceUntilIdle()
        f.runtime.state.value shouldBe RuntimeState.Switching

        // startedConfigs accumulates across the whole test, so the assertion is relative: the parked
        // switch has already started once, and the queued disable must not add to it.
        val startedBefore = f.adapter.startedConfigs.size
        val disableJob = launch { disableOutcome = f.runtime.disable() }
        testScheduler.advanceUntilIdle()
        f.adapter.startedConfigs.size shouldBe startedBefore

        f.adapter.inFlightStarts[0].complete(Unit)
        testScheduler.advanceUntilIdle()
        switchJob.join()
        disableJob.join()

        switchOutcome shouldBe RuntimeState.Ready(10809)
        disableOutcome shouldBe RuntimeState.Disabled
        f.store.enabledSync() shouldBe false
        f.gate.state() shouldBe ProxyEndpointState.Disabled
    }

    @Test
    fun `switch and switch are serialised`() = runTest {
        val f = pausedFixture(testScheduler)
        // The initial enable must not park: only the transition under test is paused.
        f.adapter.pauseOnStart = false
        f.runtime.enable(runtimeNodeTemplate("a"))
        f.adapter.pauseOnStart = true
        f.adapter.startErrors.clear()
        f.timeline.clear()

        val first = launch { f.runtime.switchNode(runtimeNodeTemplate("b")) }
        testScheduler.advanceUntilIdle()
        f.runtime.state.value shouldBe RuntimeState.Switching

        val startedBefore = f.adapter.startedConfigs.size
        val second = launch { f.runtime.switchNode(runtimeNodeTemplate("c")) }
        testScheduler.advanceUntilIdle()
        f.adapter.startedConfigs.size shouldBe startedBefore

        // Release the parked first switch; the queued one then becomes the parked one and is
        // released in turn, which is what lets both coroutines finish.
        f.adapter.inFlightStarts[0].complete(Unit)
        testScheduler.advanceUntilIdle()
        f.adapter.inFlightStarts[1].complete(Unit)
        testScheduler.advanceUntilIdle()
        first.join()
        second.join()

        // The second switch found Ready and moved to the next node; no interleaving, one core.
        f.runtime.state.value shouldBe RuntimeState.Ready(10810)
        f.gate.state() shouldBe ProxyEndpointState.Available(ProxyEndpoint.loopbackSocks(10810))
        f.adapter.startedConfigs.size shouldBe startedBefore + 1
    }

    @Test
    fun `a cancelled enable is not a failure and leaves the gate blocked`() = runTest {
        val f = pausedFixture(testScheduler)
        var caught: Throwable? = null

        val job = launch {
            try {
                f.runtime.enable(runtimeNodeTemplate())
            } catch (error: Throwable) {
                caught = error
            }
        }
        testScheduler.advanceUntilIdle()
        f.runtime.state.value shouldBe RuntimeState.Starting

        job.cancel()
        testScheduler.advanceUntilIdle()
        job.join()

        // Cancellation is not a proxy failure and must not be reported as one.
        (caught is CancellationException) shouldBe true
        // Unknown outcome, so nothing is published as available and the recorded intent stands.
        f.gate.state() shouldBe ProxyEndpointState.Unavailable
        f.runtime.state.value shouldBe RuntimeState.Failed(ProxyFailureReason.LocalFailed)
        f.store.enabledSync() shouldBe true
    }

    @Test
    fun `a cancelled switch also fails closed`() = runTest {
        val f = pausedFixture(testScheduler)
        // The initial enable must not park: only the transition under test is paused.
        f.adapter.pauseOnStart = false
        f.runtime.enable(runtimeNodeTemplate("a"))
        f.adapter.pauseOnStart = true
        f.adapter.startErrors.clear()
        var caught: Throwable? = null

        val job = launch {
            try {
                f.runtime.switchNode(runtimeNodeTemplate("b"))
            } catch (error: Throwable) {
                caught = error
            }
        }
        testScheduler.advanceUntilIdle()

        job.cancel()
        testScheduler.advanceUntilIdle()
        job.join()

        (caught is CancellationException) shouldBe true
        f.gate.state() shouldBe ProxyEndpointState.Unavailable
        f.runtime.state.value shouldBe RuntimeState.Failed(ProxyFailureReason.LocalFailed)
        // The recorded intent is unchanged by an abandoned switch.
        f.store.enabledSync() shouldBe true
    }
}
