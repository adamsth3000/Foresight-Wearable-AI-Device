package com.foresight.gateway.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspTransportHealthMonitorTest {
    @Test
    fun `dropped frames immediately identify a stalled sender`() {
        val monitor = RtspTransportHealthMonitor()

        monitor.observe(snapshot(droppedVideoFrames = 0))
        val result = monitor.observe(snapshot(droppedVideoFrames = 1))

        assertTrue(result.shouldReconnect)
    }

    @Test
    fun `sustained sender queue saturation identifies a stalled sender`() {
        val monitor = RtspTransportHealthMonitor()

        assertFalse(monitor.observe(snapshot(queueItems = 300)).shouldReconnect)
        assertTrue(monitor.observe(snapshot(queueItems = 300)).shouldReconnect)
    }

    @Test
    fun `normal sender queue does not request reconnect`() {
        val monitor = RtspTransportHealthMonitor()

        assertFalse(monitor.observe(snapshot(queueItems = 20)).shouldReconnect)
        assertFalse(monitor.observe(snapshot(queueItems = 20)).shouldReconnect)
    }

    private fun snapshot(
        queueItems: Int = 0,
        droppedVideoFrames: Long = 0,
    ) = RtspTransportHealthMonitor.Snapshot(
        queueItems = queueItems,
        queueCapacity = 400,
        sentVideoFrames = 100,
        sentAudioFrames = 100,
        droppedVideoFrames = droppedVideoFrames,
        droppedAudioFrames = 0,
        bytesSent = 1_000,
    )
}
