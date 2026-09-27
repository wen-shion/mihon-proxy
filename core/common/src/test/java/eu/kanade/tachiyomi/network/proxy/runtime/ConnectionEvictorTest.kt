package eu.kanade.tachiyomi.network.proxy.runtime

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

/**
 * T-07, the evictor's half: cancel first, evict second.
 *
 * `evictAll()` only removes idle connections, so the order is what makes the call meaningful - a pool
 * evicted without a cancel would keep serving a tunnel nobody can see.
 */
class ConnectionEvictorTest {

    @Test
    fun `cancel and evict cancels the in-flight calls before evicting the pool`() {
        val client = mockk<OkHttpClient>(relaxed = true)
        val evictor = OkHttpConnectionEvictor(client)

        evictor.cancelAndEvict()

        verifyOrder {
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
        }
    }

    @Test
    fun `every pass cancels and evicts again`() {
        val client = mockk<OkHttpClient>(relaxed = true)
        val evictor = OkHttpConnectionEvictor(client)

        // The double eviction of a switch is two full passes, not one reused one.
        evictor.cancelAndEvict()
        evictor.cancelAndEvict()

        verify(exactly = 2) { client.dispatcher.cancelAll() }
        verify(exactly = 2) { client.connectionPool.evictAll() }
    }
}
