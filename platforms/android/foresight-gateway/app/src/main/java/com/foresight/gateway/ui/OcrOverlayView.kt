package com.foresight.gateway.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.foresight.gateway.vision.OcrObservation
import com.foresight.gateway.vision.PreviewCoordinateTransform

/** Draw-only transparent presentation of accepted local OCR lines. */
class OcrOverlayView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 216, 93); style = Paint.Style.STROKE; strokeWidth = resources.displayMetrics.density }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = resources.displayMetrics.density * 11f; setShadowLayer(resources.displayMetrics.density * 2f, 0f, 0f, Color.BLACK) }
    private var observation: OcrObservation? = null

    fun setObservation(value: OcrObservation) { if (observation != value) { observation = value; invalidate() } }
    fun clear() { if (observation != null) { observation = null; invalidate() } }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val value = observation ?: return
        if (width <= 0 || height <= 0) return
        val transform = PreviewCoordinateTransform(value.sourceWidth, value.sourceHeight, width, height)
        value.regions.forEach { region ->
            val box = transform.map(region.normalizedBoundingBox)
            canvas.drawRect(RectF(box.left, box.top, box.right, box.bottom), boxPaint)
            canvas.drawText(region.text, box.left, (box.top - resources.displayMetrics.density * 3f).coerceAtLeast(textPaint.textSize), textPaint)
        }
    }
}
