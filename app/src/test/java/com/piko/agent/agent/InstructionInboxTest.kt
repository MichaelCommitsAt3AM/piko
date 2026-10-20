package com.piko.agent.agent

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstructionInboxTest {
    @Test fun emptyInstructionsDoNotInvalidateWork() {
        val inbox = InstructionInbox()
        assertFalse(inbox.add("  "))
        assertEquals(0L, inbox.state.value.revision)
    }
    @Test fun updatesAreOrderedAndBounded() {
        val inbox = InstructionInbox()
        repeat(25) { inbox.add("instruction $it") }
        assertEquals(25L, inbox.state.value.revision)
        assertEquals(20, inbox.state.value.messages.size)
        assertEquals("instruction 24", inbox.state.value.messages.last())
    }
    @Test fun normalResponseIsDelivered() = runTest {
        assertEquals("action", InstructionInbox().whileCurrent(0) { "action" })
    }
    @Test fun userUpdateCancelsPendingInference() = runTest {
        val inbox = InstructionInbox()
        var cancelled = false
        val pending = async {
            inbox.whileCurrent(0) {
                try { awaitCancellation() } finally { cancelled = true }
            }
        }
        runCurrent()
        inbox.add("Use Calculator instead")
        assertNull(pending.await())
        assertTrue(cancelled)
    }
    @Test fun staleSnapshotNeverStartsInference() = runTest {
        val inbox = InstructionInbox()
        inbox.add("Different task")
        var called = false
        assertNull(inbox.whileCurrent(0) { called = true; "old action" })
        assertFalse(called)
    }
}
