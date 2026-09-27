package eu.kanade.tachiyomi.network

import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * Verifies the redaction configured by [headerLoggingInterceptor], which is the interceptor the
 * HTTP client installs when verbose logging is on.
 */
class HeaderLoggingTest {

    private val proxyAuthorizationHeader = "Proxy-Authorization"

    private val secret = "Basic dXNlcjpwYXNzd29yZA=="

    @Test
    fun `redacts the Proxy-Authorization value in logged headers`() {
        val logs = mutableListOf<String>()

        interceptLoggedRequest(headerLoggingInterceptor { logs += it })

        assertTrue(
            logs.any { it == "$proxyAuthorizationHeader: ██" },
            "expected a redacted header line, got $logs",
        )
        assertFalse(logs.any { it.contains(secret) }, "the credential leaked into $logs")
    }

    @Test
    fun `redaction is case insensitive`() {
        val logs = mutableListOf<String>()

        interceptLoggedRequest(
            interceptor = headerLoggingInterceptor { logs += it },
            headerName = "proxy-authorization",
        )

        assertFalse(logs.any { it.contains(secret) }, "the credential leaked into $logs")
    }

    @Test
    fun `the credential would be logged without redaction`() {
        // Negative control: proves the assertions above are able to detect a leak at all.
        val logs = mutableListOf<String>()
        val unredacted = HttpLoggingInterceptor { logs += it }.apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        interceptLoggedRequest(unredacted)

        assertTrue(logs.any { it.contains(secret) }, "expected the control to leak, got $logs")
    }

    private fun interceptLoggedRequest(
        interceptor: Interceptor,
        headerName: String = proxyAuthorizationHeader,
    ) {
        val request = Request.Builder()
            .url("https://example.com/")
            .header(headerName, secret)
            .build()

        val chain = mockk<Interceptor.Chain>(relaxed = true)
        every { chain.request() } returns request
        // Stubbed explicitly: HttpLoggingInterceptor reads it to build the start line.
        every { chain.connection() } returns null
        every { chain.proceed(any()) } throws IOException("no network in unit tests")

        assertThrows(IOException::class.java) { interceptor.intercept(chain) }
    }
}
