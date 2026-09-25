package eu.kanade.tachiyomi.network.proxy

/**
 * Every way a throwable can be rendered: its `toString()`, its message, its type, and the printed
 * stack trace - which walks the cause chain itself and is what a crash reporter writes out.
 *
 * A redaction regression can hide in any of them, so the assertions that matter check all of them
 * rather than the message alone.
 */
internal fun Throwable.renderings(): List<String> {
    val chain = generateSequence(this) { it.cause }.toList()
    return buildList {
        addAll(chain.map { it.toString() })
        addAll(chain.map { it.message ?: "" })
        addAll(chain.map { it.javaClass.name })
        add(stackTraceToString())
        add(chain.last().stackTraceToString())
    }
}
