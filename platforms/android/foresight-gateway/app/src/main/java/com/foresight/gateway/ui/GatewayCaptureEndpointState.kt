package com.foresight.gateway.ui

/** The editable RTSP destination that must be read anew for every capture session. */
internal data class GatewayCaptureEndpointState(val rtspEndpoint: String = "") {
    fun update(value: String): GatewayCaptureEndpointState = copy(rtspEndpoint = value.trim())

    companion object {
        fun restore(savedEndpoint: String?): GatewayCaptureEndpointState =
            GatewayCaptureEndpointState(savedEndpoint?.trim().orEmpty())
    }
}
