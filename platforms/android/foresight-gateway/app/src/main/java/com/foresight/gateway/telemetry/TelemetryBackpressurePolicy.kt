package com.foresight.gateway.telemetry

/** Pure timing policy for bounded, best-effort cellular telemetry delivery. */
internal class TelemetryBackpressurePolicy {
    private val lastRetainedTimestampNanos = mutableMapOf<String, Long>()
    private var retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS

    fun retain(recordType: String, timestampNanos: Long?): Boolean {
        if (recordType != "accelerometer" && recordType != "gyroscope") return true
        val previous = lastRetainedTimestampNanos[recordType]
        if (previous != null && timestampNanos != null && timestampNanos - previous < IMU_MIN_INTERVAL_NANOS) {
            return false
        }
        if (timestampNanos != null) lastRetainedTimestampNanos[recordType] = timestampNanos
        return true
    }

    fun normalUploadDelayMillis(nowMillis: Long, lastUploadMillis: Long): Long =
        (MIN_UPLOAD_INTERVAL_MILLIS - (nowMillis - lastUploadMillis)).coerceAtLeast(0L)

    fun nextFailureDelayMillis(): Long = retryDelayMillis.also {
        retryDelayMillis = (retryDelayMillis * 2).coerceAtMost(MAX_RETRY_DELAY_MILLIS)
    }

    fun recordSuccess() {
        retryDelayMillis = INITIAL_RETRY_DELAY_MILLIS
    }

    companion object {
        const val IMU_MIN_INTERVAL_NANOS = 100_000_000L // 10 Hz retained per IMU stream.
        const val MIN_UPLOAD_INTERVAL_MILLIS = 1_000L
        const val INITIAL_RETRY_DELAY_MILLIS = 1_000L
        const val MAX_RETRY_DELAY_MILLIS = 30_000L
    }
}
