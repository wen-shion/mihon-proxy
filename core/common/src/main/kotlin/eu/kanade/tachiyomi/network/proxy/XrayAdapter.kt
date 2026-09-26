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
import kotlin.coroutines.cancellation.CancellationException

/**
 * The proxy layer's only view of the native core.
 *
 * Everything above this interface works with categories, never with native strings, and everything
 * below it is a single blocking call into `libgojni.so`. Three properties are enforced here rather
 * than left to callers:
 *
 * * **No config reaches the core without passing [assertStartable].** `runXray` accepts
 *   `{"xrayJson":"{}"}` and reports success, leaving a running core with no usable outbound; the
 *   structural check is the only thing that catches that, so [validate] and [start] each apply it.
 * * **Nothing runs before the core has tested it.** `start` calls `testXray` and only calls `runXray`
 *   once that succeeded. The two checks are not redundant: the structural gate is what the core does
 *   *not* do (an outbound-less config it accepts), and `testXray` is what the structural gate cannot
 *   do (a config that is well-formed but that the core still refuses - a REALITY outbound with no
 *   secret, for instance). A caller never has to remember to call [validate] first.
 * * **Failures become [XrayException].** A Java-side failure (JNI, class init, out of memory) is
 *   reported as [XrayErrorCategory.LocalFailure], and no failure of any kind carries native text or
 *   the original throwable upwards - see [SanitisedCause].
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

    /** Gate 1 + gate 2: rejects, then `testXray`, then - only if that succeeded - `runXray`. */
    suspend fun start(configJson: String)

    suspend fun stop()

    suspend fun isRunning(): Boolean

    /**
     * Converts share links (or an Age-encrypted subscription) into one opaque node per entry.
     *
     * The result is [NodeTemplate], not `String`: the payload is credential material, and a plain
     * string is what ends up in a log line, a `toString()` or a crash report. Only the config builder
     * and the encrypted snapshot store read [NodeTemplate.outboundJson], and both live in this module.
     *
     * @param text the provider response; must stay within libXray's 16 MiB invoke limit.
     * @param ageSecretKey the Age key when the subscription is encrypted, otherwise null.
     */
    suspend fun convertShareLinks(text: String, ageSecretKey: String? = null): List<NodeTemplate>
}

internal class RealXrayAdapter(
    private val invoker: LibXrayInvoker = RealLibXrayInvoker(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : XrayAdapter {

    override suspend fun version(): String = withContext(io) {
        request(XrayMethod.Version, null).strictString(XrayExchange.KEY_VERSION)
            ?: throw XrayException(XrayErrorCategory.LocalFailure)
    }

    /**
     * A port that was free when the core picked it. May be taken by the time it is used.
     *
     * The whole `ports` list has to be usable, not just its head: a port outside the valid range is
     * a contract break, and accepting a list because the first entry looked fine would hand the
     * runtime a fallback it cannot use.
     */
    override suspend fun freePort(): Int = withContext(io) {
        val ports = request(XrayMethod.FreePorts, FREEPORTS_PAYLOAD).strictInts(XrayExchange.KEY_PORTS)
            ?: throw XrayException(XrayErrorCategory.LocalFailure)
        ports.forEach { port ->
            if (port !in 1..65535) throw XrayException(XrayErrorCategory.LocalFailure)
        }
        ports.firstOrNull() ?: throw XrayException(XrayErrorCategory.LocalFailure)
    }

    override suspend fun validate(configJson: String): Unit = withContext(io) {
        assertStartable(configJson)
        request(XrayMethod.Test, configPayload(configJson))
        Unit
    }

    /**
     * Gate 1, then gate 2, then run.
     *
     * `testXray` is asked first and `runXray` is only reached if it succeeded. Skipping it would mean
     * a config the core refuses starts a core that then fails at request time, which surfaces to the
     * user as "the proxy is on but nothing loads" instead of a classified failure.
     */
    override suspend fun start(configJson: String): Unit = withContext(io) {
        assertStartable(configJson)
        request(XrayMethod.Test, configPayload(configJson))
        request(XrayMethod.Run, configPayload(configJson))
        Unit
    }

    override suspend fun stop(): Unit = withContext(io) {
        request(XrayMethod.Stop, null)
        Unit
    }

    /**
     * Whether a core is running in this process.
     *
     * A missing or non-boolean `running` field is a [XrayErrorCategory.LocalFailure], not `false`.
     * Defaulting to "not running" would let a contract break masquerade as a stopped core, and the
     * runtime treats "stopped" as a normal, expected state - so the failure would be silent.
     */
    override suspend fun isRunning(): Boolean = withContext(io) {
        request(XrayMethod.State, null).boolean(XrayExchange.KEY_RUNNING)
            ?: throw XrayException(XrayErrorCategory.LocalFailure)
    }

    override suspend fun convertShareLinks(text: String, ageSecretKey: String?): List<NodeTemplate> =
        withContext(io) {
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

            val outbounds = request(XrayMethod.ConvertShareLinks, payload)[XrayExchange.KEY_OUTBOUNDS]
                as? JsonArray ?: throw XrayException(XrayErrorCategory.LocalFailure)
            // Every entry has to be an outbound, or none of them are usable: a partially converted
            // subscription would offer the user a node list that is missing entries for no stated
            // reason, and the missing ones could be exactly the nodes that work.
            outbounds.map { element ->
                val outbound = element as? JsonObject ?: throw XrayException(XrayErrorCategory.LocalFailure)
                NodeTemplate(outbound.toString())
            }
        }

    /**
     * The structural gate, applied by both entry points.
     *
     * It is not a substitute for the core's own validation - it is the check the core does *not* do:
     * an empty or outbound-less config is accepted by `runXray` and produces a core that cannot route.
     * It locks the whole production shape, so a caller cannot hand in a config that is well-formed but
     * weaker than the one Phase 1 verified: logging that would leak, a listener reachable off-device,
     * a second inbound, a routing or DNS section that would bypass the node, or an outbound family the
     * MVP never exercised.
     */
    internal fun assertStartable(configJson: String) {
        val config = try {
            Json.parseToJsonElement(configJson)
        } catch (error: SerializationException) {
            throw XrayException(XrayErrorCategory.MalformedJson, error.sanitisedCause())
        } as? JsonObject ?: throw invalid()

        // Logging: the pinned level, and the access log explicitly off. Unset is not equivalent - it
        // defaults to the console and writes every accepted destination plus the outbound tag.
        val log = config["log"] as? JsonObject ?: throw invalid()
        if (log.strictString("loglevel") != XrayConfigBuilder.LOG_LEVEL) throw invalid()
        if (log.strictString("access") != XrayConfigBuilder.ACCESS_LOG) throw invalid()

        // No section that could route, resolve or export traffic around the selected node.
        XrayConfigBuilder.FORBIDDEN_SECTIONS.forEach { section ->
            if (config.containsKey(section)) throw invalid()
        }

        // Exactly one inbound, and it is the loopback SOCKS listener with auth and UDP off. Requiring
        // the protocol is what excludes a TUN or dokodemo-door inbound.
        val inbounds = config["inbounds"] as? JsonArray ?: throw invalid()
        if (inbounds.size != 1) throw invalid()
        val inbound = inbounds[0] as? JsonObject ?: throw invalid()
        if (inbound.strictString("protocol") != XrayConfigBuilder.INBOUND_PROTOCOL) throw invalid()
        if (inbound.strictString("listen") != XrayConfigBuilder.LOOPBACK_HOST) throw invalid()
        val inboundPort = inbound.strictInt("port")
        if (inboundPort == null || inboundPort !in 1..65535) throw invalid()
        val inboundSettings = inbound["settings"] as? JsonObject ?: throw invalid()
        if (inboundSettings.strictString("auth") != XrayConfigBuilder.INBOUND_AUTH) throw invalid()
        if (inboundSettings.boolean("udp") != false) throw invalid()

        // Exactly one outbound, VLESS over REALITY. No `freedom`, so a failed node cannot fall back
        // to a direct connection, and no protocol the MVP has not verified on a device.
        val outbounds = config["outbounds"] as? JsonArray ?: throw invalid()
        if (outbounds.size != 1) throw invalid()
        val outbound = outbounds[0] as? JsonObject ?: throw invalid()
        if (outbound.strictString("protocol") != XrayConfigBuilder.OUTBOUND_PROTOCOL) throw invalid()
        val streamSettings = outbound["streamSettings"] as? JsonObject ?: throw invalid()
        if (streamSettings.strictString("security") != XrayConfigBuilder.OUTBOUND_SECURITY) throw invalid()
    }

    private fun request(method: XrayMethod, payloadJson: String?): JsonObject {
        val response = try {
            invoker.invoke(method, payloadJson)
        } catch (error: XrayException) {
            throw error
        } catch (error: CancellationException) {
            // Structured concurrency: a cancelled call is not a failure. Wrapping it would turn a
            // cancellation the caller deliberately triggered - a screen going away, a node switch
            // cancelling in-flight work - into an ordinary failure it is expected to report.
            throw error
        } catch (error: Throwable) {
            // Deliberately not `error` as the cause: a JNI or parser failure can quote the endpoint or
            // the config, and a cause chain is printed verbatim by crash reporters.
            throw XrayException(XrayErrorCategory.LocalFailure, error.sanitisedCause())
        }
        return XrayExchange.data(response)
    }

    private fun configPayload(configJson: String): String =
        buildJsonObject { put(XrayExchange.KEY_XRAY_JSON, JsonPrimitive(configJson)) }.toString()

    private fun invalid(): Nothing = throw XrayException(XrayErrorCategory.ConfigValidationFailed)

    private companion object {
        /**
         * One port per call.
         *
         * The runtime asks again when it needs another one, rather than holding a second port that
         * was picked before it was needed. A spare is only useful at the moment of a collision, and
         * by then the core can hand out a fresh one anyway.
         */
        const val FREEPORTS_PAYLOAD = """{"count":1}"""
    }
}
