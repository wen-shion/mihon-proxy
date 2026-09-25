package eu.kanade.tachiyomi.network.proxy

import eu.kanade.tachiyomi.network.proxy.model.NodeTemplate
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

    private val nodePayload =
        """{"protocol":"vless","tag":"tokyo-1","settings":{"address":"example.invalid","port":443,""" +
            """"id":"11111111-2222-3333-4444-555555555555","encryption":"none"},""" +
            """"streamSettings":{"security":"reality","realitySettings":{"serverName":"example.invalid"}}}"""

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
