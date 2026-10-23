package com.caro.tratto.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.View

/** Cerchietto che segue la S Pen in hover: mostra dove e quanto grande scriverai/cancellerai. */
internal class HoverView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
        color = 0x99333B48.toInt()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22333B48 }

    private var x = 0f
    private var y = 0f
    private var radius = 0f
    private var visible = false

    // --- indicatore zoom (pillola in alto al centro) ---
    private var zoomText: String? = null
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xD91F2A37.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 15f * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private val hideZoom = Runnable {
        zoomText = null
        invalidate()
    }

    fun showZoom(percent: Int) {
        zoomText = "$percent%"
        removeCallbacks(hideZoom)
        invalidate()
    }

    fun hideZoomSoon() {
        removeCallbacks(hideZoom)
        postDelayed(hideZoom, 900)
    }

    // --- lasso e riquadro di selezione ---
    private var lasso: Path? = null
    private val selRect = RectF()
    private var hasSel = false
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = 0xFF1E63D6.toInt()
        pathEffect = DashPathEffect(floatArrayOf(7f * density, 5f * density), 0f)
    }
    private val selFill = Paint().apply { color = 0x141E63D6 }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF1E63D6.toInt()
    }
    val handleRadius = 9f * density

    fun setLasso(path: Path?) {
        lasso = path
        invalidate()
    }

    fun setSelection(rect: RectF?) {
        hasSel = rect != null
        if (rect != null) selRect.set(rect)
        invalidate()
    }

    // --- anteprima della linea dritta ---
    private var lineOn = false
    private val linePts = FloatArray(4)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    fun showLine(x0: Float, y0: Float, x1: Float, y1: Float, widthPx: Float, color: Int) {
        linePts[0] = x0; linePts[1] = y0; linePts[2] = x1; linePts[3] = y1
        linePaint.strokeWidth = widthPx.coerceAtLeast(1f)
        linePaint.color = color
        lineOn = true
        invalidate()
    }

    fun hideLine() {
        if (!lineOn) return
        lineOn = false
        invalidate()
    }

    /** Carta scura (nera, blu progetto): cerchietto e lasso chiari, altrimenti non si vedono. */
    var darkPaper = false
        set(value) {
            if (field == value) return
            field = value
            ring.color = if (value) 0xCCFFFFFF.toInt() else 0x99333B48.toInt()
            fill.color = if (value) 0x33FFFFFF else 0x22333B48
            dashPaint.color = if (value) 0xFF8CC4FF.toInt() else 0xFF1E63D6.toInt()
            selFill.color = if (value) 0x228CC4FF else 0x141E63D6
            invalidate()
        }

    fun show(x: Float, y: Float, radiusPx: Float) {
        this.x = x
        this.y = y
        radius = radiusPx.coerceAtLeast(2.5f * density)
        visible = true
        invalidate()
    }

    fun hide() {
        if (!visible) return
        visible = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        zoomText?.let { text ->
            val w = textPaint.measureText(text) + 28f * density
            val h = 34f * density
            val cx = width / 2f
            val top = 14f * density
            canvas.drawRoundRect(cx - w / 2, top, cx + w / 2, top + h, h / 2, h / 2, pillPaint)
            canvas.drawText(text, cx, top + h / 2 - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
        }
        lasso?.let { canvas.drawPath(it, dashPaint) }
        if (lineOn) canvas.drawLine(linePts[0], linePts[1], linePts[2], linePts[3], linePaint)
        if (hasSel) {
            canvas.drawRect(selRect, selFill)
            canvas.drawRect(selRect, dashPaint)
            canvas.drawCircle(selRect.right, selRect.bottom, handleRadius, handleFill)
            canvas.drawCircle(selRect.right, selRect.bottom, handleRadius, handleRing)
        }
        if (!visible) return
        canvas.drawCircle(x, y, radius, fill)
        canvas.drawCircle(x, y, radius, ring)
    }
}
