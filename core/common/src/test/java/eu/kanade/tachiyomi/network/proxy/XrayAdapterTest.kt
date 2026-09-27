package eu.kanade.tachiyomi.network.proxy

import eu.kanade.tachiyomi.network.proxy.model.NodeTemplate
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.coroutines.cancellation.CancellationException

/**
 * T-02 and T-03: the adapter's gates, and the mapping from native failures to categories.
 *
 * The invoker is faked, so these tests never load `libgojni.so`; what they pin is the *call sequence*
 * - that the core is asked to test the config before anything runs it, that `runXray` is unreachable
 * once the core has rejected it, and that a config which cannot route never reaches the core at all.
 */
class XrayAdapterTest {

    private class RecordingInvoker(
        private val responses: Map<XrayMethod, String> = emptyMap(),
        private val throwOn: XrayMethod? = null,
        private val throwWhat: () -> Throwable = {
            IllegalStateException("native exploded while reaching https://secret.example/node")
        },
    ) : LibXrayInvoker {

        val calls = mutableListOf<XrayMethod>()
        val payloads = mutableListOf<String?>()

        override fun invoke(method: XrayMethod, payloadJson: String?): String {
            calls += method
            payloads += payloadJson
            if (method == throwOn) {
                throw throwWhat()
            }
            return responses[method] ?: """{"success":true,"data":{}}"""
        }
    }

    /**
     * A real VLESS-over-REALITY outbound in libXray's projection shape, which the structural gate
     * now requires: the outbound family is pinned to vless + reality, and the node carries a secret.
     */
    private val nodePayload =
        """{"protocol":"vless","tag":"tokyo-1","settings":{"address":"example.invalid","port":443,""" +
            """"id":"11111111-2222-3333-4444-555555555555","encryption":"none"},""" +
            """"streamSettings":{"network":"raw","security":"reality","realitySettings":{""" +
            """"serverName":"example.invalid","fingerprint":"chrome",""" +
            """"password":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","shortId":"0000"}}}"""

    private val startableConfig = XrayConfigBuilder.build(NodeTemplate(nodePayload), 10808)

    private fun adapter(invoker: LibXrayInvoker): XrayAdapter = RealXrayAdapter(invoker)

    // --------------------------------------------------------------- gate order

    @Test
    fun `validate asks the core to test the config and never runs it`(): Unit = runBlocking {
        val invoker = RecordingInvoker()
        adapter(invoker).validate(startableConfig)
        invoker.calls shouldContainExactly listOf(XrayMethod.Test)
    }

    @Test
    fun `start tests the config before running it`(): Unit = runBlocking {
        val invoker = RecordingInvoker()
        adapter(invoker).start(startableConfig)
        // The core gets a say before anything is put into service; the caller does not have to have
        // called validate() first for that to be true.
        invoker.calls shouldContainExactly listOf(XrayMethod.Test, XrayMethod.Run)
    }

    @Test
    fun `start never reaches runXray when the core rejects the config`(): Unit = runBlocking {
        val invoker = RecordingInvoker(
            responses = mapOf(
                XrayMethod.Test to """{"success":false,"data":null,"error":"failed to build outbound: bad vless"}""",
            ),
        )
        val failure = shouldThrow<XrayException> { adapter(invoker).start(startableConfig) }
        failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
        // The whole point: Run is not attempted when Test said no.
        invoker.calls shouldContainExactly listOf(XrayMethod.Test)
    }

    // ---------------------------------------------------------- structural gate

    @Test
    fun `start refuses an empty config without calling the core at all`(): Unit = runBlocking {
        val invoker = RecordingInvoker()
        // runXray accepts {"xrayJson":"{}"} and reports success, leaving a core that cannot route.
        val failure = shouldThrow<XrayException> { adapter(invoker).start("{}") }
        failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
        invoker.calls.shouldBeEmpty()
    }

    /**
     * Every invariant of the production config, one mutation at a time.
     *
     * The gate is the only thing standing between a caller and a weaker config, so each line here is
     * a config that would otherwise reach the core: logging that leaks the destination and the node's
     * remark, a listener reachable off-device, a second inbound, a routing or DNS section that would
     * bypass the selected node, or an outbound family the MVP never verified on a device.
     */
    @Test
    fun `the structural gate locks every production invariant`(): Unit = runBlocking {
        val inbound =
            """{"tag":"socks-in","listen":"127.0.0.1","port":10808,"protocol":"socks",""" +
                """"settings":{"auth":"noauth","udp":false}}"""
        val socks = { listen: String, auth: String, udp: String, port: String ->
            """{"tag":"socks-in","listen":"$listen","port":$port,"protocol":"socks",""" +
                """"settings":{"auth":"$auth","udp":$udp}}"""
        }

        fun config(
            log: String? = """{"loglevel":"warning","access":"none"}""",
            inbounds: String = "[$inbound]",
            outbounds: String = "[$nodePayload]",
            extra: String = "",
        ): String {
            val parts = buildList {
                if (log != null) add("\"log\":$log")
                add("\"inbounds\":$inbounds")
                add("\"outbounds\":$outbounds")
                if (extra.isNotEmpty()) add(extra)
            }
            return parts.joinToString(prefix = "{", postfix = "}", separator = ",")
        }

        fun outbound(protocol: String, security: String?): String {
            val stream = if (security == null) "" else ""","streamSettings":{"security":"$security"}"""
            return """{"protocol":"$protocol","settings":{"address":"example.invalid","port":443}$stream}"""
        }

        val rejected = mapOf(
            "log section absent" to config(log = null),
            "loglevel is not warning" to config(log = """{"loglevel":"info","access":"none"}"""),
            "access is not none" to config(log = """{"loglevel":"warning","access":"console"}"""),
            "access is absent" to config(log = """{"loglevel":"warning"}"""),
            "routing section present" to config(extra = """"routing":{"rules":[]}"""),
            "dns section present" to config(extra = """"dns":{"servers":[]}"""),
            "metrics section present" to config(extra = """"metrics":{"tag":"m"}"""),
            "observatory section present" to config(extra = """"observatory":{"subjectSelector":[]}"""),
            "reverse section present" to config(extra = """"reverse":{"bridges":[]}"""),
            "no inbound" to config(inbounds = "[]"),
            "two inbounds" to config(inbounds = "[$inbound,$inbound]"),
            "tun inbound" to config(
                inbounds = """[{"protocol":"tun","listen":"127.0.0.1","port":10808,""" +
                    """"settings":{"auth":"noauth","udp":false}}]""",
            ),
            "inbound listens off loopback" to config(
                inbounds = "[${socks("0.0.0.0", "noauth", "false", "10808")}]",
            ),
            "inbound requires auth" to config(
                inbounds = "[${socks("127.0.0.1", "password", "false", "10808")}]",
            ),
            "inbound udp enabled" to config(inbounds = "[${socks("127.0.0.1", "noauth", "true", "10808")}]"),
            "inbound port out of range" to config(inbounds = "[${socks("127.0.0.1", "noauth", "false", "0")}]"),
            "inbound port is a string" to config(
                inbounds = "[${socks("127.0.0.1", "noauth", "false", "\"10808\"")}]",
            ),
            "no outbound" to config(outbounds = "[]"),
            "two outbounds" to config(outbounds = "[$nodePayload,$nodePayload]"),
            "freedom outbound" to config(outbounds = "[${outbound("freedom", "reality")}]"),
            "vmess outbound" to config(outbounds = "[${outbound("vmess", "reality")}]"),
            "trojan outbound" to config(outbounds = "[${outbound("trojan", "reality")}]"),
            "vless without reality" to config(outbounds = "[${outbound("vless", "tls")}]"),
            "vless without stream settings" to config(outbounds = "[${outbound("vless", null)}]"),
        )

        rejected.forEach { (name, configJson) ->
            val invoker = RecordingInvoker()
            withClue(name) {
                val failure = shouldThrow<XrayException> { adapter(invoker).start(configJson) }
                failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
                // Rejected before the core saw it: neither Test nor Run.
                invoker.calls.shouldBeEmpty()
            }
        }
    }

    @Test
    fun `the gate accepts the config the builder produces`(): Unit = runBlocking {
        val invoker = RecordingInvoker()
        adapter(invoker).start(startableConfig)
        invoker.calls shouldContainExactly listOf(XrayMethod.Test, XrayMethod.Run)
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
        failure.renderings().none { it.contains("secret.example") } shouldBe true
    }

    // --------------------------------------------------- cause-chain redaction

    @Test
    fun `a java side failure keeps no reference to the original throwable`(): Unit = runBlocking {
        val failure = shouldThrow<XrayException> {
            adapter(RecordingInvoker(throwOn = XrayMethod.Version)).version()
        }
        failure.category shouldBe XrayErrorCategory.LocalFailure

        // The fake threw IllegalStateException("native exploded while reaching https://secret.example/node").
        // Neither the message, the toString of the chain, nor the printed stack trace may carry it.
        failure.renderings().none { it.contains("secret.example") } shouldBe true
        // The type is still available for a local diagnosis - that is all a cause may contribute.
        (failure.cause as SanitisedCause).originalType shouldBe IllegalStateException::class.java.name
        // And the chain ends: there is no original throwable hanging off the sanitised cause.
        failure.cause!!.cause shouldBe null
    }

    @Test
    fun `the sanitiser keeps the type and drops the message`() {
        val original = IllegalStateException("reaching https://secret.example/node")
        val sanitised = original.sanitisedCause()

        sanitised.originalType shouldBe IllegalStateException::class.java.name
        sanitised.toString().contains("secret.example") shouldBe false
        sanitised.message!!.contains("secret.example") shouldBe false
        sanitised.cause shouldBe null
        // Nothing points back at the original throwable.
        sanitised.renderings().none { it.contains("secret.example") } shouldBe true
    }

    @Test
    fun `a json parse failure does not echo the config it failed on`(): Unit = runBlocking {
        // Truncated on purpose: the parser's own message quotes the input it choked on, and that
        // input is a config carrying the node's endpoint and credentials.
        val truncated =
            """{"protocol":"vless","settings":{"address":"secret.example","port":443,""" +
                """"id":"11111111-2222-3333-4444-555555555555"}"""
        val invoker = RecordingInvoker()
        val failure = shouldThrow<XrayException> { adapter(invoker).start(truncated) }

        failure.category shouldBe XrayErrorCategory.MalformedJson
        invoker.calls.shouldBeEmpty()
        failure.renderings().none { it.contains("secret.example") } shouldBe true
        failure.renderings().none { it.contains("11111111-2222-3333-4444-555555555555") } shouldBe true

        // The type is preserved - that is the only thing a cause may contribute - while the message
        // is not. Derived rather than hard-coded because kotlinx throws a subtype of the declared
        // exception, and the point is that whatever was thrown is what shows up.
        val originalType = runCatching { Json.parseToJsonElement(truncated) }
            .exceptionOrNull()!!
            .javaClass.name
        (failure.cause as SanitisedCause).originalType shouldBe originalType
        originalType.startsWith("kotlinx.serialization") shouldBe true
    }

    @Test
    fun `the envelope never puts a raw throwable on the cause chain`() {
        // XrayExchange is the other place a parser failure is caught.
        val failure = shouldThrow<XrayException> {
            XrayExchange.envelope("testXray", """{"xrayJson":"with secret.example inside" """)
        }
        failure.category shouldBe XrayErrorCategory.MalformedJson
        failure.renderings().none { it.contains("secret.example") } shouldBe true
        failure.cause!!.cause shouldBe null
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
    fun `isRunning reads both boolean values`(): Unit = runBlocking {
        val running = RecordingInvoker(
            responses = mapOf(XrayMethod.State to """{"success":true,"data":{"running":true}}"""),
        )
        val stopped = RecordingInvoker(
            responses = mapOf(XrayMethod.State to """{"success":true,"data":{"running":false}}"""),
        )
        adapter(running).isRunning() shouldBe true
        adapter(stopped).isRunning() shouldBe false
    }

    /**
     * A response that does not say whether a core is running is a failure, not `false`.
     *
     * Defaulting to "not running" would let a contract break masquerade as a stopped core, and
     * "stopped" is an ordinary, expected state for the runtime - so the break would be invisible.
     */
    @Test
    fun `isRunning refuses a response that does not carry a boolean`(): Unit = runBlocking {
        listOf(
            """{"success":true,"data":{}}""",
            """{"success":true,"data":{"running":"true"}}""",
            """{"success":true,"data":{"running":1}}""",
            """{"success":true,"data":{"running":null}}""",
            """{"success":true,"data":{"running":{}}}""",
        ).forEach { response ->
            withClue(response) {
                val invoker = RecordingInvoker(responses = mapOf(XrayMethod.State to response))
                val failure = shouldThrow<XrayException> { adapter(invoker).isRunning() }
                failure.category shouldBe XrayErrorCategory.LocalFailure
            }
        }
    }

    /**
     * The version is a string and nothing else.
     *
     * `contentOrNull` would happily read a number or a boolean as its text, which is how a contract
     * drift turns into a `String` the caller cannot tell from a real version. Every wrong type is a
     * failure, and none of them is rendered.
     */
    @Test
    fun `version refuses a response whose version is not a string`(): Unit = runBlocking {
        listOf(
            """{"success":true,"data":{}}""",
            """{"success":true,"data":{"version":null}}""",
            """{"success":true,"data":{"version":26.9}}""",
            """{"success":true,"data":{"version":1}}""",
            """{"success":true,"data":{"version":true}}""",
            """{"success":true,"data":{"version":{}}}""",
            """{"success":true,"data":{"version":["26.9.9"]}}""",
        ).forEach { response ->
            withClue(response) {
                val invoker = RecordingInvoker(responses = mapOf(XrayMethod.Version to response))
                val failure = shouldThrow<XrayException> { adapter(invoker).version() }
                failure.category shouldBe XrayErrorCategory.LocalFailure
                failure.renderings().none { it.contains("26.9") } shouldBe true
            }
        }
    }

    @Test
    fun `freePort returns the first advertised port and asks for exactly one`(): Unit = runBlocking {
        val invoker = RecordingInvoker(
            responses = mapOf(XrayMethod.FreePorts to """{"success":true,"data":{"ports":[10808,10809]}}"""),
        )
        adapter(invoker).freePort() shouldBe 10808
        invoker.calls shouldContainExactly listOf(XrayMethod.FreePorts)
        // One port per call: the runtime asks again when it needs another, rather than holding a
        // spare that was picked before it was needed.
        invoker.payloads.single() shouldBe """{"count":1}"""
    }

    /**
     * A `ports` field that is not a strict integer array is a failure, not a shorter list.
     *
     * `intOrNull` accepts the string `"10809"`, and a `mapNotNull` over the elements silently drops
     * one it cannot read - so a broken response used to look like fewer ports. There is no partial
     * success: one bad element makes the whole call a failure.
     */
    @Test
    fun `freePort refuses a ports field that is not a strict integer array`(): Unit = runBlocking {
        listOf(
            """{"success":true,"data":{}}""",
            """{"success":true,"data":{"ports":{}}}""",
            """{"success":true,"data":{"ports":10808}}""",
            """{"success":true,"data":{"ports":"10808"}}""",
            """{"success":true,"data":{"ports":[10808,{}]}}""",
            """{"success":true,"data":{"ports":[10808,null]}}""",
            """{"success":true,"data":{"ports":[10808,"10809"]}}""",
            """{"success":true,"data":{"ports":[10808,true]}}""",
            """{"success":true,"data":{"ports":[10808,10808.5]}}""",
            """{"success":true,"data":{"ports":[]}}""",
        ).forEach { response ->
            withClue(response) {
                val invoker = RecordingInvoker(responses = mapOf(XrayMethod.FreePorts to response))
                val failure = shouldThrow<XrayException> { adapter(invoker).freePort() }
                failure.category shouldBe XrayErrorCategory.LocalFailure
            }
        }
    }

    @Test
    fun `freePort refuses a port outside the valid range`(): Unit = runBlocking {
        listOf(0, 65536, -1, 65537, 100000).forEach { port ->
            withClue("port=$port") {
                val invoker = RecordingInvoker(
                    responses = mapOf(
                        XrayMethod.FreePorts to """{"success":true,"data":{"ports":[$port]}}""",
                    ),
                )
                val failure = shouldThrow<XrayException> { adapter(invoker).freePort() }
                failure.category shouldBe XrayErrorCategory.LocalFailure
            }
        }
    }

    @Test
    fun `freePort refuses a list whose fallback port is out of range`(): Unit = runBlocking {
        // The head being fine is not enough: the runtime would only discover the unusable fallback
        // at the moment it needed it.
        listOf(
            """{"success":true,"data":{"ports":[10808,0]}}""",
            """{"success":true,"data":{"ports":[10808,65536]}}""",
            """{"success":true,"data":{"ports":[10808,-1]}}""",
        ).forEach { response ->
            withClue(response) {
                val invoker = RecordingInvoker(responses = mapOf(XrayMethod.FreePorts to response))
                val failure = shouldThrow<XrayException> { adapter(invoker).freePort() }
                failure.category shouldBe XrayErrorCategory.LocalFailure
            }
        }
    }

    @Test
    fun `freePort fails as a local failure when no port is advertised`(): Unit = runBlocking {
        val invoker = RecordingInvoker(
            responses = mapOf(XrayMethod.FreePorts to """{"success":true,"data":{}}"""),
        )
        val failure = shouldThrow<XrayException> { adapter(invoker).freePort() }
        failure.category shouldBe XrayErrorCategory.LocalFailure
    }

    // --------------------------------------------------------- cancellation

    @Test
    fun `a cancelled call propagates as cancellation and not as a failure`(): Unit = runBlocking {
        val invoker = RecordingInvoker(
            throwOn = XrayMethod.State,
            throwWhat = { CancellationException("the caller went away") },
        )
        val thrown = runCatching { adapter(invoker).isRunning() }.exceptionOrNull()

        // Structured concurrency: a cancelled call is not a failure. Wrapping it would turn a
        // cancellation the caller deliberately triggered - a screen going away, a node switch
        // cancelling in-flight work - into an ordinary failure it is expected to report.
        (thrown is CancellationException) shouldBe true
        (thrown is XrayException) shouldBe false
    }

    // ------------------------------------------------------------ conversion

    @Test
    fun `convertShareLinks returns one opaque template per outbound`(): Unit = runBlocking {
        val invoker = RecordingInvoker(
            responses = mapOf(
                XrayMethod.ConvertShareLinks to """{"success":true,"data":{"outbounds":[""" +
                    """{"protocol":"vless","tag":"a"},{"protocol":"vless","tag":"b"}]}}""",
            ),
        )
        val nodes = adapter(invoker).convertShareLinks("vless://...\nvless://...")
        nodes.size shouldBe 2
        // The payload is readable in-module for contract assertions...
        nodes[0].outboundJson.contains("\"tag\":\"a\"") shouldBe true
        // ...but never through a string form, which is what would end up in a log or a report.
        nodes[0].toString() shouldBe "NodeTemplate(<redacted>)"
        nodes.toString().contains("\"tag\"") shouldBe false
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
    fun `convertShareLinks refuses a partly malformed outbound list`(): Unit = runBlocking {
        listOf(
            """{"success":true,"data":{"outbounds":[{"protocol":"vless"},42]}}""",
            """{"success":true,"data":{"outbounds":[42,{"protocol":"vless"}]}}""",
            """{"success":true,"data":{"outbounds":[{"protocol":"vless"},null]}}""",
            """{"success":true,"data":{"outbounds":["a string"]}}""",
        ).forEach { response ->
            withClue(response) {
                val invoker = RecordingInvoker(
                    responses = mapOf(XrayMethod.ConvertShareLinks to response),
                )
                val failure = shouldThrow<XrayException> {
                    adapter(invoker).convertShareLinks("vless://node")
                }
                // The whole response is unusable, not just the bad entry: a silently shortened list
                // would drop nodes for no stated reason, and the dropped ones could be the working
                // ones. There is no partial success.
                failure.category shouldBe XrayErrorCategory.LocalFailure
            }
        }
    }

    @Test
    fun `convertShareLinks refuses a missing or non-array outbounds field`(): Unit = runBlocking {
        listOf(
            """{"success":true,"data":{}}""",
            """{"success":true,"data":{"outbounds":{}}}""",
            """{"success":true,"data":{"outbounds":"vless://node"}}""",
        ).forEach { response ->
            withClue(response) {
                val invoker = RecordingInvoker(
                    responses = mapOf(XrayMethod.ConvertShareLinks to response),
                )
                val failure = shouldThrow<XrayException> {
                    adapter(invoker).convertShareLinks("vless://node")
                }
                failure.category shouldBe XrayErrorCategory.LocalFailure
            }
        }
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
