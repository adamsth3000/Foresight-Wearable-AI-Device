package com.foresight.gateway.ui

/** Presentation-only choice; intentionally independent of the LAB/FIELD capture policy. */
enum class GatewayVisualizationMode {
    STANDARD,
    VISION,
    AUGMENTED_REALITY;

    val showsCompass: Boolean
        get() = this != STANDARD

    val displayName: String
        get() = when (this) {
            STANDARD -> "STANDARD"
            VISION -> "VISION"
            AUGMENTED_REALITY -> "AUGMENTED REALITY"
        }

    companion object {
        fun restore(persisted: String?): GatewayVisualizationMode =
            entries.firstOrNull { it.name == persisted } ?: STANDARD
    }
}
