package com.example.input_ds.personalization

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SharedAuthTokenCacheTest {
    @Test
    fun concurrentCallersUseOneTokenRequest() {
        val cache = SharedAuthTokenCache()
        val loads = AtomicInteger()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = List(16) {
                executor.submit<String> {
                    start.await()
                    cache.getOrLoad("account") {
                        loads.incrementAndGet()
                        Thread.sleep(40)
                        SharedAuthTokenCache.Token("shared-token", Long.MAX_VALUE)
                    }
                }
            }
            start.countDown()

            assertEquals(List(16) { "shared-token" }, futures.map { it.get(2, TimeUnit.SECONDS) })
            assertEquals(1, loads.get())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun failedAuthenticationIsSharedDuringCooldown() {
        val cache = SharedAuthTokenCache(failureCooldownMs = 15_000L)
        val loads = AtomicInteger()
        var now = 1_000L
        val failure = IllegalStateException("limited")
        val loader = {
            loads.incrementAndGet()
            throw failure
        }

        val first = runCatching { cache.getOrLoad("account", { now }, loader) }.exceptionOrNull()
        now += 10_000L
        val second = runCatching { cache.getOrLoad("account", { now }, loader) }.exceptionOrNull()

        assertSame(failure, first)
        assertSame(failure, second)
        assertEquals(1, loads.get())
    }

    @Test
    fun rejectingAnOldTokenDoesNotDeleteANewerToken() {
        val cache = SharedAuthTokenCache()
        var now = 1_000L
        assertEquals(
            "old",
            cache.getOrLoad("account", { now }) { SharedAuthTokenCache.Token("old", 2_000L) }
        )
        now = 2_001L
        assertEquals(
            "new",
            cache.getOrLoad("account", { now }) { SharedAuthTokenCache.Token("new", 9_000L) }
        )

        cache.invalidate("account", "old")

        assertEquals(
            "new",
            cache.getOrLoad("account", { now }) { error("new token was incorrectly invalidated") }
        )
    }
}
