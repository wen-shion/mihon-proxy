package eu.kanade.tachiyomi.network.proxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import libXray.LibXray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-15 (contract addendum): pins the outbound shape libXray actually accepts, on the real core.
 *
 * Phase 1B recorded the projection libXray emits for a `vless://` link. Running it here against the
 * pinned AAR found a difference that no JVM test could: the REALITY secret is carried as
 * **`password`**, and a payload that omits it is rejected by the core with
 * `Failed to build REALITY config. > empty "password"`.
 *
 * That rejection is what `XrayAdapter.start` has to catch *before* `runXray`, so this test also ties
 * the raw native error to the category the runtime branches on - otherwise the gate could be
 * checking a category the real core never produces.
 */
@RunWith(AndroidJUnit4::class)
class XrayContractProbeTest {

    @Test
    fun theProjectionShapeIsAcceptedByTheCore() {
        val response = testXray(PROJECTION_OUTBOUND)
        assertTrue("the core rejected the projection shape: $response", response.contains("\"success\":true"))
    }

    @Test
    fun realityWithoutASecretIsRejectedAndClassified() {
        // The negative control, and the reason the fixture must carry a REALITY secret at all. This
        // is the same mutation `XrayAdapterInstrumentedTest` feeds to start().
        val withoutSecret = PROJECTION_OUTBOUND.replace(
            """"password":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",""",
            "",
        )
        val response = testXray(withoutSecret)
        println("PROBE[reality-without-secret] -> $response")

        assertTrue(
            "the core accepted a REALITY outbound with no secret: $response",
            response.contains("\"success\":false"),
        )
        assertTrue(
            "the failure was not the REALITY one: $response",
            response.contains("REALITY", ignoreCase = true),
        )

        // The boundary must turn exactly this into the category the gate checks; if it did not,
        // start() would let the config through to runXray.
        val category = runCatching { XrayExchange.data(response) }
            .exceptionOrNull()
            ?.let { (it as XrayException).category }
        assertEquals(XrayErrorCategory.ConfigValidationFailed, category)
    }

    /**
     * The structural gate reads one shape only: a flat `settings.address` / `settings.port`.
     *
     * There is deliberately no compatibility branch for the nested `vnext` form, so this is where a
     * schema drift has to show up. If an AAR upgrade changes what the converter emits, this fails and
     * the parser is revisited on purpose - rather than the change being absorbed silently by a
     * parser written for a shape nobody has run.
     */
    @Test
    fun theConverterEmitsTheFlatSettingsShapeTheParserReads(): Unit = runBlocking {
        val response = LibXray.invoke(
            """{"apiVersion":3,"method":"convertShareLinksToXrayJson","payload":""" +
                """{"text":${quote(VLESS_SHARE_LINK)}}}""",
        )
        val outbounds = XrayExchange.data(response)[XrayExchange.KEY_OUTBOUNDS] as JsonArray
        val settings = (outbounds.single() as JsonObject)["settings"] as JsonObject

        assertNotNull("the converter stopped emitting a flat settings.address", settings["address"])
        assertNotNull("the converter stopped emitting a flat settings.port", settings["port"])
        assertNotNull("the converter stopped emitting the id", settings["id"])
        assertNull("the converter switched to the nested vnext form", settings["vnext"])
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
        /** A syntactically complete VLESS + REALITY link; `.113.10` is TEST-NET-3, not routable. */
        const val VLESS_SHARE_LINK =
            "vless://00000000-0000-0000-0000-000000000000@203.0.113.10:443" +
                "?security=reality&encryption=none&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "&fp=chrome&type=tcp&sni=www.example.com#spike-synthetic-node"

        /** Exactly what libXray's converter emits for a vless:// link (Phase 1B evidence). */
        const val PROJECTION_OUTBOUND =
            """{"protocol":"vless","settings":{"address":"203.0.113.10","encryption":"none",""" +
                """"id":"00000000-0000-0000-0000-000000000000","port":443},"streamSettings":""" +
                """{"network":"raw","realitySettings":{"fingerprint":"chrome",""" +
                """"password":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",""" +
                """"serverName":"www.example.com","shortId":"0000"},"security":"reality"},"tag":"spike-synthetic-node"}"""
    }
}
