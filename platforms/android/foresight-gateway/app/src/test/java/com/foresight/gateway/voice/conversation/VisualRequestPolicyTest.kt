package com.foresight.gateway.voice.conversation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualRequestPolicyTest {
    @Test fun `visual language requests an image`() {
        assertTrue(VisualRequestPolicy.decide("What color is the chair beside that painting?").includeImage)
        assertTrue(VisualRequestPolicy.decide("What am I looking at?").includeImage)
    }

    @Test fun `general knowledge remains text only`() {
        assertFalse(VisualRequestPolicy.decide("Why is the sky blue?").includeImage)
    }
}
