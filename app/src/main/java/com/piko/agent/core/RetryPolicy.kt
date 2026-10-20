package com.piko.agent.core

import kotlin.math.min

/**
 * Bounded exponential backoff for provider failures and transient execution
 * errors. Retry-After values are honored when present.
 */
class RetryPolicy(
    val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 900,
    private val maxDelayMs: Long = 8_000,
) {
    init {
        require(maxAttempts >= 1)
    }

    fun nextDelayMs(attempt: Int, retryAfterMs: Long? = null): Long? {
        if (attempt >= maxAttempts) return null
        if (retryAfterMs != null && retryAfterMs > 0) return min(retryAfterMs, maxDelayMs)
        val exponential = baseDelayMs * (1L shl attempt.coerceIn(0, 5))
        return exponential.coerceAtMost(maxDelayMs)
    }

    fun isRetryable(httpCode: Int?): Boolean = when (httpCode) {
        null -> true
        408, 409, 425, 429 -> true
        in 500..599 -> true
        else -> false
    }
}
