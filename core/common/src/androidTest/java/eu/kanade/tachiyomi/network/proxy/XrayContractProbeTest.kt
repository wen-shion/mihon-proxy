package eu.kanade.tachiyomi.network.proxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import libXray.LibXray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-15 (contract addendum): pins the outbound shape libXray actually accepts, on the real core.
 *
 * Phase 1B recorded the projection libXray emits for a `vless://` link. Running it here against the
 * pinned AAR found a difference that no JVM test could: the REALITY secret is carried as
 * **`password`**, and a payload that names it `publicKey` is rejected by the core with
 * `Failed to build REALITY config. > empty "password"`. Both facts are asserted below, so an AAR
 * upgrade or a change to the fixture that reintroduces the wrong key fails here rather than in the
 * runtime.
 */
@RunWith(AndroidJUnit4::class)
class XrayContractProbeTest {

    @Test
    fun theProjectionShapeIsAcceptedByTheCore() {
        assertAccepted(PROJECTION_OUTBOUND)
    }

    @Test
    fun realityWithoutASecretIsRejectedByTheCore() {
        // The negative control, and the reason the fixture must carry a REALITY secret at all: a
        // `realitySettings` object with no key material is rejected with
        // `Failed to build REALITY config. > empty "password"`. If this ever starts passing, the
        // core has changed how it reads REALITY settings and the fixture needs review.
        val withoutSecret = PROJECTION_OUTBOUND.replace(
            """"password":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",""",
            "",
        )
        val error = testXray(withoutSecret)
        assertTrue(
            "expected the core to reject a REALITY outbound without a secret, got: $error",
            error.contains("REALITY", ignoreCase = true) || error.contains("\"success\":false"),
        )
    }

    private fun assertAccepted(outboundJson: String) {
        val response = testXray(outboundJson)
        assertTrue("the core rejected the projection shape: $response", response.contains("\"success\":true"))
    }

    private fun testXray(outboundJson: String): String {
        val config = XrayConfigBuilder.build(outboundJson, 10808)
        return LibXray.invoke(
            """{"apiVersion":3,"method":"testXray","payload":{"xrayJson":${quote(config)}}}""",
        )
    }

    private fun quote(s: String): String =
        buildString {
            append('"')
            for (c in s) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    else -> append(c)
                }
            }
            append('"')
        }

    private companion object {
        /** Exactly what libXray's converter emits for a vless:// link (Phase 1B evidence). */
        const val PROJECTION_OUTBOUND =
            """{"protocol":"vless","settings":{"address":"203.0.113.10","encryption":"none",""" +
                """"id":"00000000-0000-0000-0000-000000000000","port":443},"streamSettings":""" +
                """{"network":"raw","realitySettings":{"fingerprint":"chrome",""" +
                """"password":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",""" +
                """"serverName":"www.example.com","shortId":"0000"},"security":"reality"},"tag":"spike-synthetic-node"}"""
    }
}
