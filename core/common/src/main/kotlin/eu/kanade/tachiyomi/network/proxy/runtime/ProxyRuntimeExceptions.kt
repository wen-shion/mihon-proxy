package eu.kanade.tachiyomi.network.proxy.runtime

/**
 * The transition is not legal from the state the machine is in.
 *
 * This is a caller bug rather than a proxy failure: the state machine never reaches `Failed` for it,
 * and nothing about the proxy changes when it is thrown. It carries only state and event names, so
 * it is safe to log or show.
 */
class IllegalProxyTransitionException(
    val from: RuntimeState,
    val event: String,
) : IllegalStateException("cannot $event from ${from::class.simpleName}")

/**
 * The enabled flag could not be recorded, so the transition stopped before it began.
 *
 * Thrown rather than returned because the two directions fail differently and both need surfacing:
 * an interrupted enable has already blocked the gate (the machine is in `Failed`, still fail-closed),
 * while an interrupted disable has changed nothing at all and must leave the proxy running rather
 * than pretend it stopped. Carries no detail beyond the event name.
 */
class ProxyStatePersistException(event: String) :
    RuntimeException("the proxy enabled flag could not be persisted; $event did not proceed")
