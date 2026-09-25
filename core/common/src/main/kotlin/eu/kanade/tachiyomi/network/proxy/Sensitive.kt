package eu.kanade.tachiyomi.network.proxy

/**
 * Marks a payload whose content is credential material and must stay inside the proxy layer: it must
 * not be logged, rendered, put into an error message, or written to storage that is not encrypted
 * with a Keystore-managed key; hardware-backed where available and verified.
 *
 * Where the payload is actually stored, and whether the backing key is hardware-backed on a given
 * device, is decided by the persistence layer (PR-D), not by this annotation.
 *
 * This is a marker rather than a compiler-enforced restriction, because the code that legitimately
 * needs the payload (the config builder and the selected-node store) lives in the same module while
 * everything else must treat it as opaque. `ProxyNodeModelTest` asserts that the marked types keep a
 * redacted string form, which is the property that actually prevents accidental disclosure.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Sensitive
