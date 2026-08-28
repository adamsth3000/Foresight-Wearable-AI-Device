package com.foresight.gateway.metadata

/** Source-neutral identity for a media publisher. */
data class MediaSourceDescriptor(
    val sourceDevice: String = "galaxy_s24_fe",
    val cameraSource: String = "phone_rear_camera",
    val microphoneSource: String = "phone_microphone",
    val transport: String = "rtsp_tcp",
)
