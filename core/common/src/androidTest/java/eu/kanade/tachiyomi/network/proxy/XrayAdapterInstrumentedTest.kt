package eu.kanade.tachiyomi.network.proxy

import androidx.test.ext.junit.runners.AndroidJUnit4
import eu.kanade.tachiyomi.network.proxy.model.NodeTemplate
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T-15 / T-16 / T-17: the adapter against the *real* native core.
 *
 * The unit tests fake [LibXrayInvoker], so they can pin the envelope and the call sequence but they
 * cannot tell whether our reading of libXray's contract is right. These run on a device or an
 * emulator with `libgojni.so` loaded, so the wire names, the response shape and the gates are
 * exercised against the pinned AAR instead of against our own assumptions.
 *
 * Nothing here reaches the network: the configs point at `.invalid` addresses, which the resolver
 * is required to reject, so a `RemoteDialFailed`/`RemoteTimeout`/`RemoteConnectionRefused` outcome
 * is the *expected* result and is what separates "the config was rejected" (T-17) from "the remote
 * was unreachable".
 */
@RunWith(AndroidJUnit4::class)
class XrayAdapterInstrumentedTest {

    private lateinit var adapter: RealXrayAdapter

    @Before
    fun setUp() {
        adapter = RealXrayAdapter()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        // Never leave a core running between tests: testXray requires no instance to be live.
        runCatching { adapter.stop() }
    }

    // -- T-15: the methods exist on the AAR and their responses parse ----------------------------

    @Test
    fun versionReportsANonBlankStringFromTheCore(): Unit = runBlocking {
        val version = adapter.version()
        assertTrue("xrayVersion returned a blank string", version.isNotBlank())
        // A version that does not look like Xray's would mean we are talking to the wrong artifact.
        assertTrue("unexpected version string: $version", Regex("""^\d+\.\d+""").containsMatchIn(version))
    }

    @Test
    fun freePortReportsAPortInRange(): Unit = runBlocking {
        val port = adapter.freePort()
        assertTrue("port out of range: $port", port in 1..65535)
    }

    @Test
    fun freePortReturnsDifferentPortsAcrossCalls(): Unit = runBlocking {
        // Two ports so the runtime has a fallback without a second round trip; if the core returned
        // one fixed value the fallback would be useless.
        val ports = List(4) { adapter.freePort() }
        assertTrue("getFreePorts returned no usable port: $ports", ports.all { it in 1..65535 })
        assertEquals("the core returned one fixed port", ports.toSet().size, ports.size)
    }

    @Test
    fun shareLinksConvertToOpaqueNodeTemplates(): Unit = runBlocking {
        val nodes = adapter.convertShareLinks(VLESS_SHARE_LINK)
        assertEquals(1, nodes.size)
        val node = nodes.single()
        // One outbound object per node, carrying the protocol and the remark as the tag (Q1). The
        // payload is readable inside this module for a contract assertion...
        assertTrue("no protocol in the payload", node.outboundJson.contains("\"protocol\""))
        assertTrue("no tag in the payload", node.outboundJson.contains("\"tag\""))
        // ...and stays opaque in every string form, which is what would reach a log or a report.
        assertEquals("NodeTemplate(<redacted>)", node.toString())
        assertFalse("the payload leaked through the container", nodes.toString().contains("\"protocol\""))
    }

    @Test
    fun convertRejectsTextThatIsNotAShareLink(): Unit = runBlocking {
        try {
            adapter.convertShareLinks("this is not a subscription")
            fail("convertShareLinks accepted non-share-link text")
        } catch (expected: XrayException) {
            assertEquals(XrayErrorCategory.ShareLinkUnsupportedFormat, expected.category)
        }
    }

    // -- T-16: the gates hold against the real core ----------------------------------------------

    @Test
    fun anEmptyConfigIsRejectedAndNeverReachesTheCore(): Unit = runBlocking {
        // `runXray({"xrayJson":"{}"})` reports success and leaves a core that cannot route, which is
        // the failure mode the structural gate exists for.
        for (core in listOf("{}", """{"log":{"access":"none"},"inbounds":[],"outbounds":[]}""")) {
            try {
                adapter.start(core)
                fail("start accepted a core that cannot route: $core")
            } catch (expected: XrayException) {
                assertEquals(XrayErrorCategory.ConfigValidationFailed, expected.category)
            }
        }
    }

    @Test
    fun aConfigThatKeepsTheAccessLogOnIsRejectedBeforeTheCore(): Unit = runBlocking {
        val leaked = XrayConfigBuilder.build(VLESS_OUTBOUND, 10808)
            .replace("\"access\":\"none\"", "\"access\":\"\"")
        try {
            adapter.validate(leaked)
            fail("validate accepted a config whose access log is not off")
        } catch (expected: XrayException) {
            assertEquals(XrayErrorCategory.ConfigValidationFailed, expected.category)
        }
    }

    @Test
    fun aRejectedConfigNeverStartsTheCore(): Unit = runBlocking {
        try {
            adapter.start("{}")
            fail("start accepted an outbound-less config")
        } catch (expected: XrayException) {
            assertFalse("the core was started by a rejected config", adapter.isRunning())
        }
    }

    @Test
    fun theVerifiedConfigPassesTheCoresOwnValidation(): Unit = runBlocking {
        // Gate 1 alone is not enough - the core still has to accept the shape we build.
        adapter.validate(XrayConfigBuilder.build(NodeTemplate(VLESS_OUTBOUND), 10808))
    }

    /**
     * The gate that only `testXray` can provide, against the core itself.
     *
     * The config is structurally valid - it is produced by our own builder and passes every
     * invariant - but the core refuses it, because a REALITY outbound with no secret cannot be built.
     * `start` must therefore stop after `testXray` and never call `runXray`.
     */
    @Test
    fun aConfigTheCoreItselfRejectsIsNeverRun(): Unit = runBlocking {
        val withoutSecret = VLESS_OUTBOUND.replace(
            """"password":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",""",
            "",
        )
        val config = XrayConfigBuilder.build(withoutSecret, adapter.freePort())

        try {
            adapter.start(config)
            fail("start ran a config the core itself rejects")
        } catch (expected: XrayException) {
            assertEquals(XrayErrorCategory.ConfigValidationFailed, expected.category)
            assertFalse("the core was started by a config it refused", adapter.isRunning())
        }

        // Nothing was left behind: testXray refuses to run while an instance is live, so if the
        // rejected start had left a core up, this would report AlreadyRunning instead of validating.
        adapter.validate(XrayConfigBuilder.build(VLESS_OUTBOUND, adapter.freePort()))
    }

    // -- T-17: "the config is invalid" is distinguishable from "the remote is unreachable" -------

    @Test
    fun anUnresolvableRemoteIsNotReportedAsAnInvalidConfig(): Unit = runBlocking {
        val config = XrayConfigBuilder.build(NodeTemplate(VLESS_OUTBOUND), 10808)
        try {
            adapter.validate(config)
            // A core that accepts the config is fine; the point is that it must not be rejected.
        } catch (unexpected: XrayException) {
            fail(
                "a structurally valid config was rejected as ${unexpected.category}; " +
                    "an unreachable remote must not be classified as ConfigValidationFailed",
            )
        }
    }

    @Test
    fun startingTheVerifiedConfigBringsUpTheListenerAndReportsRunning(): Unit = runBlocking {
        val port = adapter.freePort()
        val config = XrayConfigBuilder.build(NodeTemplate(VLESS_OUTBOUND), port)
        // start tests first and only then runs; runXray is synchronous, so once start returns the
        // listener is up (F1).
        adapter.start(config)
        assertTrue("the core did not report itself as running", adapter.isRunning())
        adapter.stop()
        assertFalse("the core still reports itself as running after stop", adapter.isRunning())
    }

    @Test
    fun aJavaSideFailureIsReportedAsALocalFailureNotAnUnhandledCrash(): Unit = runBlocking {
        // A payload over libXray's 16 MiB invoke limit must be classified, not thrown as-is.
        val oversized = "x".repeat(XrayExchange.MAX_PAYLOAD_BYTES + 1)
        try {
            adapter.convertShareLinks(oversized)
            fail("convertShareLinks accepted a payload over the 16 MiB limit")
        } catch (expected: XrayException) {
            assertEquals(XrayErrorCategory.PayloadTooLarge, expected.category)
        }
    }

    @Test
    fun noFailureEverCarriesNativeText(): Unit = runBlocking {
        // The native string can name the endpoint, so the exception must carry only the category -
        // and neither the message, the string form nor the printed chain may echo the config.
        val failures = buildList {
            runCatching { adapter.convertShareLinks("not a link") }.exceptionOrNull()?.let(::add)
            runCatching { adapter.start("{}") }.exceptionOrNull()?.let(::add)
        }
        assertEquals("expected both calls to fail", 2, failures.size)

        val secrets = listOf(
            "example.invalid",
            "11111111-2222-3333-4444-555555555555",
            "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            "user:password",
        )
        for (failure in failures) {
            val error = failure as XrayException
            // The message is the category name - never the native text.
            assertEquals(error.category.name, error.message)
            // The category must be one we actually classify, not the unmapped bucket.
            assertFalse(
                "an expected failure was left unclassified: ${error.category}",
                error.category == XrayErrorCategory.Other,
            )
            error.renderings().forEach { rendered ->
                secrets.forEach { secret ->
                    assertFalse(
                        "a failure echoed secret material: $secret in ${rendered.take(200)}",
                        rendered.contains(secret),
                    )
                }
            }
        }
    }

    private companion object {
        /**
         * A syntactically complete VLESS + REALITY link. The address is `.invalid` (RFC 2606, not
         * resolvable), so nothing here can reach a real endpoint.
         */
        const val VLESS_SHARE_LINK =
            "vless://11111111-2222-3333-4444-555555555555@example.invalid:443" +
                "?security=reality&encryption=none&pbk=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
                "&fp=chrome&type=tcp&sni=example.invalid#tokyo-1"

        /**
         * The same node written the way libXray's own converter emits it (Phase 1B evidence, and
         * re-confirmed on device by `XrayContractProbeTest`).
         *
         * The REALITY key is `password`, not `publicKey`: a payload carrying `publicKey` is rejected
         * by the core with `Failed to build REALITY config. > empty "password"`. Using the real
         * projection shape here is what makes this a contract test rather than a test of a fixture.
         */
        const val VLESS_OUTBOUND =
            """{"protocol":"vless","tag":"tokyo-1","settings":{"address":"example.invalid",""" +
                """"port":443,"id":"11111111-2222-3333-4444-555555555555","encryption":"none"},""" +
                """"streamSettings":{"network":"raw","security":"reality","realitySettings":{""" +
                """"serverName":"example.invalid","fingerprint":"chrome",""" +
                """"password":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","shortId":"0000"}}}"""
    }
}

/** Every rendering of a throwable, including the printed chain, so a leak cannot hide in one. */
private fun Throwable.renderings(): List<String> {
    val chain = generateSequence(this) { it.cause }.toList()
    return buildList {
        addAll(chain.map { it.toString() })
        addAll(chain.map { it.message ?: "" })
        add(stackTraceToString())
        add(chain.last().stackTraceToString())
    }
}
