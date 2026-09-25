package eu.kanade.tachiyomi.network.proxy

import libXray.LibXray

/**
 * The libXray methods this app calls.
 *
 * These strings mirror `LibXrayMethod` in the pinned libXray (`invoke_model.go`) and exist in exactly
 * one place: keeping them here means a change on the native side is a one-file change, and
 * `LibXrayInvokerIsolationTest` enforces that no other source file names a method.
 */
internal enum class XrayMethod(val wireName: String) {
    Version("xrayVersion"),
    State("getXrayState"),
    FreePorts("getFreePorts"),
    Test("testXray"),
    Run("runXray"),
    Stop("stopXray"),
    ConvertShareLinks("convertShareLinksToXrayJson"),
}

/**
 * The seam between the proxy layer and the native core. Tests substitute a fake to assert the call
 * sequence (notably that `runXray` is never reached after a failed `testXray`) without loading
 * `libgojni.so`.
 */
internal interface LibXrayInvoker {
    fun invoke(method: XrayMethod, payloadJson: String?): String
}

/**
 * The only production implementation: a thin, allocation-free wrapper over the single native entry
 * point. It adds no policy - retries, port selection and state live in the runtime - and it declares
 * no dependency on Android, so it can be exercised on the JVM through [LibXrayInvoker].
 */
internal class RealLibXrayInvoker : LibXrayInvoker {

    override fun invoke(method: XrayMethod, payloadJson: String?): String =
        LibXray.invoke(XrayExchange.envelope(method.wireName, payloadJson))
}
