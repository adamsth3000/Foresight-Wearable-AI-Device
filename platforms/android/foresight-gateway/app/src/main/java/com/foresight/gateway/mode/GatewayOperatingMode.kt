package com.foresight.gateway.mode

/** User-selected policy. Connectivity must never infer or mutate this value. */
enum class GatewayOperatingMode {
    LAB,
    FIELD;

    companion object {
        fun restore(persisted: String?): GatewayOperatingMode =
            entries.firstOrNull { it.name == persisted } ?: LAB
    }
}

/** Mode-only admission policy; transport reachability is deliberately not an input. */
internal object GatewayOperatingModePolicy {
    fun canStartCapture(mode: GatewayOperatingMode, rtspEndpoint: String): Boolean =
        mode == GatewayOperatingMode.FIELD || rtspEndpoint.isNotBlank()
}
