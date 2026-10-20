package com.piko.agent.core

import kotlin.math.roundToLong

data class TokenUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cachedInputTokens: Long = 0,
    val costUsd: Double = 0.0,
) {
    fun plus(other: TokenUsage): TokenUsage = TokenUsage(
        inputTokens = inputTokens + other.inputTokens,
        outputTokens = outputTokens + other.outputTokens,
        cachedInputTokens = cachedInputTokens + other.cachedInputTokens,
        costUsd = costUsd + other.costUsd,
    )

    companion object {
        fun estimate(
            inputTokens: Long,
            outputTokens: Long,
            inputPricePerMillion: Double,
            outputPricePerMillion: Double,
            cachedInputTokens: Long = 0,
        ): TokenUsage {
            val billableInput = (inputTokens - cachedInputTokens).coerceAtLeast(0)
            val cost = (billableInput / 1_000_000.0) * inputPricePerMillion +
                (cachedInputTokens / 1_000_000.0) * inputPricePerMillion * 0.1 +
                (outputTokens / 1_000_000.0) * outputPricePerMillion
            return TokenUsage(inputTokens, outputTokens, cachedInputTokens, cost)
        }
    }
}

object TokenEstimator {
    /** Conservative heuristic used only for UI hints before a response arrives. */
    fun estimateTokens(text: String): Long = (text.length / 4.0).roundToLong().coerceAtLeast(1)
}
