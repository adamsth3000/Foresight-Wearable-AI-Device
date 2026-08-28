package com.foresight.gateway.transport

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RtspSenderProgressMonitorTest {
    @Test
    fun `active camera with a growing sender queue and frozen counters requests rebuild`() {
        val monitor = RtspSenderProgressMonitor()

        monitor.observe(snapshot(elapsed = 0, cameraFrames = 0, queueItems = 0))
        assertFalse(monitor.observe(snapshot(elapsed = 2_000, cameraFrames = 60, queueItems = 85)).shouldRebuild)

        val result = monitor.observe(snapshot(elapsed = 4_000, cameraFrames = 120, queueItems = 235))

        assertTrue(result.shouldRebuild)
    }

    @Test
    fun `sender progress prevents a rebuild despite camera input`() {
        val monitor = RtspSenderProgressMonitor()

        monitor.observe(snapshot(elapsed = 0, cameraFrames = 0, queueItems = 0))
        val result = monitor.observe(
            snapshot(elapsed = 2_000, cameraFrames = 60, queueItems = 85, sentVideoFrames = 60, bytes = 10_000),
        )

        assertFalse(result.shouldRebuild)
    }

    private fun snapshot(
        elapsed: Long,
        cameraFrames: Long,
        queueItems: Int,
        sentVideoFrames: Long = 0,
        bytes: Long = 0,
    ) = RtspSenderProgressMonitor.Snapshot(
        elapsedRealtimeMillis = elapsed,
        cameraFrames = cameraFrames,
        queueItems = queueItems,
        sentVideoFrames = sentVideoFrames,
        sentAudioFrames = 0,
        senderByteCounter = bytes,
    )
}
