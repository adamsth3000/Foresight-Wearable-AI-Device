package com.foresight.gateway.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspGenerationOwnershipTest {
    @Test
    fun `retired generation callbacks cannot affect its replacement`() {
        val ownership = RtspGenerationOwnership()
        ownership.activate(1)
        ownership.retire(1)
        ownership.activate(2)

        assertFalse(ownership.accepts(1))
        assertTrue(ownership.accepts(2))
    }

    @Test
    fun `repeated retirement leaves no active publisher generation`() {
        val ownership = RtspGenerationOwnership()
        ownership.activate(1)
        ownership.retire(1)
        ownership.retire(1)

        assertFalse(ownership.accepts(1))
    }
}
