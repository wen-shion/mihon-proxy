package eu.kanade.tachiyomi.network.proxy

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * T-05: the native vocabulary stays in one file.
 *
 * The point is not tidiness. Method names and the `libXray` binding are the parts that change when the
 * AAR is upgraded, and absorbing an AAR upgrade is what this whole layer is designed for; if a method
 * name crept into the runtime or the UI, an upgrade would scatter across the codebase instead of
 * landing in one reviewable file.
 */
class LibXrayInvokerIsolationTest {

    private val methodNames = listOf(
        "xrayVersion",
        "getXrayState",
        "getFreePorts",
        "testXray",
        "runXray",
        "stopXray",
        "convertShareLinksToXrayJson",
    )

    private val invokerFile = "LibXrayInvoker.kt"

    /**
     * Gradle runs unit tests with the module directory as the working directory, but that is a build
     * detail rather than a guarantee, so a few plausible roots are tried and a miss is reported
     * loudly rather than silently passing.
     */
    private fun mainSourceRoot(): File {
        val candidates = listOf(
            File("src/main/kotlin"),
            File("core/common/src/main/kotlin"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("could not locate src/main/kotlin from ${File(".").absolutePath}")
    }

    private fun kotlinSources(): List<File> =
        mainSourceRoot().walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `every libXray method name is declared in one place`() {
        val sources = kotlinSources()
        // Guards against the roots above silently pointing at an almost-empty tree.
        (sources.size > 5) shouldBe true

        methodNames.forEach { name ->
            val declaring = sources.filter { it.readText().contains("\"$name\"") }.map { it.name }
            declaring shouldContainExactly listOf(invokerFile)
        }
    }

    @Test
    fun `only the invoker references the native binding`() {
        val referencing = kotlinSources()
            .filter { "libXray.LibXray" in it.readText() || "LibXray.invoke" in it.readText() }
            .map { it.name }
        referencing shouldContainExactly listOf(invokerFile)
    }

    @Test
    fun `the wire names still match the pinned libXray vocabulary`() {
        // Written out rather than derived: a change here means either the AAR was upgraded or a typo
        // slipped in, and both need review.
        XrayMethod.Version.wireName shouldBe "xrayVersion"
        XrayMethod.State.wireName shouldBe "getXrayState"
        XrayMethod.FreePorts.wireName shouldBe "getFreePorts"
        XrayMethod.Test.wireName shouldBe "testXray"
        XrayMethod.Run.wireName shouldBe "runXray"
        XrayMethod.Stop.wireName shouldBe "stopXray"
        XrayMethod.ConvertShareLinks.wireName shouldBe "convertShareLinksToXrayJson"
    }

    @Test
    fun `the envelope carries the pinned api version`() {
        XrayExchange.API_VERSION shouldBe 3
        XrayExchange.envelope("xrayVersion", null) shouldBe """{"apiVersion":3,"method":"xrayVersion"}"""
        XrayExchange.envelope("testXray", """{"xrayJson":"{}"}""") shouldBe
            """{"apiVersion":3,"method":"testXray","payload":{"xrayJson":"{}"}}"""
    }
}
