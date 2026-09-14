package com.foresight.gateway.sensors

/**
 * Phone-facing, magnetic-north-referenced heading for presentation only.
 *
 * This is a temporary wearer/body-heading proxy. It does not establish the GoPro optical-axis
 * direction because a mounted GoPro can have an independent yaw offset.
 */
data class HeadingState(
    val headingDegrees: Float?,
    val sensorAccuracy: Int?,
    val timestampElapsedRealtimeNanos: Long?,
) {
    init {
        require(headingDegrees == null || headingDegrees in 0f..<360f) {
            "headingDegrees must be normalized to [0, 360)"
        }
    }

    val isAvailable: Boolean
        get() = headingDegrees != null

    companion object {
        fun unavailable() = HeadingState(
            headingDegrees = null,
            sensorAccuracy = null,
            timestampElapsedRealtimeNanos = null,
        )
    }
}
