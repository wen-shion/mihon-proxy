package eu.kanade.tachiyomi.network.proxy

/**
 * Marks a payload whose content is credential material and must stay inside the proxy layer:
 * it must not be logged, rendered, put into an error message, or written to storage that is not
 * encrypted with a hardware-backed key.
 *
 * This is a marker rather than a compiler-enforced restriction, because the code that legitimately
 * needs the payload (the config builder and the selected-node store) lives in the same module while
 * everything else must treat it as opaque. `NodeTemplateTest` asserts that the marked types keep a
 * redacted string form, which is the property that actually prevents accidental disclosure.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class Sensitive
