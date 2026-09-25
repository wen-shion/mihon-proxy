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
 *   semi-sensitive: the UI shows it, but it must never reach a log or a crash report - it names the
 *   provider's node, which is exactly the association the user did not opt into publishing.
 */
data class ProxyNode(
    val id: NodeId,
    val ordinal: Int,
    val displayName: String,
    val protocol: NodeProtocol,
    val security: NodeSecurity,
) {

    /**
     * Redacts [displayName].
     *
     * The generated `toString()` of a data class prints every property, and a `ProxyNode` in a log
     * line, a crash report or an assertion message is a realistic accident - a list of nodes is the
     * obvious thing to print when something looks wrong. The remark is only semi-sensitive, so it is
     * shown to the user, but it is not allowed out through a debug channel, and `displayName` stays
     * readable through the property for the UI.
     */
    override fun toString(): String =
        "ProxyNode(id=$id, ordinal=$ordinal, protocol=$protocol, security=$security, displayName=<redacted>)"
}
