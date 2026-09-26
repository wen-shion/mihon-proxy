package eu.kanade.tachiyomi.network.proxy

import eu.kanade.tachiyomi.network.proxy.model.NodeTemplate
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * T-01: the generated config is the pinned, minimal, loopback-only shape.
 *
 * The assertions read the produced JSON rather than the builder's constants, so a drift from the
 * shape that was verified on device fails here instead of on a device.
 */
class XrayConfigBuilderTest {

    /**
     * The projection shape libXray actually emits (Phase 1B evidence): note `settings.address` at
     * the top level and the REALITY key being `password`, not `publicKey`. The real core rejects a
     * REALITY outbound without `password`, so the fixture has to match the projection to be
     * representative - see `XrayContractProbeTest` on device.
     */
    private val nodePayload =
        """{"protocol":"vless","tag":"tokyo-1","settings":{"address":"example.invalid","port":443,""" +
            """"id":"11111111-2222-3333-4444-555555555555","encryption":"none"},""" +
            """"streamSettings":{"network":"raw","security":"reality","realitySettings":{""" +
            """"serverName":"example.invalid","fingerprint":"chrome",""" +
            """"password":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","shortId":"0000"}}}"""

    private val template = NodeTemplate(nodePayload)

    private fun build(port: Int = 10808): JsonObject =
        Json.parseToJsonElement(XrayConfigBuilder.build(template, port)).jsonObject

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    @Test
    fun `logging is warning level with the access log explicitly off`() {
        val log = build()["log"]!!.jsonObject
        log.text("loglevel") shouldBe "warning"
        // Not merely "unset": an unset access log defaults to the console and writes every accepted
        // destination, plus the outbound tag, which is the provider's remark verbatim.
        log.text("access") shouldBe "none"
    }

    @Test
    fun `there is exactly one inbound and it is a loopback socks listener with auth and udp off`() {
        val inbounds = build(port = 10809)["inbounds"] as JsonArray
        inbounds.size shouldBe 1
        val inbound = inbounds[0].jsonObject
        inbound.text("protocol") shouldBe "socks"
        inbound.text("listen") shouldBe "127.0.0.1"
        (inbound["port"] as JsonPrimitive).intOrNull shouldBe 10809
        val settings = inbound["settings"]!!.jsonObject
        settings.text("auth") shouldBe "noauth"
        (settings["udp"] as JsonPrimitive).booleanOrNull shouldBe false
    }

    @Test
    fun `there is exactly one outbound and it is the selected node`() {
        val outbounds = build()["outbounds"] as JsonArray
        outbounds.size shouldBe 1
        val outbound = outbounds[0].jsonObject
        outbound.text("tag") shouldBe "tokyo-1"
        outbound.text("protocol") shouldBe "vless"
    }

    @Test
    fun `the config carries no tun, routing, dns or metrics section`() {
        val serialized = XrayConfigBuilder.build(template, 10808)
        serialized shouldNotContain "\"routing\""
        serialized shouldNotContain "\"dns\""
        serialized shouldNotContain "\"metrics\""
        serialized shouldNotContain "\"tun\""
        serialized shouldNotContain "dokodemo-door"
        serialized shouldNotContain "\"freedom\""
    }

    @Test
    fun `a whole conversion envelope is rejected instead of being used as one outbound`() {
        val failure = assertThrows<XrayException> {
            XrayConfigBuilder.build("""{"outbounds":[{"protocol":"vless"}]}""", 10808)
        }
        failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
    }

    @Test
    fun `a freedom outbound is rejected so a failed node cannot fall back to direct`() {
        val failure = assertThrows<XrayException> {
            XrayConfigBuilder.build("""{"protocol":"freedom"}""", 10808)
        }
        failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
    }

    /**
     * The outbound family is pinned to what the MVP verified on a device.
     *
     * Accepting "any protocol that is not freedom" would mean shipping a config the spikes never ran:
     * a protocol that fails to build, or one that routes somewhere we did not expect.
     */
    @Test
    fun `an outbound family other than vless is rejected`() {
        val families = listOf("vmess", "trojan", "shadowsocks", "socks", "http", "wireguard", "dns")
        families.forEach { protocol ->
            withClue(protocol) {
                val failure = assertThrows<XrayException> {
                    XrayConfigBuilder.build(
                        """{"protocol":"$protocol","settings":{},"streamSettings":{"security":"reality"}}""",
                        10808,
                    )
                }
                failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
            }
        }
    }

    @Test
    fun `a vless outbound that is not reality is rejected`() {
        listOf("tls", "none", "xtls").forEach { security ->
            withClue(security) {
                val failure = assertThrows<XrayException> {
                    XrayConfigBuilder.build(
                        """{"protocol":"vless","settings":{"address":"example.invalid","port":443},""" +
                            """"streamSettings":{"security":"$security"}}""",
                        10808,
                    )
                }
                failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
            }
        }
    }

    @Test
    fun `a vless outbound without stream settings is rejected`() {
        val failure = assertThrows<XrayException> {
            XrayConfigBuilder.build(
                """{"protocol":"vless","settings":{"address":"example.invalid","port":443}}""",
                10808,
            )
        }
        failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
    }

    @Test
    fun `a vless outbound without settings is rejected`() {
        val failure = assertThrows<XrayException> {
            XrayConfigBuilder.build("""{"protocol":"vless","streamSettings":{"security":"reality"}}""", 10808)
        }
        failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
    }

    @Test
    fun `an outbound whose declared port is out of range is rejected`() {
        listOf(0, 65536, -1).forEach { port ->
            withClue("port=$port") {
                val failure = assertThrows<XrayException> {
                    XrayConfigBuilder.build(
                        """{"protocol":"vless","settings":{"address":"example.invalid","port":$port},""" +
                            """"streamSettings":{"security":"reality"}}""",
                        10808,
                    )
                }
                failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
            }
        }
    }

    @Test
    fun `the flat settings port libXray emits is range-checked`() {
        XrayConfigBuilder.build(
            """{"protocol":"vless","settings":{"address":"example.invalid","port":443},""" +
                """"streamSettings":{"security":"reality"}}""",
            10808,
        )
        val failure = assertThrows<XrayException> {
            XrayConfigBuilder.build(
                """{"protocol":"vless","settings":{"address":"example.invalid","port":70000},""" +
                    """"streamSettings":{"security":"reality"}}""",
                10808,
            )
        }
        failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
    }

    /**
     * A port that is present has to be a JSON integer.
     *
     * The lenient read would take the string `"443"` as a port and let the payload through the range
     * check - a contract break wearing a value, which is exactly what the strict accessors exist to
     * stop. Absent is still fine: the core decides, as it does for everything else this layer does
     * not model.
     */
    @Test
    fun `an outbound whose port is present but not an integer is rejected`() {
        listOf("\"443\"", "443.5", "null", "true").forEach { raw ->
            withClue("port=$raw") {
                val failure = assertThrows<XrayException> {
                    XrayConfigBuilder.build(
                        """{"protocol":"vless","settings":{"address":"example.invalid","port":$raw},""" +
                            """"streamSettings":{"security":"reality"}}""",
                        10808,
                    )
                }
                failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
            }
        }
    }

    /**
     * The parser reads one shape only.
     *
     * A nested `vnext` list is not what libXray's converter emits, so nothing here parses it: the
     * outbound passes the structural gate and `testXray` is left to judge it. Carrying a
     * compatibility branch for a shape the pinned AAR never produces would mean maintaining a second
     * parser for an unverified schema - and a drift would then be absorbed silently instead of
     * failing. `XrayContractProbeTest` asserts the shape the real converter emits, on the device.
     */
    @Test
    fun `a nested vnext port is not parsed`() {
        XrayConfigBuilder.build(
            """{"protocol":"vless","settings":{"vnext":[{"address":"a","port":70000}]},""" +
                """"streamSettings":{"security":"reality"}}""",
            10808,
        )
    }

    @Test
    fun `a payload without a protocol is rejected`() {
        val failure = assertThrows<XrayException> { XrayConfigBuilder.build("""{"tag":"x"}""", 10808) }
        failure.category shouldBe XrayErrorCategory.ConfigValidationFailed
    }

    @Test
    fun `a payload that is not json is rejected`() {
        val failure = assertThrows<XrayException> { XrayConfigBuilder.build("not json", 10808) }
        failure.category shouldBe XrayErrorCategory.MalformedJson
    }

    @Test
    fun `an out of range port is rejected`() {
        assertThrows<IllegalArgumentException> { XrayConfigBuilder.build(template, 0) }
        assertThrows<IllegalArgumentException> { XrayConfigBuilder.build(template, 65536) }
    }

    @Test
    fun `the node payload is carried through verbatim`() {
        XrayConfigBuilder.build(template, 10808) shouldContain "11111111-2222-3333-4444-555555555555"
    }
}
