package com.example.input_ds.personalization

import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide token cache used by short-lived API clients and WorkManager workers.
 * A state object is also the per-account single-flight lock, so concurrent callers
 * never turn one expired token into a burst of authentication requests.
 */
internal class SharedAuthTokenCache(
    private val failureCooldownMs: Long = 15_000L
) {
    data class Token(val value: String, val expiresAtMs: Long)

    private class State {
        var token: Token? = null
        var failure: Throwable? = null
        var retryAtMs: Long = 0L
    }

    private val states = ConcurrentHashMap<String, State>()

    fun getOrLoad(
        key: String,
        nowMs: () -> Long = System::currentTimeMillis,
        loader: () -> Token
    ): String {
        val state = states.computeIfAbsent(key) { State() }
        synchronized(state) {
            val now = nowMs()
            state.token?.takeIf { now < it.expiresAtMs }?.let { return it.value }
            state.failure?.takeIf { now < state.retryAtMs }?.let { throw it }

            return try {
                loader().also {
                    state.token = it
                    state.failure = null
                    state.retryAtMs = 0L
                }.value
            } catch (error: Throwable) {
                state.failure = error
                state.retryAtMs = nowMs() + failureCooldownMs
                throw error
            }
        }
    }

    fun invalidate(key: String, rejectedToken: String?) {
        if (rejectedToken == null) return
        val state = states[key] ?: return
        synchronized(state) {
            if (state.token?.value == rejectedToken) state.token = null
        }
    }
}
