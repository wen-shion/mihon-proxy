package eu.kanade.tachiyomi.network.proxy

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

/**
 * The response-envelope contract, and the property that nothing escapes it.
 *
 * [XrayExchange.data] is the only place a native response is read, so it is the only place a
 * malformed one can turn into a raw parser or type exception. Every path out of it has to be a
 * classified [XrayException], and the two kinds of failure are kept apart on purpose:
 *
 * * **not JSON** is [XrayErrorCategory.MalformedJson] - the core did not answer in the protocol;
 * * **valid JSON that violates the envelope** is [XrayErrorCategory.LocalFailure] - a disagreement
 *   about the contract, which the runtime must not mistake for a remote failure it should retry or
 *   route around.
 *
 * None of them may carry the response or the native error text.
 */
class XrayExchangeTest {

    private fun failure(response: String?): XrayException =
        shouldThrow<XrayException> { XrayExchange.data(response) }

    // -- the shapes that are accepted ------------------------------------------

    @Test
    fun `a success returns its data object`() {
        XrayExchange.data("""{"success":true,"data":{"version":"26.9.9"},"error":""}""")
            .string("version") shouldBe "26.9.9"
    }

    @Test
    fun `a success without usable data returns an empty object`() {
        // The pinned contract has no data for several methods; `null` is how that is expressed.
        XrayExchange.data("""{"success":true,"data":{},"error":""}""") shouldBe JsonObject(emptyMap())
        XrayExchange.data("""{"success":true,"data":null,"error":""}""") shouldBe JsonObject(emptyMap())
        XrayExchange.data("""{"success":true,"error":""}""") shouldBe JsonObject(emptyMap())
    }

    @Test
    fun `a failure envelope is classified from its error text`() {
        failure("""{"success":false,"data":null,"error":"xray is already running"}""").category shouldBe
            XrayErrorCategory.AlreadyRunning
        failure("""{"success":false,"data":null,"error":"unknown method"}""").category shouldBe
            XrayErrorCategory.UnknownMethod
    }

    // -- malformed JSON --------------------------------------------------------

    @Test
    fun `text that is not json is a malformed json failure`() {
        listOf("not json", "{", "", """{"success":""").forEach { text ->
            withClue(text) { failure(text).category shouldBe XrayErrorCategory.MalformedJson }
        }
    }

    @Test
    fun `valid json that is not an envelope object is a local failure`() {
        listOf("[]", "\"a string\"", "42", "true", "null").forEach { text ->
            withClue(text) { failure(text).category shouldBe XrayErrorCategory.LocalFailure }
        }
    }

    @Test
    fun `a missing response is a local failure`() {
        failure(null).category shouldBe XrayErrorCategory.LocalFailure
    }

    // -- contract-violating shapes ---------------------------------------------

    @Test
    fun `a success flag that is not a boolean is a local failure`() {
        // An object, an array, a string, a number, the literal null and an absent key are all
        // contract violations. None of them may be read as "not succeeded": that would let a broken
        // envelope look like a normal failure the runtime is expected to act on.
        listOf(
            """{"success":{},"data":{}}""",
            """{"success":[],"data":{}}""",
            """{"success":"true","data":{}}""",
            """{"success":"false","data":{}}""",
            """{"success":1,"data":{}}""",
            """{"success":null,"data":{}}""",
            """{"data":{}}""",
        ).forEach { envelope ->
            withClue(envelope) { failure(envelope).category shouldBe XrayErrorCategory.LocalFailure }
        }
    }

    @Test
    fun `an error field that is not a string is a local failure`() {
        listOf(
            """{"success":false,"error":{}}""",
            """{"success":false,"error":[]}""",
            """{"success":false,"error":404}""",
            """{"success":false,"error":null}""",
            """{"success":false}""",
        ).forEach { envelope ->
            withClue(envelope) { failure(envelope).category shouldBe XrayErrorCategory.LocalFailure }
        }
    }

    @Test
    fun `a data field that is neither an object nor absent is a local failure`() {
        listOf(
            """{"success":true,"data":"text"}""",
            """{"success":true,"data":[1,2]}""",
            """{"success":true,"data":7}""",
        ).forEach { envelope ->
            withClue(envelope) { failure(envelope).category shouldBe XrayErrorCategory.LocalFailure }
        }
    }

    // -- no raw text escapes ---------------------------------------------------

    @Test
    fun `no shape failure carries the response it failed on`() {
        val secret = "https://secret.example/sub?token=SECRET-TOKEN"
        val envelopes = listOf(
            "not json at all $secret",
            """{"success":"$secret"}""",
            """{"success":false,"error":{"u":"$secret"}}""",
            """{"success":true,"data":"$secret"}""",
            """{"success":false,"error":42,"data":{"v":"$secret"}}""",
        )
        envelopes.forEach { envelope ->
            withClue(envelope) {
                val error = failure(envelope)
                val rendered = error.renderings()
                rendered.none { it.contains("SECRET-TOKEN") } shouldBe true
                rendered.none { it.contains("secret.example") } shouldBe true
            }
        }
    }
}
