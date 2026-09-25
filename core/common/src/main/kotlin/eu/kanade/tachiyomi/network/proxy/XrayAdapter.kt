package eu.kanade.tachiyomi.network.proxy

import eu.kanade.tachiyomi.network.proxy.model.NodeTemplate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * The proxy layer's only view of the native core.
 *
 * Everything above this interface works with categories, never with native strings, and everything
 * below it is a single blocking call into `libgojni.so`. Two properties are enforced here rather than
 * left to callers:
 *
 * * **No config reaches the core without passing [assertStartable].** `runXray` accepts `{"xrayJson":"{}"}`
 *   and reports success, leaving a running core with no usable outbound; the structural check is the
 *   only thing that catches that, so both [validate] and [start] apply it independently.
 * * **Failures become [XrayException].** A Java-side failure (JNI, class init, out of memory) is
 *   reported as [XrayErrorCategory.LocalFailure] so callers have one error type to handle.
 *
 * Functions are `@Throws`-free because the exception they raise is our own type, and `suspend` because
 * every call blocks on the native side and must not run on the main thread.
 */
interface XrayAdapter {

    suspend fun version(): String

    /** A port that was free when the core picked it. May be taken by the time it is used. */
    suspend fun freePort(): Int

    /** Gate 1: rejects a config that must not be started, then asks the core to validate it. */
    suspend fun validate(configJson: String)

    /** Gate 2: rejects the same way, then starts the core. Returns once the listener is up. */
    suspend fun start(configJson: String)

    suspend fun stop()

    suspend fun isRunning(): Boolean

    /**
     * Converts share links (or an Age-encrypted subscription) into one opaque outbound JSON per node.
     *
     * @param text the provider response; must stay within libXray's 16 MiB invoke limit.
     * @param ageSecretKey the Age key when the subscription is encrypted, otherwise null.
     */
    suspend fun convertShareLinks(text: String, ageSecretKey: String? = null): List<String>
}

internal class RealXrayAdapter(
    private val invoker: LibXrayInvoker = RealLibXrayInvoker(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : XrayAdapter {

    override suspend fun version(): String = withContext(io) {
        request(XrayMethod.Version, null).string(XrayExchange.KEY_VERSION)
            ?: throw XrayException(XrayErrorCategory.LocalFailure)
    }

    override suspend fun freePort(): Int = withContext(io) {
        request(XrayMethod.FreePorts, FREEPORTS_PAYLOAD).ints(XrayExchange.KEY_PORTS).firstOrNull()
            ?: throw XrayException(XrayErrorCategory.LocalFailure)
    }

    override suspend fun validate(configJson: String): Unit = withContext(io) {
        assertStartable(configJson)
        request(XrayMethod.Test, configPayload(configJson))
        Unit
    }

    override suspend fun start(configJson: String): Unit = withContext(io) {
        assertStartable(configJson)
        request(XrayMethod.Run, configPayload(configJson))
        Unit
    }

    override suspend fun stop(): Unit = withContext(io) {
        request(XrayMethod.Stop, null)
        Unit
    }

    override suspend fun isRunning(): Boolean = withContext(io) {
        request(XrayMethod.State, null).boolean(XrayExchange.KEY_RUNNING) ?: false
    }

    override suspend fun convertShareLinks(text: String, ageSecretKey: String?): List<String> = withContext(io) {
        if (text.toByteArray(Charsets.UTF_8).size > XrayExchange.MAX_PAYLOAD_BYTES) {
            throw XrayException(XrayErrorCategory.PayloadTooLarge)
        }
        val payload = buildJsonObject {
            put(XrayExchange.KEY_TEXT, JsonPrimitive(text))
            if (!ageSecretKey.isNullOrEmpty()) {
                put(
                    XrayExchange.KEY_AGE,
                    buildJsonObject { put(XrayExchange.KEY_SECRET_KEY, JsonPrimitive(ageSecretKey)) },
                )
            }
        }.toString()

        val outbounds = request(XrayMethod.ConvertShareLinks, payload)[XrayExchange.KEY_OUTBOUNDS] as? JsonArray
            ?: throw XrayException(XrayErrorCategory.LocalFailure)
        outbounds.mapNotNull { it as? JsonObject }.map { it.toString() }
    }

    /**
     * The structural gate, applied by both entry points.
     *
     * It is not a substitute for the core's own validation - it is the check the core does *not* do:
     * an empty or outbound-less config is accepted by `runXray` and produces a core that cannot route.
     */
    internal fun assertStartable(configJson: String) {
        val element = try {
            Json.parseToJsonElement(configJson)
        } catch (error: SerializationException) {
            throw XrayException(XrayErrorCategory.MalformedJson, error)
        }
        val config = element as? JsonObject ?: throw XrayException(XrayErrorCategory.ConfigValidationFailed)

        val access = (config["log"] as? JsonObject)?.string("access")
        if (access != XrayConfigBuilder.ACCESS_LOG) {
            throw XrayException(XrayErrorCategory.ConfigValidationFailed)
        }

        val inbounds = config["inbounds"] as? JsonArray
        if (inbounds == null || inbounds.size != 1) {
            throw XrayException(XrayErrorCategory.ConfigValidationFailed)
        }
        val inbound = inbounds[0] as? JsonObject ?: throw XrayException(XrayErrorCategory.ConfigValidationFailed)
        if (inbound.string("protocol") != XrayConfigBuilder.INBOUND_PROTOCOL ||
            inbound.string("listen") != XrayConfigBuilder.LOOPBACK_HOST
        ) {
            throw XrayException(XrayErrorCategory.ConfigValidationFailed)
        }

        val outbounds = config["outbounds"] as? JsonArray
        if (outbounds == null || outbounds.size != 1) {
            throw XrayException(XrayErrorCategory.ConfigValidationFailed)
        }
        val outbound = outbounds[0] as? JsonObject ?: throw XrayException(XrayErrorCategory.ConfigValidationFailed)
        if (outbound.string("protocol").isNullOrBlank()) {
            throw XrayException(XrayErrorCategory.ConfigValidationFailed)
        }
    }

    private fun request(method: XrayMethod, payloadJson: String?): JsonObject {
        val response = try {
            invoker.invoke(method, payloadJson)
        } catch (error: XrayException) {
            throw error
        } catch (error: Throwable) {
            throw XrayException(XrayErrorCategory.LocalFailure, error)
        }
        return XrayExchange.data(response)
    }

    private fun configPayload(configJson: String): String =
        buildJsonObject { put(XrayExchange.KEY_XRAY_JSON, JsonPrimitive(configJson)) }.toString()

    private companion object {
        /** Two ports so the runtime has a fallback without a second round trip. */
        const val FREEPORTS_PAYLOAD = """{"count":2}"""
    }
}

/** Declared so the builder can be used from tests and from the runtime through one type. */
internal fun RealXrayAdapter.buildConfig(template: NodeTemplate, port: Int): String =
    XrayConfigBuilder.build(template, port)
