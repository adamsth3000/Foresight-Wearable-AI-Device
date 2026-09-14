package com.foresight.gateway.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.foresight.gateway.vision.DetectionSnapshot
import com.foresight.gateway.vision.PreviewCoordinateTransform

/** Draw-only overlay for the newest accepted local Vision response. */
class VisionDetectionOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(80, 255, 190)
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = resources.displayMetrics.density * 14f
        setShadowLayer(resources.displayMetrics.density * 2f, 0f, 0f, Color.BLACK)
    }
    private var snapshot: DetectionSnapshot? = null

    fun setSnapshot(value: DetectionSnapshot) {
        if (snapshot == value) return
        snapshot = value
        invalidate()
    }

    fun clear() {
        if (snapshot == null) return
        snapshot = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val value = snapshot ?: return
        if (width <= 0 || height <= 0) return
        val transform = PreviewCoordinateTransform(value.sourceWidth, value.sourceHeight, width, height)
        value.detections.forEach { detection ->
            val box = transform.map(detection.boundingBox)
            canvas.drawRect(RectF(box.left, box.top, box.right, box.bottom), boxPaint)
            canvas.drawText(
                "${detection.label} ${(detection.confidence * 100).toInt()}%",
                box.left,
                (box.top - resources.displayMetrics.density * 5f).coerceAtLeast(labelPaint.textSize),
                labelPaint,
            )
        }
    }
}
