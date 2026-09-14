package com.foresight.gateway.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import com.foresight.gateway.sensors.HeadingState
import kotlin.math.roundToInt

/** Compact shared compass overlay for both VISION and AUGMENTED_REALITY. */
class CompassRibbonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 255, 255, 255)
        strokeWidth = dp(1f)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dp(13f)
        textAlign = Paint.Align.CENTER
        setShadowLayer(dp(2f), 0f, dp(1f), Color.BLACK)
    }
    private val caretPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(244, 67, 54) }
    private var headingState = HeadingState.unavailable()

    init {
        contentDescription = "Phone heading compass"
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setHeadingState(state: HeadingState) {
        if (headingState == state) return
        headingState = state
        contentDescription = state.headingDegrees?.let { "Phone heading ${it.roundToInt()} degrees" }
            ?: "Phone heading unavailable"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val heading = headingState.headingDegrees
        if (heading == null) {
            canvas.drawText(
                "PHONE HEADING UNAVAILABLE",
                width / 2f,
                height / 2f - labelPaint.ascent() / 2f,
                labelPaint,
            )
            return
        }

        val centerX = width / 2f
        val tickBaseY = height - dp(8f)
        val pixelsPerDegree = width / (CompassRibbonModel.HALF_VISIBLE_SPAN_DEGREES * 2f)
        CompassRibbonModel.visibleTicks(heading).forEach { tick ->
            val x = centerX + tick.relativeDegrees * pixelsPerDegree
            val tickHeight = if (tick.isMajor) dp(18f) else dp(10f)
            canvas.drawLine(x, tickBaseY, x, tickBaseY - tickHeight, tickPaint)
            tick.label?.let { label ->
                canvas.drawText(label, x, dp(18f), labelPaint)
            }
        }

        val caret = Path().apply {
            moveTo(centerX, height - dp(2f))
            lineTo(centerX - dp(6f), height - dp(13f))
            lineTo(centerX + dp(6f), height - dp(13f))
            close()
        }
        canvas.drawPath(caret, caretPaint)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
