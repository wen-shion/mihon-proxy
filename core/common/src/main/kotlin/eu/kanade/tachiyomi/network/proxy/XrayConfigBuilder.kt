package eu.kanade.tachiyomi.network.proxy

import eu.kanade.tachiyomi.network.proxy.model.NodeTemplate
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Builds the runtime config handed to libXray.
 *
 * The shape is the one verified on device in Phase 1: one loopback SOCKS inbound with authentication
 * off and UDP off, and exactly one outbound - the selected node. There is no TUN inbound (this app
 * never takes over the device's traffic), no routing rules, no DNS hijack and no metrics listener,
 * and no `freedom` outbound, so a failed node can never silently fall back to a direct connection.
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
    const val LOG_LEVEL = "warning"
    const val ACCESS_LOG = "none"

    private const val OUTBOUND_PROTOCOL_FREEDOM = "freedom"

    private val json = Json { ignoreUnknownKeys = true }

    /** Builds the config for [template] bound to loopback [port]. */
    fun build(template: NodeTemplate, port: Int): String = build(template.outboundJson, port)

    /**
     * Same, from the raw outbound JSON. Exposed so the mapping layer can be tested without building a
     * [NodeTemplate] first.
     */
    fun build(outboundJson: String, port: Int): String {
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
                                    put("auth", JsonPrimitive("noauth"))
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
     * The payload must be a single outbound object that libXray would build. Rejecting anything else
     * here - rather than letting the core discover it - is what keeps a malformed or empty payload
     * from reaching `runXray`, which would otherwise succeed and start a core with no usable outbound.
     */
    internal fun parseOutbound(outboundJson: String): JsonObject {
        val element = try {
            json.parseToJsonElement(outboundJson)
        } catch (error: SerializationException) {
            throw XrayException(XrayErrorCategory.MalformedJson, error)
        }
        val outbound = element as? JsonObject ?: throw XrayException(XrayErrorCategory.ConfigValidationFailed)

        // A `{"outbounds":[...]}` envelope means the caller passed a whole conversion result where a
        // single node was expected; the core would accept the shape but the wrong node could win.
        if (outbound.containsKey("outbounds")) {
            throw XrayException(XrayErrorCategory.ConfigValidationFailed)
        }
        val protocol = (outbound["protocol"] as? JsonPrimitive)?.contentOrNull
        if (protocol.isNullOrBlank() || protocol == OUTBOUND_PROTOCOL_FREEDOM) {
            throw XrayException(XrayErrorCategory.ConfigValidationFailed)
        }
        return outbound
    }
}
