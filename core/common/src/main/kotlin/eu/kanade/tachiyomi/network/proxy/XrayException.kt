package eu.kanade.tachiyomi.network.proxy

/**
 * Why an adapter call failed, in a form that is safe to surface.
 *
 * The categories mirror the failure modes the Phase 1 spikes actually observed, so the runtime can
 * decide between retrying (a different port, a different node) and giving up, without ever seeing
 * the native error text. That text can contain the endpoint, so it is classified and then dropped.
 */
enum class XrayErrorCategory {
    /** The core does not know the method - a version mismatch between this client and the AAR. */
    UnknownMethod,

    /** The share-link text is not a format libXray can build outbounds from. */
    ShareLinkUnsupportedFormat,

    /** A request or the share-link payload was not valid JSON. */
    MalformedJson,

    /** The core rejected the config (bad inbound or outbound definition). */
    ConfigValidationFailed,

    /** The loopback port is already taken; the caller should ask for another one and retry. */
    LocalPortInUse,

    /** A core is already running in this process (`testXray` requires none). */
    AlreadyRunning,

    /** The remote did not answer in time. */
    RemoteTimeout,

    /** The remote refused the connection. */
    RemoteConnectionRefused,

    /** REALITY handshake failed - typically a wrong public key or short id, or a stale node. */
    RealityHandshakeFailed,

    /** TLS handshake failed. */
    TlsHandshakeFailed,

    /** The remote closed the connection during the handshake. */
    RemoteEof,

    /** The dial itself failed (bad address, no route). */
    RemoteDialFailed,

    /** The payload is larger than libXray's 16 MiB invoke limit. */
    PayloadTooLarge,

    /** The failure happened locally and has no useful category (including a Java-side exception). */
    LocalFailure,

    /** Classified as an error, but not matched by any known pattern. */
    Other,
}

/**
 * The only exception the adapter throws.
 *
 * [message] is the category name: the native error string is classified at the boundary and then
 * discarded, so no endpoint, credential or provider text can travel upwards through an exception.
 */
class XrayException(
    val category: XrayErrorCategory,
    cause: Throwable? = null,
) : Exception(category.name, cause)
