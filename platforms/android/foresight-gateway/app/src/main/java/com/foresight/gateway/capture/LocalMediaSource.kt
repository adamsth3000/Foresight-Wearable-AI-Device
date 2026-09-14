package com.foresight.gateway.capture

/** Explicit identity for an app-private continuous-media producer. */
enum class LocalMediaSourceId {
    PHONE_CAMERA,
    GOPRO_RTMP,
}

/** Recording usability is intentionally distinct from live-source transport state. */
enum class LocalMediaAvailability {
    AVAILABLE,
    UNAVAILABLE,
    FINALIZING,
    INTERRUPTED,
    ERROR,
}

/** Relative, app-private location for a recording. Absolute paths never enter the ledger. */
data class LocalMediaLocation(
    val directoryName: String,
    val fileName: String,
) {
    init {
        require(directoryName.isNotBlank() && !directoryName.contains('/') && !directoryName.contains('\\'))
        require(fileName == java.io.File(fileName).name && fileName.endsWith(".mp4"))
    }

    companion object {
        fun phoneCamera(fileName: String) = LocalMediaLocation("recordings", fileName)
        fun goProRtmp(fileName: String) = LocalMediaLocation("gopro_ingest_recordings", fileName)
    }
}

/**
 * Links a source packet timestamp to Android's elapsed-realtime clock and the normalized MP4.
 * C1 persists this evidence only; C2 will decide how to map event boundaries from it.
 */
data class MediaTimelineAnchor(
    val androidMonotonicNanos: Long,
    val sourcePtsUs: Long,
    val mp4PtsUs: Long,
)

/** Narrow seam for C2. C1 does not route FIELD controls through this contract yet. */
interface LocalMediaSource {
    val mediaSourceId: LocalMediaSourceId
    fun availability(): LocalMediaAvailability
    fun currentRecordingContext(): LocalRecordingContext?
    fun startContinuousRecording(): LocalRecordingContext?
    fun stopContinuousRecording(): LocalRecordingContext?
}
