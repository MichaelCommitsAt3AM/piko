package com.piko.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoordinateMapperTest {
    @Test
    fun `image coordinates map to screen coordinates`() {
        val point = CoordinateMapper.imageToScreen(
            PixelPoint(50, 100),
            PixelSize(200, 400),
            PixelSize(400, 800),
        )
        assertEquals(PixelPoint(100, 200), point)
    }

    @Test
    fun `screen coordinates map back to image`() {
        val point = CoordinateMapper.screenToImage(
            PixelPoint(540, 1200),
            PixelSize(540, 1200),
            PixelSize(1080, 2400),
        )
        assertEquals(PixelPoint(270, 600), point)
    }

    @Test
    fun `mapping clamps to screen edges`() {
        val point = CoordinateMapper.imageToScreen(
            PixelPoint(9999, -20),
            PixelSize(100, 100),
            PixelSize(1000, 2000),
        )
        assertEquals(999, point.x)
        assertEquals(0, point.y)
    }

    @Test
    fun `oriented size swaps dimensions for landscape rotation`() {
        assertEquals(PixelSize(200, 100), CoordinateMapper.orientedSize(PixelSize(100, 200), 90))
        assertEquals(PixelSize(100, 200), CoordinateMapper.orientedSize(PixelSize(100, 200), 0))
        assertEquals(PixelSize(200, 100), CoordinateMapper.orientedSize(PixelSize(100, 200), 270))
    }

    @Test
    fun `fit inside preserves aspect ratio`() {
        val result = CoordinateMapper.fitInside(PixelSize(1080, 2400), 1080)
        assertEquals(486, result.width)
        assertEquals(1080, result.height)
    }

    @Test
    fun `small images are not enlarged`() {
        assertEquals(PixelSize(300, 200), CoordinateMapper.fitInside(PixelSize(300, 200), 1024))
    }
}

class AgentStateMachineTest {
    @Test
    fun `happy path transitions are accepted`() {
        val machine = AgentStateMachine()
        assertTrue(machine.transitionTo(AgentState.PREPARING))
        assertTrue(machine.transitionTo(AgentState.OBSERVING))
        assertTrue(machine.transitionTo(AgentState.REASONING))
        assertTrue(machine.transitionTo(AgentState.EXECUTING))
        assertTrue(machine.transitionTo(AgentState.VERIFYING))
        assertTrue(machine.transitionTo(AgentState.OBSERVING))
    }

    @Test
    fun `terminal state cannot restart`() {
        val machine = AgentStateMachine()
        machine.transitionTo(AgentState.PREPARING)
        machine.transitionTo(AgentState.FAILED)
        assertFalse(machine.transitionTo(AgentState.OBSERVING))
        assertFalse(machine.transitionTo(AgentState.CANCELLED))
    }

    @Test
    fun `pause is allowed from running states`() {
        val machine = AgentStateMachine()
        machine.transitionTo(AgentState.PREPARING)
        machine.transitionTo(AgentState.OBSERVING)
        machine.transitionTo(AgentState.REASONING)
        assertTrue(machine.transitionTo(AgentState.PAUSED))
        assertTrue(machine.transitionTo(AgentState.AWAITING_USER_CONFIRMATION))
    }

    @Test
    fun `cancel is allowed from active state`() {
        val machine = AgentStateMachine()
        machine.transitionTo(AgentState.PREPARING)
        assertTrue(machine.transitionTo(AgentState.CANCELLED))
        assertEquals(AgentState.CANCELLED, machine.state)
    }

    @Test
    fun `approval returns to reasoning for pre approved action`() {
        val machine = AgentStateMachine()
        machine.transitionTo(AgentState.PREPARING)
        machine.transitionTo(AgentState.OBSERVING)
        machine.transitionTo(AgentState.REASONING)
        machine.transitionTo(AgentState.AWAITING_USER_CONFIRMATION)
        assertTrue(machine.transitionTo(AgentState.REASONING))
        assertTrue(machine.transitionTo(AgentState.EXECUTING))
    }
}
