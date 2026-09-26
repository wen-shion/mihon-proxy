package eu.kanade.tachiyomi.network.proxy.model

import eu.kanade.tachiyomi.network.proxy.Sensitive

/**
 * One node's outbound, as produced by libXray, held opaquely.
 *
 * The payload is a single libXray outbound object (for example
 * `{"protocol":"vless","tag":"...","settings":{...},"streamSettings":{...}}`) and contains the
 * credentials: the UUID, the REALITY public key and the short id. Nothing outside the config builder
 * and the encrypted snapshot store reads it, and no accessor exposes its fields, so callers cannot
 * start parsing it - re-parsing would be a second implementation of libXray's own format, which is
 * exactly what we must not maintain.
 *
 * [toString] is overridden because a data class or a log statement would otherwise print the
 * credentials.
 */
@Sensitive
class NodeTemplate internal constructor(internal val outboundJson: String) {

    override fun toString(): String = "NodeTemplate(<redacted>)"

    override fun equals(other: Any?): Boolean = other is NodeTemplate && other.outboundJson == outboundJson

    override fun hashCode(): Int = outboundJson.hashCode()
}
