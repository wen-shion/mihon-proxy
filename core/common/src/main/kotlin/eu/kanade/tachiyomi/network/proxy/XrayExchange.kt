package eu.kanade.tachiyomi.network.proxy

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull

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
                // The parser message quotes the offending input, which for a config payload is
                // credential material - so only the type survives.
                throw XrayException(XrayErrorCategory.MalformedJson, error.sanitisedCause())
            }
            put("payload", payload)
        }
    }.toString()

    /**
     * Returns the `data` object of a successful response.
     *
     * The shape extraction is total: nothing a malformed or contract-violating envelope can throw -
     * a parser error, a `ClassCastException`, an NPE off a null field - reaches the caller. The two
     * outcomes are deliberately different:
     *
     * * **Not JSON at all** is [XrayErrorCategory.MalformedJson] - the core did not answer in the
     *   protocol.
     * * **Valid JSON that violates the pinned envelope** is [XrayErrorCategory.LocalFailure]. That
     *   covers `success` being anything but a boolean, `error` not being a string on a failure
     *   envelope, `data` being neither an object nor absent, and a missing response entirely. None of
     *   these are failures the core *reported*; they are disagreements about the contract, and
     *   classifying them as a remote failure would invite the runtime to retry or to fall back.
     *
     * Neither the raw response nor the native error text is ever attached to the exception.
     */
    fun data(response: String?): JsonObject {
        val parsed = response?.let { text ->
            try {
                json.parseToJsonElement(text)
            } catch (error: SerializationException) {
                throw XrayException(XrayErrorCategory.MalformedJson, error.sanitisedCause())
            }
        }
        val envelope = parsed as? JsonObject ?: throw XrayException(XrayErrorCategory.LocalFailure)

        // `asStrictBoolean` on purpose: `success` must be a JSON boolean. An object, an array, the
        // literal null, an absent key and the *string* "true" are all contract violations, and none
        // of them may be read as "not succeeded" - that would let a broken envelope look like a
        // normal failure the runtime is expected to act on.
        val succeeded = envelope["success"].asStrictBoolean()
            ?: throw XrayException(XrayErrorCategory.LocalFailure)

        if (!succeeded) {
            throw XrayException(categorise(errorText(envelope["error"])))
        }

        return when (val data = envelope["data"]) {
            null, is JsonNull -> JsonObject(emptyMap())
            is JsonObject -> data
            else -> throw XrayException(XrayErrorCategory.LocalFailure)
        }
    }

    /**
     * The `error` field of a failure envelope, which the pinned contract emits as a string.
     *
     * Anything else - absent, an object, an array, a number, the literal null - yields null, which
     * [categorise] maps to [XrayErrorCategory.LocalFailure]. The point is that a malformed envelope
     * cannot be talked into a *specific* category by shaping its `error` field.
     */
    private fun errorText(element: JsonElement?): String? =
        (element as? JsonPrimitive)?.takeIf { it.isString }?.content

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
//
// All of them are strict: a field the contract types as a string, a boolean or an integer is only
// readable as that type, and never as whatever a primitive's text content happens to parse into.
// The callers turn the null into a classified failure, which is the point - a contract break must
// not be able to look like a normal reading.

/**
 * A JSON string, and nothing that merely reads as one.
 *
 * `contentOrNull` alone would accept a number or a boolean, so a field the contract defines as a
 * string would take a wrong type as a value.
 */
internal fun JsonObject.strictString(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A field the contract defines as a JSON boolean. The caller turns a null into a failure. */
internal fun JsonObject.boolean(key: String): Boolean? = this[key].asStrictBoolean()

/**
 * An array of JSON integers, and nothing that merely parses as one.
 *
 * `intOrNull` alone accepts the *string* `"10809"`, and a `mapNotNull` over the elements would
 * silently drop one it could not read - both of which let a contract break look like a shorter
 * answer. A null here means the field is not a usable integer array, and there is no partial list.
 */
internal fun JsonObject.strictInts(key: String): List<Int>? {
    val array = this[key] as? JsonArray ?: return null
    val values = ArrayList<Int>(array.size)
    for (element in array) {
        values += element.asStrictInt() ?: return null
    }
    return values
}

private fun JsonElement?.asStrictBoolean(): Boolean? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonElement?.asStrictInt(): Int? =
    (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
