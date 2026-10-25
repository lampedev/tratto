package com.caro.tratto.model

import android.graphics.Canvas
import android.graphics.Paint

/**
 * Disegna il foglio (colore carta + motivo) in coordinate locali della pagina (punti PDF)
 * su qualunque Canvas: schermo, PNG, PdfDocument (dove resta vettoriale) e anteprime.
 * Unico punto di verità: editor, export e miniature usano SOLO questo.
 */
object PageBackgroundPainter {
    private const val PT_PER_MM = 72f / 25.4f
    private const val TOP_MARGIN_MM = 20f
    private const val MARGIN_LINE_MM = 25f

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paperPaint = Paint().apply { style = Paint.Style.FILL }

    /**
     * @param minLinePt spessore minimo delle linee in punti pagina: le miniature passano
     *   l'equivalente di 1 px, altrimenti le righe sottili sparirebbero.
     */
    @Synchronized   // Paint condivisi: schermo (main) ed export (IO) possono disegnare insieme
    fun paint(
        canvas: Canvas,
        format: PageFormat,
        style: PageStyle,
        drawPaper: Boolean = true,
        minLinePt: Float = 0f,
    ) {
        val w = format.widthPt.toFloat()
        val h = format.heightPt.toFloat()
        val paper = style.paperArgb
        if (drawPaper) {
            // drawRect e non drawColor: drawColor riempie tutta la clip corrente (in una
            // miniatura Compose coprirebbe l'intera finestra).
            paperPaint.color = paper
            canvas.drawRect(0f, 0f, w, h, paperPaint)
        }

        // righe di contrasto calcolate sul colore della carta (opache: gli incroci dei quadretti
        // non si scuriscono): blu-grigio su carta chiara, bianco attenuato su nera e blu progetto
        if (style.darkPaper) {
            linePaint.color = mix(paper, 0xFFFFFFFF.toInt(), 0.32f)
            accentPaint.color = mix(paper, 0xFFFF8A80.toInt(), 0.6f)
            dotPaint.color = mix(paper, 0xFFFFFFFF.toInt(), 0.45f)
        } else {
            linePaint.color = mix(paper, 0xFF2A4E80.toInt(), 0.24f)
            accentPaint.color = mix(paper, 0xFFD23C3C.toInt(), 0.45f)
            dotPaint.color = mix(paper, 0xFF2A3F5C.toInt(), 0.38f)
        }
        linePaint.strokeWidth = maxOf(0.5f, minLinePt)
        accentPaint.strokeWidth = maxOf(0.8f, minLinePt)

        // fogli di Samsung Notes: unità = lato corto / 360, passo = misura × 1,35 unità,
        // primo motivo a 10 unità dall'alto; i puntini sono un po' più radi in orizzontale
        val samsung = style.spacing.samsungSize
        val unit = minOf(w, h) / 360f
        val lineStep = if (samsung > 0) samsung * 1.35f * unit else style.spacing.linesMm * PT_PER_MM
        val gridStep = if (samsung > 0) lineStep else style.spacing.gridMm * PT_PER_MM
        val top = if (samsung > 0) 10.25f * unit else TOP_MARGIN_MM * PT_PER_MM
        val gridTop = if (samsung > 0) top else gridStep
        val dotTop = if (samsung > 0) 11f * unit else gridStep
        val dotStepX = if (samsung > 0) gridStep + 1.5f * unit else gridStep

        when (style.background) {
            PageBackground.BLANK -> Unit
            PageBackground.LINES -> horizontalLines(canvas, 0f, w, top, h - 8f, lineStep)
            PageBackground.GRID -> {
                var x = gridStep
                while (x < w) { canvas.drawLine(x, 0f, x, h, linePaint); x += gridStep }
                var y = gridTop
                while (y < h) { canvas.drawLine(0f, y, w, y, linePaint); y += gridStep }
            }
            PageBackground.DOTS -> {
                val r = maxOf(0.75f, minLinePt * 0.9f)
                var y = dotTop
                while (y < h) {
                    var x = if (samsung > 0) 0f else gridStep
                    while (x < w) { canvas.drawCircle(x, y, r, dotPaint); x += dotStepX }
                    y += gridStep
                }
            }
            PageBackground.CORNELL -> {
                // Metodo Cornell: colonna parole chiave a sinistra, riassunto in fondo.
                val cueX = w * 0.28f
                val summaryY = h * 0.80f
                horizontalLines(canvas, 0f, w, top, summaryY - 4f, lineStep)
                canvas.drawLine(0f, top - lineStep * 0.5f, w, top - lineStep * 0.5f, accentPaint)
                canvas.drawLine(cueX, top - lineStep * 0.5f, cueX, summaryY, accentPaint)
                canvas.drawLine(0f, summaryY, w, summaryY, accentPaint)
            }
        }
        if (style.marginLine && style.background != PageBackground.CORNELL) {
            val x = MARGIN_LINE_MM * PT_PER_MM
            canvas.drawLine(x, 0f, x, h, accentPaint)
        }
    }

    /** [over] steso su [base] con opacità [a], risultato opaco. */
    private fun mix(base: Int, over: Int, a: Float): Int {
        fun ch(shift: Int) = ((base shr shift and 0xFF) * (1 - a) + (over shr shift and 0xFF) * a).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun horizontalLines(canvas: Canvas, x0: Float, x1: Float, from: Float, to: Float, step: Float) {
        var y = from
        while (y < to) {
            canvas.drawLine(x0, y, x1, y, linePaint)
            y += step
        }
    }
}
