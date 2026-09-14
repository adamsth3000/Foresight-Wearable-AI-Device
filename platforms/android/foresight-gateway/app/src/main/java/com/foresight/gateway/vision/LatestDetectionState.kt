package com.foresight.gateway.vision

/** Thread-safe, presentation-independent access to the newest Vision result. */
class LatestDetectionState(
    private val elapsedRealtimeNanos: () -> Long,
) {
    @Volatile
    private var latest: DetectionSnapshot? = null

    fun publish(snapshot: DetectionSnapshot) {
        latest = snapshot
    }

    fun clear() {
        latest = null
    }

    fun freshSnapshot(maxAgeNanos: Long): DetectionSnapshot? {
        require(maxAgeNanos >= 0) { "Maximum detection age cannot be negative." }
        val snapshot = latest ?: return null
        return snapshot.takeIf { elapsedRealtimeNanos() - it.captureElapsedRealtimeNanos <= maxAgeNanos }
    }

    fun latestSnapshot(): DetectionSnapshot? = latest
}
