package com.foresight.gateway.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.foresight.gateway.vision.PreviewCoordinateTransform
import com.foresight.gateway.vision.gesture.GestureTargetPresentation
import kotlin.math.max

/** Draw-only marker for the one subject selected by the future gesture-targeting runtime. */
class GestureTargetOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 206, 74)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 3f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = resources.displayMetrics.density * 14f
        setShadowLayer(resources.displayMetrics.density * 2f, 0f, 0f, Color.BLACK)
    }
    private var presentation: GestureTargetPresentation? = null

    fun setPresentation(value: GestureTargetPresentation?) {
        if (presentation == value) return
        presentation = value
        invalidate()
    }

    fun clear() = setPresentation(null)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val value = presentation ?: return
        if (width <= 0 || height <= 0) return
        // The target model is source-normalized; map it through the same preview transform as boxes.
        val transform = PreviewCoordinateTransform(1, 1, width, height)
        val box = transform.map(value.boundingBox)
        val centerX = (box.left + box.right) / 2f
        val centerY = (box.top + box.bottom) / 2f
        val radius = max(resources.displayMetrics.density * 22f, max(box.right - box.left, box.bottom - box.top) * 0.55f)
        canvas.drawCircle(centerX, centerY, radius, markerPaint)
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, markerPaint)
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, markerPaint)
        canvas.drawText(
            "${value.label} ${(value.confidence * 100).toInt()}%",
            centerX - radius,
            (centerY - radius - resources.displayMetrics.density * 6f).coerceAtLeast(labelPaint.textSize),
            labelPaint,
        )
    }
}
