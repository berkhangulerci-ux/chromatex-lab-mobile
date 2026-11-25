package org.chromatex.lab.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import org.chromatex.lab.R
import org.chromatex.lab.data.CameraOptics

/**
 * Tek sarı kare = 100mm kumaş deliği.
 * İkinci (renk) kare kaldırıldı — oran kavgası (WiFi manuel: delik oturunca renk kesiliyor).
 */
class FabricWindowOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val holeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f * resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.overlay_stroke)
    }

    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0x66000000
    }

    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 13f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private val bigArrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFEE58.toInt()
        textSize = 96f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    private val bigStatusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 28f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val hole = RectF()

    var guideHint: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var bigArrow: String = ""
        set(value) {
            field = value
            invalidate()
        }

    var flashMode: Int = 0
        set(value) {
            field = value
            invalidate()
        }

    var okReady: Boolean = false
        set(value) {
            field = value
            holeStroke.color = ContextCompat.getColor(
                context,
                if (value) R.color.ok else R.color.overlay_stroke,
            )
            holeStroke.strokeWidth = (if (value) 8f else 6f) * resources.displayMetrics.density
            invalidate()
        }

    /** Sarı delik — ekran kısa kenarına oran */
    var windowFraction = CameraOptics.HOLE_FILL_FRAC
        set(value) {
            field = value.coerceIn(0.35f, 0.44f)
            invalidate()
        }

    fun windowRect(): RectF = RectF(hole)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // PreviewView FIT_CENTER (3:4 dikey tam sensör) içine oturan gerçek kare kısa kenarı.
        val fittedW = minOf(width.toFloat(), height.toFloat() * 3f / 4f)
        val fittedH = minOf(height.toFloat(), width.toFloat() * 4f / 3f)
        val side = minOf(fittedW, fittedH) * windowFraction
        val left = (width - side) / 2f
        val top = (height - side) / 2f
        hole.set(left, top, left + side, top + side)

        when (flashMode) {
            1 -> {
                flashPaint.color = 0x55DC2626
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
            }
            2 -> {
                flashPaint.color = 0x55059669
                canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashPaint)
            }
        }

        canvas.drawRect(0f, 0f, width.toFloat(), hole.top, dim)
        canvas.drawRect(0f, hole.bottom, width.toFloat(), height.toFloat(), dim)
        canvas.drawRect(0f, hole.top, hole.left, hole.bottom, dim)
        canvas.drawRect(hole.right, hole.top, width.toFloat(), hole.bottom, dim)
        canvas.drawRect(hole, holeStroke)

        canvas.drawText(
            "SARI = 100mm kumas deligi — bunu oturtun",
            hole.centerX(),
            hole.top - 12f,
            labelPaint,
        )

        if (bigArrow.isNotBlank()) {
            bigArrowPaint.color = if (okReady) 0xFF34D399.toInt() else 0xFFFFEE58.toInt()
            canvas.drawText(bigArrow, width / 2f, height / 2f + bigArrowPaint.textSize * 0.35f, bigArrowPaint)
        }

        if (guideHint.isNotBlank()) {
            canvas.drawText(
                guideHint.take(32),
                width / 2f,
                hole.bottom + 40f * resources.displayMetrics.density,
                bigStatusPaint,
            )
        }
    }
}
