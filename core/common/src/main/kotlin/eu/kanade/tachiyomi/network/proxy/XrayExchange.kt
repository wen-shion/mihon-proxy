package eu.kanade.tachiyomi.network.proxy

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * The libXray `invoke` contract: one JSON envelope in, one JSON envelope out.
 *
 * Mirrors `invoke.go` / `invoke_model.go` at the pinned revision, and keeps every raw response string
 * inside this object so the rest of the proxy layer only ever sees classified results.
 */
internal object XrayExchange {

    /** `LibXrayInvokeRequest.APIVersion` at the pinned revision. */
    const val API_VERSION = 3

    /** libXray rejects requests larger than this (`maxInvokeJSONSizeMiB`). */
    const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024

    // Field names of the request payloads and response data, as they appear on the wire.
    const val KEY_XRAY_JSON = "xrayJson"
    const val KEY_TEXT = "text"
    const val KEY_AGE = "age"
    const val KEY_SECRET_KEY = "secretKey"
    const val KEY_OUTBOUNDS = "outbounds"
    const val KEY_VERSION = "version"
    const val KEY_PORTS = "ports"
    const val KEY_RUNNING = "running"

    private val json = Json { ignoreUnknownKeys = true }

    /** `{"apiVersion":3,"method":"...","payload":{...}}`, with `payload` omitted when null. */
    fun envelope(method: String, payloadJson: String?): String = buildJsonObject {
        put("apiVersion", JsonPrimitive(API_VERSION))
        put("method", JsonPrimitive(method))
        if (payloadJson != null) {
            val payload = try {
                json.parseToJsonElement(payloadJson)
            } catch (error: SerializationException) {
                throw XrayException(XrayErrorCategory.MalformedJson, error)
            }
            put("payload", payload)
        }
    }.toString()

    /**
     * Returns the `data` object of a successful response.
     *
     * Throws [XrayException] when the envelope reports a failure, when it cannot be parsed at all, or
     * when a success carries no data.
     */
    fun data(response: String?): JsonObject {
        val envelope = try {
            response?.let { json.parseToJsonElement(it) }
        } catch (error: SerializationException) {
            throw XrayException(XrayErrorCategory.MalformedJson, error)
        } as? JsonObject ?: throw XrayException(XrayErrorCategory.LocalFailure)

        val succeeded = envelope["success"]?.jsonPrimitive?.booleanOrNull ?: false
        if (!succeeded) {
            throw XrayException(categorise(envelope["error"]?.jsonPrimitive?.contentOrNull))
        }
        return envelope["data"] as? JsonObject ?: JsonObject(emptyMap())
    }

    /**
     * Turns a native error string into a category. The text itself is never propagated: it can name
     * the endpoint, and nothing above this layer needs it.
     */
    fun categorise(errorText: String?): XrayErrorCategory {
        val text = errorText?.lowercase() ?: return XrayErrorCategory.LocalFailure
        return when {
            text.contains("unknown method") -> XrayErrorCategory.UnknownMethod
            text.contains("unsupported apiversion") -> XrayErrorCategory.UnknownMethod
            text.contains("unsupported share format") -> XrayErrorCategory.ShareLinkUnsupportedFormat
            text.contains("size limit") -> XrayErrorCategory.PayloadTooLarge
            text.contains("invalid character") -> XrayErrorCategory.MalformedJson
            text.contains("failed to parse json config") ||
                text.contains("failed to build inbound") ||
                text.contains("failed to build outbound") ||
                text.contains("unknown transport protocol") ||
                text.contains("unrecognized protocol") -> XrayErrorCategory.ConfigValidationFailed
            text.contains("address already in use") -> XrayErrorCategory.LocalPortInUse
            text.contains("already running") -> XrayErrorCategory.AlreadyRunning
            text.contains("i/o timeout") || text.contains("context deadline") ||
                text.contains("timeout") -> XrayErrorCategory.RemoteTimeout
            text.contains("connection refused") -> XrayErrorCategory.RemoteConnectionRefused
            text.contains("reality") && (text.contains("fail") || text.contains("invalid")) ->
                XrayErrorCategory.RealityHandshakeFailed
            text.contains("handshake") || text.contains("tls") -> XrayErrorCategory.TlsHandshakeFailed
            text.contains("eof") -> XrayErrorCategory.RemoteEof
            text.contains("dial") -> XrayErrorCategory.RemoteDialFailed
            else -> XrayErrorCategory.Other
        }
    }
}

// Top-level rather than members of [XrayExchange] so the rest of the package can use them as
// extensions without importing an object member.

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

internal fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.ints(key: String): List<Int> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
