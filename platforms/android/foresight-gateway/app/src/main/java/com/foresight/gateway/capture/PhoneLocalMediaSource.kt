package com.foresight.gateway.capture


/**
 * C1 adapter for the validated RootEncoder-owned continuous recorder. Its start/stop remains part
 * of the existing phone capture lifecycle, so this adapter deliberately exposes observation only.
 */
internal class PhoneLocalMediaSource(
    private val publisher: PhonePublisherRuntime,
) {
    val mediaSourceId: LocalMediaSourceId = LocalMediaSourceId.PHONE_CAMERA

    fun currentRecordingContext(): LocalRecordingContext? = publisher.localRecordingContext()

    fun availability(): LocalMediaAvailability = currentRecordingContext()?.availability
        ?: LocalMediaAvailability.UNAVAILABLE
}
