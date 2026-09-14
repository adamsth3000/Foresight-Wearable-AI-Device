package com.foresight.gateway.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayUiRenderCacheTest {
    @Test
    fun `cache renders the initial value and only later differences`() {
        val cache = GatewayUiRenderCache<String>()

        assertTrue(cache.shouldRender("STREAMING"))
        assertFalse(cache.shouldRender("STREAMING"))
        assertTrue(cache.shouldRender("IDLE"))
        assertFalse(cache.shouldRender("IDLE"))
    }

    @Test
    fun `cache treats a selected sync attempt change as a history change`() {
        val cache = GatewayUiRenderCache<SyncHistoryRenderState>()
        val first = SyncHistoryRenderState(emptyList(), emptySet(), "attempt-1")

        assertTrue(cache.shouldRender(first))
        assertFalse(cache.shouldRender(first.copy()))
        assertTrue(cache.shouldRender(first.copy(selectedAttemptId = "attempt-2")))
    }
}
