package com.caro.tratto.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.ink.geometry.populateOutlines
import com.caro.tratto.data.PdfPages
import com.caro.tratto.ink.Brushes
import com.caro.tratto.model.NoteStroke
import com.caro.tratto.model.NotebookDocument
import com.caro.tratto.model.PageImage
import com.caro.tratto.model.PageBackgroundPainter
import com.caro.tratto.model.ToolKind
import kotlin.math.roundToInt

/**
 * Disegna una pagina SOLO con primitive vettoriali (linee e path riempiti), in coordinate
 * pagina (punti PDF). Su PdfDocument diventa vettoriale puro; su Bitmap dà il PNG.
 *
 * Ogni tratto è il contorno della sua geometria Ink (PartitionedMesh → Path), riempito con
 * il colore del pennello: è lo stesso metodo del CanvasPathRenderer di Ink (fill WINDING).
 * Non si usa il renderer a mesh: il canvas di PdfDocument non supporta drawVertices/mesh.
 */
object PageRenderer {

    /**
     * @param minLinePt spessore minimo delle righe di sfondo (miniature: ~1 px).
     * @param images bitmap delle immagini della pagina (sotto l'inchiostro); null = non disegnarle.
     */
    fun drawPageVector(
        canvas: Canvas,
        doc: NotebookDocument,
        pageIndex: Int,
        minLinePt: Float = 0f,
        images: ((PageImage) -> Bitmap?)? = null,
        pdf: ((Int) -> PdfPages.Rendered?)? = null,
    ) {
        drawBackground(canvas, doc, pageIndex, minLinePt, pdf)
        if (images != null) drawImages(canvas, doc.pages[pageIndex].images, images)
        val strokes = doc.pages[pageIndex].strokes
        val path = Path()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        canvas.save()
        canvas.clipRect(0f, 0f, doc.format.widthPt.toFloat(), doc.format.heightPt.toFloat())
        for (ns in strokes) if (ns.tool == ToolKind.HIGHLIGHTER) drawStroke(canvas, ns, path, paint)
        for (ns in strokes) if (ns.tool != ToolKind.HIGHLIGHTER) drawStroke(canvas, ns, path, paint)
        canvas.restore()
    }

    private val pdfPaper = Paint().apply { color = Color.WHITE }
    private val pdfPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /**
     * Sfondo della pagina: il foglio col suo motivo, oppure (pagina di un PDF importato) un foglio
     * bianco con sopra la pagina del PDF adattata e centrata. [pdf] null o senza resa = solo bianco.
     */
    fun drawBackground(canvas: Canvas, doc: NotebookDocument, pageIndex: Int, minLinePt: Float, pdf: ((Int) -> PdfPages.Rendered?)?) {
        val pdfPage = doc.pages[pageIndex].pdfPage
        if (pdfPage < 0) {
            PageBackgroundPainter.paint(canvas, doc.format, doc.style, minLinePt = minLinePt)
            return
        }
        val w = doc.format.widthPt.toFloat()
        val h = doc.format.heightPt.toFloat()
        canvas.drawRect(0f, 0f, w, h, pdfPaper)
        val r = pdf?.invoke(pdfPage) ?: return
        canvas.drawBitmap(r.bitmap, null, PdfPages.fitRect(r.widthPt, r.heightPt, w, h, RectF()), pdfPaint)
    }

    /**
     * Immagini della pagina nei loro riquadri (coordinate pagina). Niente stato condiviso: schermo
     * ed export possono disegnare insieme senza aspettarsi (prima un export lento bloccava lo schermo).
     */
    fun drawImages(canvas: Canvas, list: List<PageImage>, bitmaps: (PageImage) -> Bitmap?) {
        if (list.isEmpty()) return
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val rect = RectF()
        for (img in list) {
            val bmp = bitmaps(img) ?: continue
            rect.set(img.x, img.y, img.x + img.w, img.y + img.h)
            canvas.drawBitmap(bmp, null, rect, paint)
        }
    }

    /** Miniatura della pagina larga [widthPx] pixel. */
    fun thumbnail(
        doc: NotebookDocument,
        pageIndex: Int,
        widthPx: Int,
        images: ((PageImage) -> Bitmap?)? = null,
        pdf: ((Int) -> PdfPages.Rendered?)? = null,
    ): Bitmap {
        val k = widthPx / doc.format.widthPt.toFloat()
        val h = (doc.format.heightPt * k).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(k, k)
        drawPageVector(c, doc, pageIndex, minLinePt = 1f / k, images = images, pdf = pdf)
        return bmp
    }

    private fun drawStroke(canvas: Canvas, ns: NoteStroke, path: Path, paint: Paint) = fillStroke(canvas, ns, path, paint)

    /** Riempie il contorno del tratto (coordinate pagina) con il colore del pennello. */
    fun fillStroke(canvas: Canvas, ns: NoteStroke, path: Path, paint: Paint) {
        val shape = ns.stroke.shape
        val color = ns.stroke.brush.colorIntArgb
        val alpha = ((color ushr 24) * Brushes.opacityMultiplier(ns.tool)).toInt().coerceIn(0, 255)
        paint.color = (color and 0x00FFFFFF) or (alpha shl 24)
        for (group in 0 until shape.getRenderGroupCount()) {
            shape.populateOutlines(group, path)
            if (!path.isEmpty) canvas.drawPath(path, paint)
        }
    }
}
