package com.foresight.gateway.ui

import kotlin.math.ceil
import kotlin.math.floor

/** Pure circular-heading math used by the shared compass renderer. */
internal object CompassRibbonModel {
    const val TICK_SPACING_DEGREES = 15
    const val HALF_VISIBLE_SPAN_DEGREES = 90f

    data class Tick(
        val headingDegrees: Int,
        val relativeDegrees: Float,
        val label: String?,
    ) {
        val isMajor: Boolean
            get() = label != null
    }

    fun normalizeDegrees(value: Float): Float = ((value % 360f) + 360f) % 360f

    fun normalizeDegrees(value: Int): Int = ((value % 360) + 360) % 360

    /** Returns the continuous signed delta from [fromDegrees] to [toDegrees] in [-180, 180). */
    fun shortestAngularDelta(fromDegrees: Float, toDegrees: Float): Float =
        normalizeDegrees(toDegrees - fromDegrees + 180f) - 180f

    fun cardinalLabel(headingDegrees: Int): String? = when (normalizeDegrees(headingDegrees)) {
        0 -> "N"
        45 -> "NE"
        90 -> "E"
        135 -> "SE"
        180 -> "S"
        225 -> "SW"
        270 -> "W"
        315 -> "NW"
        else -> null
    }

    fun visibleTicks(
        centerHeadingDegrees: Float,
        halfSpanDegrees: Float = HALF_VISIBLE_SPAN_DEGREES,
        tickSpacingDegrees: Int = TICK_SPACING_DEGREES,
    ): List<Tick> {
        require(halfSpanDegrees > 0f)
        require(tickSpacingDegrees > 0)

        val center = normalizeDegrees(centerHeadingDegrees)
        val first = floor((center - halfSpanDegrees) / tickSpacingDegrees).toInt() * tickSpacingDegrees
        val last = ceil((center + halfSpanDegrees) / tickSpacingDegrees).toInt() * tickSpacingDegrees
        return (first..last step tickSpacingDegrees).map { rawHeading ->
            Tick(
                headingDegrees = normalizeDegrees(rawHeading),
                relativeDegrees = rawHeading - center,
                label = cardinalLabel(rawHeading),
            )
        }
    }
}
