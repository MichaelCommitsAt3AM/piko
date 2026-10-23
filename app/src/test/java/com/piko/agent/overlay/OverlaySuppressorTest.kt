package com.piko.agent.overlay

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OverlaySuppressorTest {
    private val dispatcher = StandardTestDispatcher()
    private val changes = mutableListOf<Boolean>()
    private val callback: (Boolean) -> Unit = { changes += it }
    @Before fun setup() { Dispatchers.setMain(dispatcher); OverlaySuppressor.register(callback) }
    @After fun cleanup() { OverlaySuppressor.unregister(callback); Dispatchers.resetMain() }

    @Test fun permissionDialogsRemainUnobscuredAfterCaptureCleanup() = runTest(dispatcher) {
        OverlaySuppressor.setSecuritySuppressed(true)
        try {
            OverlaySuppressor.suppressed { }
            assertTrue(OverlaySuppressor.isSuppressed)
            assertEquals(false, changes.last())
        } finally { OverlaySuppressor.setSecuritySuppressed(false) }
        assertFalse(OverlaySuppressor.isSuppressed)
        assertEquals(true, changes.last())
    }
    @Test fun cancellationDuringSettleDelayRestoresControls() = runTest(dispatcher) {
        var captured = false
        val job = launch { OverlaySuppressor.suppressed { captured = true } }
        runCurrent()
        assertEquals(listOf(false), changes)
        job.cancel()
        job.join()
        assertEquals(listOf(false, true), changes)
        assertFalse(captured)
        assertFalse(OverlaySuppressor.isSuppressed)
    }
    @Test fun failedCaptureRestoresControls() = runTest(dispatcher) {
        val result = async { runCatching { OverlaySuppressor.suppressed { error("capture failed") } } }
        advanceUntilIdle()
        assertTrue(result.await().isFailure)
        assertEquals(listOf(false, true), changes)
    }
    @Test fun overlappingCapturesDoNotRestoreControlsDuringAnotherCapture() = runTest(dispatcher) {
        val first = launch { OverlaySuppressor.suppressed { kotlinx.coroutines.delay(100) } }
        val second = launch { OverlaySuppressor.suppressed { kotlinx.coroutines.delay(100) } }
        runCurrent()
        assertEquals(listOf(false), changes)
        advanceUntilIdle()
        first.join(); second.join()
        assertEquals(listOf(false, true, false, true), changes)
    }
}
