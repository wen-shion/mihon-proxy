package eu.kanade.tachiyomi.network.proxy.runtime

import eu.kanade.tachiyomi.network.proxy.ProxyEndpointState

/**
 * Why the runtime is in [RuntimeState.Failed].
 *
 * These names are safe to show, log and report: they carry no endpoint, no credential and no native
 * error text. The native error that caused a failure was classified by the adapter and then dropped,
 * so nothing here can echo it.
 */
enum class ProxyFailureReason {

    /**
     * The proxy is enabled, but there is no usable node to start with.
     *
     * In this phase that means no node has been provided since the process started; the encrypted
     * selected-node restore belongs to a later PR and will feed this same state when it fails.
     */
    SelectedNodeUnavailable,

    /**
     * The enabled flag could not be recorded, so the transition did not proceed.
     *
     * Reported rather than swallowed because "act first, record later" is what lets a crash revive a
     * proxy the user just turned off.
     */
    PersistFailed,

    /** Every port attempt of the bounded retry failed. */
    PortUnavailable,

    /** The core refused the node, at validation or at start. */
    NodeRejected,

    /** A local failure with no more specific reason (evictor, probe, unexpected I/O). */
    LocalFailed,
}

/**
 * Where the runtime is. Six states, deliberately more than the three the HTTP layer sees.
 *
 * The projection into [ProxyEndpointState] is deliberately lossy and always fail-closed: only
 * [Ready] maps to `Available`, and every other state - including every failure - maps to
 * `Unavailable`. No transition in this machine can ever produce a `Disabled` *projection* as the
 * result of a failure; the only way to `Disabled` is a completed [RuntimeState.Disabled], which
 * happens because the user asked for it.
 */
sealed interface RuntimeState {

    /** Proxy off, core not running. The only state whose projection is [ProxyEndpointState.Disabled]. */
    data object Disabled : RuntimeState

    /** Enabled, validating and starting the core. Nothing is served yet. */
    data object Starting : RuntimeState

    /** The core is running and its listener answered on loopback. */
    data class Ready(val port: Int) : RuntimeState

    /** Switching to another node: the previous core is being torn down and a new one started. */
    data object Switching : RuntimeState

    /** A disable is in progress; the tunnel is already closed to new traffic. */
    data object Stopping : RuntimeState

    /**
     * The last transition failed. The proxy is still *enabled* - the recorded intent is on - so the
     * projection stays [ProxyEndpointState.Unavailable] and nothing leaves untunnelled.
     */
    data class Failed(val reason: ProxyFailureReason) : RuntimeState
}
