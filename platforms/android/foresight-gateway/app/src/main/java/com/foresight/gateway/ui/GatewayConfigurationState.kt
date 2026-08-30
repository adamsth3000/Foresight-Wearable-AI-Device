package com.foresight.gateway.ui

/** One authoritative event-control base URL, kept independent from the RTSP destination. */
internal data class GatewayConfigurationState(val controlBaseUrl: String = "") {
    fun updateControlBaseUrl(value: String): GatewayConfigurationState = copy(controlBaseUrl = value.trim())

    companion object {
        fun restore(savedControlBaseUrl: String?, savedTelemetryBaseUrl: String?): GatewayConfigurationState =
            GatewayConfigurationState(savedControlBaseUrl?.trim().takeUnless { it.isNullOrEmpty() }
                ?: savedTelemetryBaseUrl?.trim().orEmpty())
    }
}
