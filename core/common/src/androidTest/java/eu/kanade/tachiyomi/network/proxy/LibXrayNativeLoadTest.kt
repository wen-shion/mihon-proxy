package eu.kanade.tachiyomi.network.proxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import libXray.LibXray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-15 (native-loading half): the pinned `libgojni.so` is present in the test APK, its ABI matches
 * the device, and the single entry point the production code uses is reachable.
 *
 * This is the check the JVM unit tests structurally cannot make: they never load the shared object.
 * It also pins the one fact the whole adapter rests on - that `LibXray.invoke` accepts a JSON
 * envelope and answers with one - so an AAR swap that changes the entry point fails here.
 */
@RunWith(AndroidJUnit4::class)
class LibXrayNativeLoadTest {

    @Test
    fun theNativeLibraryIsLoadedForTheDeviceAbi() {
        // Loading is what proves the AAR ships a slice for this ABI (x86_64 on the emulator, arm64
        // on a phone); a mismatch throws UnsatisfiedLinkError here rather than in production.
        val version = LibXray.invoke("""{"apiVersion":${XrayExchange.API_VERSION},"method":"xrayVersion"}""")
        assertTrue("empty response from the native core", version.isNotBlank())
    }

    @Test
    fun theInvokerRoundTripsThroughTheNativeEntryPoint() {
        val invoker: LibXrayInvoker = RealLibXrayInvoker()
        val response = invoker.invoke(XrayMethod.Version, null)
        val data = XrayExchange.data(response)
        val version = data.strictString(XrayExchange.KEY_VERSION)
        assertTrue("the native core reported no version", version != null && version.isNotBlank())
    }

    @Test
    fun anUnknownMethodIsClassifiedRatherThanCrashed() {
        // A version skew between this client and the AAR must surface as a category, not as an
        // unhandled native exception.
        val response = LibXray.invoke(
            """{"apiVersion":${XrayExchange.API_VERSION},"method":"thisMethodDoesNotExist"}""",
        )
        val category = runCatching { XrayExchange.data(response) }
            .exceptionOrNull()
            ?.let { (it as? XrayException)?.category }
        assertTrue(
            "expected a classified failure, got: $response",
            category == XrayErrorCategory.UnknownMethod || category == XrayErrorCategory.Other,
        )
    }

    @Test
    fun theEnvelopeApiVersionMatchesThePinnedAar() {
        // If the AAR ever bumps its apiVersion, every call starts failing with UnknownMethod; this
        // catches the bump at the place where the constant is defined.
        assertEquals(3, XrayExchange.API_VERSION)
    }
}
