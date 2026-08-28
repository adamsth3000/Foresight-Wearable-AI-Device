package com.foresight.gateway.metadata

/** Source-neutral identity for a media publisher. */
data class MediaSourceDescriptor(
    val sourceId: String = "galaxy_s24_fe",
    val sourceDevice: String = "galaxy_s24_fe",
    val cameraSource: String = "phone_rear_camera",
    val microphoneSource: String = "phone_microphone",
    val locationSource: String = "android_location_manager",
    val imuSource: String = "android_sensor_manager",
    val transport: String = "rtsp_tcp",
)
