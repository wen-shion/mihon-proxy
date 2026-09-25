package eu.kanade.tachiyomi.network.proxy.model

/**
 * Identity of a node inside one subscription snapshot.
 *
 * Generated locally when a node is first seen; it is deliberately **not** derived from the node's
 * endpoint or credentials, so it can be logged and used as a list key. It is also not stable across
 * a refresh - the provider may reorder or replace nodes - so it must never be used to restore a
 * selection. That is what the encrypted selected-node snapshot is for.
 */
@JvmInline
value class NodeId(val value: String)

enum class NodeProtocol {
    VLESS,
}

enum class NodeSecurity {
    REALITY,
}

/**
 * The non-sensitive half of a node: what the UI needs and nothing more.
 *
 * There are deliberately no `server`, `port`, `uuid`, `publicKey` or `shortId` fields. Those live
 * only inside [NodeTemplate]'s opaque payload, so they cannot leak into a log line, a bug report or
 * a data class `toString()` by accident, and nothing in the domain layer can start depending on
 * their shape.
 *
 * @param displayName the provider's remark, or a `Node N` fallback when the remark is empty. It is
 *   semi-sensitive: shown in the UI, never written to a log.
 */
data class ProxyNode(
    val id: NodeId,
    val ordinal: Int,
    val displayName: String,
    val protocol: NodeProtocol,
    val security: NodeSecurity,
)
