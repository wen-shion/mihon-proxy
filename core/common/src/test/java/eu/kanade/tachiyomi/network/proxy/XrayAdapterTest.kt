package eu.kanade.tachiyomi.network.proxy

import eu.kanade.tachiyomi.network.proxy.model.NodeTemplate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * T-02 and T-03: the adapter's two gates, and the mapping from native failures to categories.
 *
 * The invoker is faked, so these tests never load `libgojni.so`; what they pin is the *call sequence*
 * (notably that `runXray` is unreachable once the core has rejected the config, and that a config the
 * core would accept but that cannot route never reaches it at all).
 */
class XrayAdapterTest {

    private class RecordingInvoker(
        private val responses: Map<XrayMethod, String> = emptyMap(),
        private val throwOn: XrayMethod? = null,
    ) : LibXrayInvoker {

        val calls = mutableListOf<XrayMethod>()
        val payloads = mutableListOf<String?>()

        override fun invoke(method: XrayMethod, payloadJson: String?): String {
            calls += method
            payloads += payloadJson
            if (method == throwOn) {
                throw IllegalStateException("native exploded while reaching https://secret.example/node")
            }
            return responses[method] ?: """{"success":true,"data":{}}"""
        }
    }

    private val nodePayload =
        """{"protocol":"vless","settings":{"address":"example.invalid","port":443}}"""

    private val startableConfig = XrayConfigBuilder.build(NodeTemplate(nodePayload), 10808)

    private fun adapter(invoker: LibXrayInvoker): XrayAdapter = RealXrayAdapter(invoker)

    // ------------------------------------------------------------------ gates

    @Test
    fun `validate asks the core to test the config and nothing else`(): Unit = runBlocking {
        val invoker = RecordingInvoker()
        adapter(invoker).validate(startableConfig)
        invoker.calls shouldContainExactly listOf(XrayMethod.Test)
    }

    @Test
    fun `start asks the core to run the config and nothing else`(): Unit = runBlocking {
        val invoker = RecordingInvoker()
        adapter(invoker).start(startableConfig)
        invoker.calls shouldContainExactly listOf(XrayMethod.Run)
    }

    @Test
    fun `a config the core rejects never reaches runXray`(): Unit = runBlocking {
        val invoker = RecordingInvoker(
            responses = mapOf(
                XrayMethod.Test to """{"success":false,"data":null,"error":"failed to build outbound: bad vless"}""",
            ),
        )
        val failure = shouldThrow<XrayException> { adapter(invoker).validate(startableConfig) }
        failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
        invoker.calls shouldContainExactly listOf(XrayMethod.Test)
    }

    @Test
    fun `start refuses an empty config without calling the core at all`(): Unit = runBlocking {
        val invoker = RecordingInvoker()
        // runXray accepts {"xrayJson":"{}"} and reports success, leaving a core that cannot route.
        val failure = shouldThrow<XrayException> { adapter(invoker).start("{}") }
        failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
        invoker.calls.shouldBeEmpty()
    }

    @Test
    fun `start refuses a config whose access log is not explicitly off`(): Unit = runBlocking {
        val invoker = RecordingInvoker()
        val leaked = startableConfig.replace("\"access\":\"none\"", "\"access\":\"console\"")
        val failure = shouldThrow<XrayException> { adapter(invoker).start(leaked) }
        failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
        invoker.calls.shouldBeEmpty()
    }

    @Test
    fun `start refuses a config with more than one outbound`(): Unit = runBlocking {
        val invoker = RecordingInvoker()
        // The built config ends with the outbound array followed by the closing brace, so appending a
        // second outbound before those two characters keeps the JSON valid.
        val doubled = startableConfig.dropLast(2) + """,{"protocol":"vless"}]}"""
        val failure = shouldThrow<XrayException> { adapter(invoker).start(doubled) }
        failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
        invoker.calls.shouldBeEmpty()
    }

    // ---------------------------------------------------------- error mapping

    @Test
    fun `native failures are mapped to categories`() {
        val cases = mapOf(
            "unknown method: nope" to XrayErrorCategory.UnknownMethod,
            "unsupported apiVersion" to XrayErrorCategory.UnknownMethod,
            "unsupported share format" to XrayErrorCategory.ShareLinkUnsupportedFormat,
            "invoke request exceeds the 16 MiB size limit" to XrayErrorCategory.PayloadTooLarge,
            "invalid character 'x' looking for beginning of value" to XrayErrorCategory.MalformedJson,
            "failed to build inbound: bad port" to XrayErrorCategory.ConfigValidationFailed,
            "listen tcp 127.0.0.1:10808: bind: address already in use" to XrayErrorCategory.LocalPortInUse,
            "xray is already running" to XrayErrorCategory.AlreadyRunning,
            "dial tcp: i/o timeout" to XrayErrorCategory.RemoteTimeout,
            "dial tcp 192.0.2.1:443: connect: connection refused" to XrayErrorCategory.RemoteConnectionRefused,
            "reality handshake failed" to XrayErrorCategory.RealityHandshakeFailed,
            "tls: handshake failure" to XrayErrorCategory.TlsHandshakeFailed,
            "unexpected EOF" to XrayErrorCategory.RemoteEof,
            "dial tcp: no route to host" to XrayErrorCategory.RemoteDialFailed,
            "something nobody has seen before" to XrayErrorCategory.Other,
        )
        cases.forEach { (text, expected) ->
            XrayExchange.categorise(text) shouldBe expected
        }
        XrayExchange.categorise(null) shouldBe XrayErrorCategory.LocalFailure
    }

    @Test
    fun `an exception carries the category and never the native text`(): Unit = runBlocking {
        val invoker = RecordingInvoker(
            responses = mapOf(
                XrayMethod.Test to """{"success":false,"data":null,""" +
                    """"error":"dial tcp secret.example:443: i/o timeout"}""",
            ),
        )
        val failure = shouldThrow<XrayException> { adapter(invoker).validate(startableConfig) }
        failure.category shouldBe XrayErrorCategory.RemoteTimeout
        failure.message shouldBe XrayErrorCategory.RemoteTimeout.name
        (failure.message!!.contains("secret.example")) shouldBe false
    }

    @Test
    fun `a java side failure becomes a local failure`(): Unit = runBlocking {
        val failure = shouldThrow<XrayException> { adapter(RecordingInvoker(throwOn = XrayMethod.Version)).version() }
        failure.category shouldBe XrayErrorCategory.LocalFailure
        (failure.message!!.contains("secret.example")) shouldBe false
    }

    // -------------------------------------------------------------- accessors

    @Test
    fun `version and state read the documented response fields`(): Unit = runBlocking {
        val invoker = RecordingInvoker(
            responses = mapOf(
                XrayMethod.Version to """{"success":true,"data":{"version":"26.9.9"}}""",
                XrayMethod.State to """{"success":true,"data":{"running":true}}""",
            ),
        )
        adapter(invoker).version() shouldBe "26.9.9"
        adapter(invoker).isRunning() shouldBe true
    }

    @Test
    fun `freePort returns the first advertised port and asks for two`(): Unit = runBlocking {
        val invoker = RecordingInvoker(
            responses = mapOf(XrayMethod.FreePorts to """{"success":true,"data":{"ports":[10808,10809]}}"""),
        )
        adapter(invoker).freePort() shouldBe 10808
        invoker.calls shouldContainExactly listOf(XrayMethod.FreePorts)
    }

    @Test
    fun `freePort fails as a local failure when no port is advertised`(): Unit = runBlocking {
        val invoker = RecordingInvoker(
            responses = mapOf(XrayMethod.FreePorts to """{"success":true,"data":{}}"""),
        )
        val failure = shouldThrow<XrayException> { adapter(invoker).freePort() }
        failure.category shouldBe XrayErrorCategory.LocalFailure
    }

    // ------------------------------------------------------------ conversion

    @Test
    fun `convertShareLinks returns one opaque payload per outbound`(): Unit = runBlocking {
        val invoker = RecordingInvoker(
            responses = mapOf(
                XrayMethod.ConvertShareLinks to """{"success":true,"data":{"outbounds":[""" +
                    """{"protocol":"vless","tag":"a"},{"protocol":"vless","tag":"b"}]}}""",
            ),
        )
        val outbounds = adapter(invoker).convertShareLinks("vless://...\nvless://...")
        outbounds.size shouldBe 2
        (outbounds[0].contains("\"tag\":\"a\"")) shouldBe true
    }

    @Test
    fun `convertShareLinks rejects a payload over the native size limit without calling the core`(): Unit =
        runBlocking {
            val invoker = RecordingInvoker()
            val oversized = "x".repeat(XrayExchange.MAX_PAYLOAD_BYTES + 1)
            val failure = shouldThrow<XrayException> { adapter(invoker).convertShareLinks(oversized) }
            failure.category shouldBe XrayErrorCategory.PayloadTooLarge
            invoker.calls.shouldBeEmpty()
        }

    @Test
    fun `convertShareLinks sends the age key only when one is provided`(): Unit = runBlocking {
        val invoker = RecordingInvoker(
            responses = mapOf(
                XrayMethod.ConvertShareLinks to """{"success":true,"data":{"outbounds":[{"protocol":"vless"}]}}""",
            ),
        )
        val adapter = adapter(invoker)
        adapter.convertShareLinks("vless://node")
        adapter.convertShareLinks("AGE-ENCRYPTED", ageSecretKey = "AGE-SECRET-KEY-1")
        invoker.payloads[0]!!.contains("secretKey") shouldBe false
        invoker.payloads[1]!!.contains("AGE-SECRET-KEY-1") shouldBe true
    }
}
