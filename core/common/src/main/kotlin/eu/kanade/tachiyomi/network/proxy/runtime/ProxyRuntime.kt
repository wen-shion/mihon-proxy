package eu.kanade.tachiyomi.network.proxy.runtime

import eu.kanade.tachiyomi.network.proxy.ProxyEndpoint
import eu.kanade.tachiyomi.network.proxy.ProxyEndpointState
import eu.kanade.tachiyomi.network.proxy.XrayAdapter
import eu.kanade.tachiyomi.network.proxy.XrayConfigBuilder
import eu.kanade.tachiyomi.network.proxy.XrayErrorCategory
import eu.kanade.tachiyomi.network.proxy.XrayException
import eu.kanade.tachiyomi.network.proxy.model.NodeTemplate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.coroutines.cancellation.CancellationException

/**
 * Defensively asserts that the core really is listening on loopback [port].
 *
 * `runXray` returning success is not evidence of readiness by itself, and the projection published
 * after this probe is what the whole app trusts. A refused connect here turns a would-be silent
 * "enabled but nothing loads" into a classified failure.
 */
fun interface LoopbackProbe {

    fun assertListening(port: Int)
}

/**
 * The proxy's state machine.
 *
 * One instance per process, one [Mutex] for every transition, and a fixed order inside each one.
 * The order is not a style preference - an OkHttp pooled connection survives the core behind it and
 * is reused by the next same-host request, so blocking the gate, clearing the content plane and only
 * then publishing a new state is what keeps a transition from leaking either way.
 *
 * Everything a transition does is fail-closed. `Unavailable` is the projection of every state except
 * [RuntimeState.Ready] and [RuntimeState.Disabled], and no failure anywhere in this machine can turn
 * the proxy off: a failed enable or switch stays enabled and unserved, and the only way to
 * [RuntimeState.Disabled] is a disable that the user asked for and that got recorded first.
 *
 * This class owns no network client and no repository. It is given the adapter, the evictor and the
 * store, and it is the only writer of the gate.
 *
 * Not annotated for dependency injection yet: like the adapter, the wiring is a later PR's job, and
 * adding it now would imply a graph that does not exist.
 */
class ProxyRuntime(
    private val adapter: XrayAdapter,
    private val evictor: ConnectionEvictor,
    private val enabledStore: ProxyEnabledStore,
    private val gate: ProxyRouteGate,
    private val probe: LoopbackProbe = RealLoopbackProbe,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutex = Mutex()

    private val mutableState = MutableStateFlow(initialState())

    /** The machine's state. The UI reads this; the projection into the gate happens inside. */
    val state: StateFlow<RuntimeState> = mutableState.asStateFlow()

    private var lastTemplate: NodeTemplate? = null

    private fun initialState(): RuntimeState = when (gate.state()) {
        // Persisted off and nothing started: the plain resting state.
        ProxyEndpointState.Disabled -> RuntimeState.Disabled
        // Persisted on, but this phase has no restore and no node has been provided yet. Fail-closed:
        // the gate was already constructed as Unavailable and the user is told there is no node.
        else -> RuntimeState.Failed(ProxyFailureReason.SelectedNodeUnavailable)
    }

    /**
     * Turns the proxy on with [template].
     *
     * The gate is blocked first, then the intent is recorded, then the content plane is cleared, and
     * only then is the core started. If any of that fails the machine lands in `Failed` and stays
     * enabled - the user asked for the proxy and it did not come up, which is a different thing from
     * the user turning it off.
     */
    suspend fun enable(template: NodeTemplate): RuntimeState = withContext(io) {
        mutex.withLock {
            val from = state.value
            if (!from.canEnable()) throw IllegalProxyTransitionException(from, "enable")

            try {
                gate.set(ProxyEndpointState.Unavailable)
                publish(RuntimeState.Starting)

                if (!enabledStore.persist(true)) throw ProxyStatePersistException("enable")

                // Remembered before anything can fail: retry() re-runs this node, and a transition
                // that failed at the very first step has to leave something to retry.
                lastTemplate = template

                evictor.cancelAndEvict()
                val port = startNode(template)

                publish(RuntimeState.Ready(port))
                state.value
            } catch (error: CancellationException) {
                abortCancelled()
                throw error
            } catch (error: ProxyStatePersistException) {
                failWith(ProxyFailureReason.PersistFailed)
                throw error
            } catch (error: PortRetryExhaustedException) {
                failWith(ProxyFailureReason.PortUnavailable)
            } catch (error: NodeRejectedException) {
                failWith(ProxyFailureReason.NodeRejected)
            } catch (error: Throwable) {
                failWith(ProxyFailureReason.LocalFailed)
            }
        }
    }

    /**
     * Turns the proxy off.
     *
     * The intent is recorded first, so a crash immediately after leaves a store that agrees with what
     * the user asked for; the gate is blocked next, because stopping the core does not close the
     * connections it is already carrying; the core is stopped last. A cleanup failure is not allowed
     * to keep the proxy running or to refuse the user's explicit off - the gate is `Disabled`
     * regardless, and a core that would not stop is inert with no selector routing to it and is
     * handled by the next start's already-running branch.
     *
     * From `Disabled` this is a no-op that returns unchanged, so callers do not have to check first.
     */
    suspend fun disable(): RuntimeState = withContext(io) {
        mutex.withLock {
            val from = state.value
            if (from is RuntimeState.Disabled) return@withContext from

            try {
                if (!enabledStore.persist(false)) throw ProxyStatePersistException("disable")

                gate.set(ProxyEndpointState.Unavailable)
                publish(RuntimeState.Stopping)

                try {
                    evictor.cancelAndEvict()
                    adapter.stop()
                    evictor.cancelAndEvict()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // A failed cleanup still ends in Disabled; see the class doc.
                    Unit
                }

                publish(RuntimeState.Disabled)
                state.value
            } catch (error: CancellationException) {
                abortCancelled()
                throw error
            }
        }
    }

    /**
     * Moves to [template] without changing that the proxy is on.
     *
     * The previous core is stopped and a fresh one is started on a freshly chosen port; a second
     * eviction follows, because a new port can land on the one just released and a same-host pooled
     * connection from the old node would otherwise be reused. No rollback: if the new node fails the
     * machine is in `Failed`, still enabled, and the way back to the old node is another switch the
     * user asks for - silently serving the old node while the UI shows the new one is the one outcome
     * this machine refuses.
     *
     * Persisting the selected node belongs to the layer above; this transition only moves the core.
     */
    suspend fun switchNode(template: NodeTemplate): RuntimeState = withContext(io) {
        mutex.withLock {
            val from = state.value
            if (!from.canSwitch()) throw IllegalProxyTransitionException(from, "switchNode")

            try {
                lastTemplate = template

                gate.set(ProxyEndpointState.Unavailable)
                publish(RuntimeState.Switching)

                evictor.cancelAndEvict()
                // A stop that fails is not fatal: the port loop's already-running branch stops a
                // lingering core and tries again, and a second eviction follows the new start.
                try {
                    adapter.stop()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    // tolerated; see above
                }

                val port = startNode(template)
                evictor.cancelAndEvict()

                publish(RuntimeState.Ready(port))
                state.value
            } catch (error: CancellationException) {
                abortCancelled()
                throw error
            } catch (error: PortRetryExhaustedException) {
                failWith(ProxyFailureReason.PortUnavailable)
            } catch (error: NodeRejectedException) {
                failWith(ProxyFailureReason.NodeRejected)
            } catch (error: Throwable) {
                failWith(ProxyFailureReason.LocalFailed)
            }
        }
    }

    /**
     * Re-runs the node the last failed transition tried to use.
     *
     * Only meaningful while the proxy is still enabled, which the store confirms: a disable that was
     * interrupted has already recorded the off, and bringing the proxy back up against it would
     * override what the user asked for.
     */
    suspend fun retry(): RuntimeState = withContext(io) {
        val template = lastTemplate
        val from = state.value
        if (from !is RuntimeState.Failed || template == null || !enabledStore.enabledSync()) {
            throw IllegalProxyTransitionException(from, "retry")
        }
        enable(template)
    }

    // ------------------------------------------------------------- internals

    /**
     * The bounded port loop.
     *
     * Every attempt asks the core for a fresh port and builds the config from scratch - a port that
     * turned out to be taken is not retried with the same config, and no spare port is carried
     * between attempts. `LocalPortInUse` moves to the next attempt, `AlreadyRunning` stops the
     * lingering core once and tries again, and anything else means the node itself was refused.
     */
    private suspend fun startNode(template: NodeTemplate): Int {
        var alreadyRunningHandled = false
        repeat(MAX_PORT_ATTEMPTS) {
            val port = adapter.freePort()
            try {
                val config = XrayConfigBuilder.build(template, port)
                adapter.start(config)
            } catch (error: XrayException) {
                when (error.category) {
                    XrayErrorCategory.LocalPortInUse -> return@repeat
                    XrayErrorCategory.AlreadyRunning -> {
                        if (alreadyRunningHandled) throw NodeRejectedException()
                        adapter.stop()
                        alreadyRunningHandled = true
                        return@repeat
                    }
                    // Everything else - a config the gate rejects, a validation the core refuses, a
                    // start that will not come up - is about this node, and no port fixes it.
                    else -> throw NodeRejectedException()
                }
            }
            probe.assertListening(port)
            return port
        }
        throw PortRetryExhaustedException()
    }

    private fun RuntimeState.canEnable(): Boolean = this is RuntimeState.Disabled || this is RuntimeState.Failed

    private fun RuntimeState.canSwitch(): Boolean = this is RuntimeState.Ready || this is RuntimeState.Failed

    /** Six internal states, three published ones, and only `Ready` is ever `Available`. */
    private fun RuntimeState.projection(): ProxyEndpointState = when (this) {
        RuntimeState.Disabled -> ProxyEndpointState.Disabled
        is RuntimeState.Ready -> ProxyEndpointState.Available(ProxyEndpoint.loopbackSocks(port))
        else -> ProxyEndpointState.Unavailable
    }

    private fun publish(next: RuntimeState) {
        gate.set(next.projection())
        mutableState.value = next
    }

    /**
     * The end state of a failed transition, published while still holding the mutex. `Unavailable`,
     * always: the proxy is still enabled, so nothing leaves untunnelled.
     */
    private fun failWith(reason: ProxyFailureReason): RuntimeState {
        val failed = RuntimeState.Failed(reason)
        gate.set(ProxyEndpointState.Unavailable)
        mutableState.value = failed
        return failed
    }

    /**
     * The minimal cleanup a cancelled transition is allowed: two in-memory writes. Nothing is
     * published as `Available` (the outcome is unknown), nothing is published as `Disabled` (the user
     * did not ask for that here), no I/O runs, and the cancellation is rethrown untouched.
     */
    private fun abortCancelled() {
        gate.set(ProxyEndpointState.Unavailable)
        mutableState.value = RuntimeState.Failed(ProxyFailureReason.LocalFailed)
    }

    private class NodeRejectedException : RuntimeException("the core refused this node")

    private class PortRetryExhaustedException : RuntimeException("no usable port")

    private companion object {
        const val MAX_PORT_ATTEMPTS = 3
        const val PROBE_TIMEOUT_MS = 1_000

        val RealLoopbackProbe = LoopbackProbe { port ->
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), PROBE_TIMEOUT_MS)
            }
        }
    }
}
