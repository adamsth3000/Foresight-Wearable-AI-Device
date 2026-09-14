package com.foresight.gateway.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompassRibbonModelTest {
    @Test
    fun `normalizes circular headings`() {
        assertEquals(359f, CompassRibbonModel.normalizeDegrees(-1f), 0f)
        assertEquals(0f, CompassRibbonModel.normalizeDegrees(0f), 0f)
        assertEquals(0f, CompassRibbonModel.normalizeDegrees(360f), 0f)
        assertEquals(1f, CompassRibbonModel.normalizeDegrees(721f), 0f)
    }

    @Test
    fun `uses the shortest continuous delta across north`() {
        assertEquals(2f, CompassRibbonModel.shortestAngularDelta(359f, 1f), 0f)
        assertEquals(-2f, CompassRibbonModel.shortestAngularDelta(1f, 359f), 0f)
    }

    @Test
    fun `maps cardinal and intercardinal labels`() {
        assertEquals("N", CompassRibbonModel.cardinalLabel(0))
        assertEquals("NE", CompassRibbonModel.cardinalLabel(45))
        assertEquals("E", CompassRibbonModel.cardinalLabel(90))
        assertEquals("SE", CompassRibbonModel.cardinalLabel(135))
        assertEquals("S", CompassRibbonModel.cardinalLabel(180))
        assertEquals("SW", CompassRibbonModel.cardinalLabel(225))
        assertEquals("W", CompassRibbonModel.cardinalLabel(270))
        assertEquals("NW", CompassRibbonModel.cardinalLabel(315))
    }

    @Test
    fun `projects wraparound ticks continuously around north`() {
        val ticks = CompassRibbonModel.visibleTicks(359f)

        assertTrue(ticks.any { it.headingDegrees == 0 && it.relativeDegrees > 0f })
        assertTrue(ticks.any { it.headingDegrees == 345 && it.relativeDegrees < 0f })
    }
}
