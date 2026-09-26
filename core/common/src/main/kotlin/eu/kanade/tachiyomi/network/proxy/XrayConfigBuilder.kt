package eu.kanade.tachiyomi.network.proxy

import eu.kanade.tachiyomi.network.proxy.model.NodeTemplate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Builds the runtime config handed to libXray.
 *
 * The shape is the one verified on device in Phase 1: one loopback SOCKS inbound with authentication
 * off and UDP off, and exactly one outbound - the selected node. There is no TUN inbound (this app
 * never takes over the device's traffic), no routing rules, no DNS hijack and no metrics listener,
 * and no `freedom` outbound, so a failed node can never silently fall back to a direct connection.
 *
 * **The MVP supports exactly one outbound family: VLESS over REALITY.** That is narrower than what
 * Xray can do, and deliberately so - every protocol this layer accepts but that was never exercised
 * on a device is a direct-connection path waiting to be discovered. Widening it is a change to the
 * constants below plus a spike, not a config a caller can hand in.
 *
 * Logging is deliberately the tightest setting that still reports errors:
 * `loglevel: "warning"` with **`access: "none"`**. Leaving `access` unset is not equivalent - Xray
 * then defaults the access log to the console, which writes every accepted destination to logcat
 * *and* the outbound tag, which is the provider's remark verbatim.
 */
object XrayConfigBuilder {

    const val LOOPBACK_HOST = "127.0.0.1"
    const val INBOUND_TAG = "socks-in"
    const val INBOUND_PROTOCOL = "socks"
    const val INBOUND_AUTH = "noauth"
    const val LOG_LEVEL = "warning"
    const val ACCESS_LOG = "none"

    /** The only outbound family the MVP will start. See the class comment. */
    const val OUTBOUND_PROTOCOL = "vless"
    const val OUTBOUND_SECURITY = "reality"

    /**
     * Top-level sections that must never appear in a config we start.
     *
     * A `routing` rule can send part of the traffic around the node, a `dns` section can resolve
     * names outside the tunnel, `metrics` adds a second listener, and the observatory/probe and
     * reverse sections generate outbound traffic of their own. The MVP needs none of them, and each
     * is a way for traffic to leave without passing through the selected node.
     */
    val FORBIDDEN_SECTIONS = listOf(
        "routing",
        "dns",
        "metrics",
        "observatory",
        "burstObservatory",
        "reverse",
    )

    private val json = Json { ignoreUnknownKeys = true }

    /** Builds the config for [template] bound to loopback [port]. */
    fun build(template: NodeTemplate, port: Int): String = build(template.outboundJson, port)

    /**
     * Same, from the raw outbound JSON - **internal**, on purpose.
     *
     * A node's outbound is credential material, and the same reasoning that keeps it inside a
     * [NodeTemplate] keeps this overload out of the public API: production code outside this module
     * builds a config from the opaque template and cannot hand in raw credential JSON. Tests, the
     * mapper (PR-D) and the on-device contract probe live here, so they keep access.
     */
    internal fun build(outboundJson: String, port: Int): String {
        require(port in 1..65535) { "port out of range: $port" }
        val outbound = parseOutbound(outboundJson)

        return buildJsonObject {
            put(
                "log",
                buildJsonObject {
                    put("loglevel", JsonPrimitive(LOG_LEVEL))
                    put("access", JsonPrimitive(ACCESS_LOG))
                },
            )
            put(
                "inbounds",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("tag", JsonPrimitive(INBOUND_TAG))
                            put("listen", JsonPrimitive(LOOPBACK_HOST))
                            put("port", JsonPrimitive(port))
                            put("protocol", JsonPrimitive(INBOUND_PROTOCOL))
                            put(
                                "settings",
                                buildJsonObject {
                                    put("auth", JsonPrimitive(INBOUND_AUTH))
                                    put("udp", JsonPrimitive(false))
                                },
                            )
                        },
                    )
                },
            )
            put("outbounds", buildJsonArray { add(outbound) })
        }.toString()
    }

    /**
     * Validates the outbound payload and returns it unchanged.
     *
     * The payload must be a single VLESS-over-REALITY outbound object that libXray would build.
     * Rejecting anything else here - rather than letting the core discover it - is what keeps a
     * malformed, empty or unsupported payload from reaching `runXray`, which would otherwise succeed
     * and start a core with no usable outbound.
     */
    internal fun parseOutbound(outboundJson: String): JsonObject {
        val element = try {
            json.parseToJsonElement(outboundJson)
        } catch (error: SerializationException) {
            // The parser message quotes the input, which here is the node's credentials, so only the
            // exception type survives.
            throw XrayException(XrayErrorCategory.MalformedJson, error.sanitisedCause())
        }
        val outbound = element as? JsonObject ?: throw invalid()

        // A `{"outbounds":[...]}` envelope means the caller passed a whole conversion result where a
        // single node was expected; the core would accept the shape but the wrong node could win.
        if (outbound.containsKey("outbounds")) throw invalid()

        if (outbound.strictString("protocol") != OUTBOUND_PROTOCOL) throw invalid()
        val settings = outbound["settings"] as? JsonObject ?: throw invalid()

        val streamSettings = outbound["streamSettings"] as? JsonObject ?: throw invalid()
        if (streamSettings.strictString("security") != OUTBOUND_SECURITY) throw invalid()

        // Only the flat `settings.port` that libXray actually emits is range-checked, and only when
        // it is there. A present port has to be a JSON integer: the lenient read would take the
        // string "443" as a port, which is a contract break wearing a value. There is deliberately no
        // compatibility branch for any other shape: the AAR SHA is pinned, an upgrade goes through a
        // contract review, and a schema drift has to fail a test rather than be absorbed by a parser
        // written for a shape nobody has run. Whether the outbound is legal at all is settled by the
        // unavoidable `testXray`.
        settings["port"]?.let { raw ->
            val nodePort = raw.asStrictInt() ?: throw invalid()
            if (nodePort !in 1..65535) throw invalid()
        }

        return outbound
    }

    private fun invalid(): Nothing = throw XrayException(XrayErrorCategory.ConfigValidationFailed)
}
