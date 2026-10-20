package com.piko.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopDetectorTest {
    private val tap = AgentAction(type = ActionType.TAP, elementId = "element_1")

    @Test
    fun `repeated identical action on unchanged screen warns then aborts`() {
        val detector = LoopDetector()
        assertEquals(LoopDetector.LoopSignal.None, detector.record("fp1", tap))
        assertTrue(detector.record("fp1", tap) is LoopDetector.LoopSignal.Warn)
        assertTrue(detector.record("fp1", tap) is LoopDetector.LoopSignal.Abort)
    }

    @Test
    fun `screen change resets duplicate tracking`() {
        val detector = LoopDetector()
        detector.record("fp1", tap)
        assertTrue(detector.record("fp2", tap) is LoopDetector.LoopSignal.None)
        assertTrue(detector.record("fp3", tap) is LoopDetector.LoopSignal.None)
    }

    @Test
    fun `same target taps abort`() {
        val detector = LoopDetector(maxSameElementTaps = 3)
        detector.record("same", tap)
        detector.record("same", AgentAction(type = ActionType.LONG_PRESS, elementId = "element_1"))
        detector.record("same", tap)
        assertTrue(detector.record("same", AgentAction(type = ActionType.LONG_PRESS, elementId = "element_1")) is LoopDetector.LoopSignal.Abort)
    }

    @Test
    fun `unchanged screen with different actions aborts after progress limit`() {
        val detector = LoopDetector(maxStepsWithoutProgress = 4)
        for (index in 0..4) {
            val action = AgentAction(type = ActionType.TYPE_TEXT, text = "value$index")
            val signal = detector.record("same", action)
            if (index == 4) {
                assertTrue(signal is LoopDetector.LoopSignal.Abort)
            }
        }
    }

    @Test
    fun `signature includes variable action arguments`() {
        assertNotNull(LoopDetector.signature(tap))
        assertEquals(
            LoopDetector.signature(tap),
            LoopDetector.signature(AgentAction(type = ActionType.TAP, elementId = "element_1")),
        )
        assertTrue(
            LoopDetector.signature(tap) !=
                LoopDetector.signature(AgentAction(type = ActionType.TAP, elementId = "element_2")),
        )
    }
}

class RetryPolicyTest {
    @Test
    fun `exponential delays are bounded and stop at max attempts`() {
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 100, maxDelayMs = 1000)
        assertEquals(100L, policy.nextDelayMs(0))
        assertEquals(200L, policy.nextDelayMs(1))
        assertEquals(null, policy.nextDelayMs(3))
    }

    @Test
    fun `retry after is honored but capped`() {
        val policy = RetryPolicy(maxAttempts = 3, baseDelayMs = 100, maxDelayMs = 5000)
        assertEquals(3000L, policy.nextDelayMs(0, 3000))
        assertEquals(5000L, policy.nextDelayMs(0, 50_000))
    }

    @Test
    fun `retryable classes are correct`() {
        val policy = RetryPolicy()
        assertTrue(policy.isRetryable(429))
        assertTrue(policy.isRetryable(503))
        assertTrue(policy.isRetryable(null))
        assertTrue(!policy.isRetryable(401))
        assertTrue(!policy.isRetryable(400))
        assertTrue(!policy.isRetryable(404))
    }
}
