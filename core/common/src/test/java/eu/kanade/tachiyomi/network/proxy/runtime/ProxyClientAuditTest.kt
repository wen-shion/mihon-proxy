package eu.kanade.tachiyomi.network.proxy.runtime

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * T-40: the client ownership audit, as a test instead of as a memory.
 *
 * The evictor can only reach the traffic that shares the central client's pool and dispatcher. Today
 * every derived client is a `newBuilder()` child, so one `cancelAll()` covers all of it - but that is
 * a fact about the current sources, not a structural guarantee, and the way it breaks is someone
 * building a second client, or replacing the pool or dispatcher on a derived one, for something that
 * looks harmless. This test fails on that day and names the file.
 *
 * What it looks for, over every main source in the repository:
 *
 * * a second `OkHttpClient.Builder()` - a second client is a second pool and a second dispatcher,
 *   which is a set of connections no transition can clear;
 * * a *replacement* of a client's dispatcher or connection pool - `.dispatcher(x)` and
 *   `.connectionPool(x)`, as opposed to reading them, which is what the evictor does;
 * * a `Dispatcher` or `ConnectionPool` constructed under a name of its own - the coroutine dispatchers
 *   that share the words (`asCoroutineDispatcher`) are not OkHttp's and are excluded.
 *
 * Nothing is on the whitelist today, because nothing replaces anything. When the control-plane client
 * arrives it goes here explicitly, next to the note that its lifetime belongs to its owner and that
 * the evictor must not reach it.
 */
class ProxyClientAuditTest {

    private val repoRoot: File = generateSequence(File(".").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("could not locate the repository root from ${File(".").absolutePath}")

    private val replacementPatterns = listOf("\\.dispatcher\\(", "\\.connectionPool\\(")

    private val constructionPatterns = listOf(
        "(?<![A-Za-z0-9_])Dispatcher\\(",
        "(?<![A-Za-z0-9_])ConnectionPool\\(",
    )

    private fun mainSources(): List<File> = repoRoot.walkTopDown()
        .onEnter { dir ->
            dir.name !in setOf("build", ".git", ".gradle", ".workbuddy", ".idea", "node_modules")
        }
        .filter { it.isFile && it.extension == "kt" }
        .filter { it.invariantSeparatorsPath.contains("/src/main/") }
        .toList()

    private fun offenders(patterns: List<String>): List<String> {
        val sources = mainSources()
        // A silent zero usually means the walk above quietly stopped matching, not that there is
        // nothing to look at.
        (sources.size > 100) shouldBe true
        return sources
            .flatMap { file ->
                val text = file.readText()
                patterns.filter { pattern -> Regex(pattern).containsMatchIn(text) }.map { file }
            }
            .map { it.invariantSeparatorsPath.removePrefix(repoRoot.invariantSeparatorsPath) }
            .sorted()
    }

    @Test
    fun `the app builds exactly one okhttp client`() {
        val builders = mainSources()
            .filter { "OkHttpClient.Builder()" in it.readText() }
            .map { it.invariantSeparatorsPath.removePrefix(repoRoot.invariantSeparatorsPath) }
            .sorted()
        builders shouldContainExactly
            listOf("/core/common/src/main/kotlin/eu/kanade/tachiyomi/network/NetworkHelper.kt")
    }

    @Test
    fun `nothing replaces a client's dispatcher or connection pool`() {
        offenders(replacementPatterns) shouldBe emptyList()
    }

    @Test
    fun `no second connection pool or dispatcher is ever constructed`() {
        offenders(constructionPatterns) shouldBe emptyList()
    }
}
