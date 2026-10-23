package com.caro.tratto.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RenderNode
import android.view.View
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import com.caro.tratto.export.PageRenderer
import com.caro.tratto.model.NoteStroke
import com.caro.tratto.model.PageImage
import com.caro.tratto.model.NotebookDocument
import com.caro.tratto.model.ToolKind
import android.graphics.RectF
import com.caro.tratto.data.PdfPages
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Disegna le pagine e i tratti finiti.
 *
 * Ogni pagina è un RenderNode registrato alla scala corrente (senza traslazione):
 * - pan/fling: si cambia solo la traslazione, nessuna ri-registrazione → fluido;
 * - pinch: si riusa la registrazione scalata, poi a gesto finito si ri-registra
 *   alla nuova scala (vettoriale nitido, antialiasing corretto del renderer mesh di Ink);
 * - nuovo tratto / gomma: si ri-registra solo la pagina toccata.
 */
internal class PagesView(context: Context, private val vp: Viewport) : View(context) {

    var doc: NotebookDocument? = null
    /** true durante il pinch: non ri-registrare a ogni frame. */
    var zooming = false
    /** Tratti finiti disegnati come path riempiti (AA di Skia, identico al PDF) invece che come mesh. */
    var pathRendering = false
        set(value) {
            field = value
            invalidateAllPages()
        }
    // collegamenti: una sottolineatura sottile azzurra, come un link, e una freccina ↗ alla fine
    private val dp = context.resources.displayMetrics.density
    private val linkLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xD93F7FD9.toInt()
    }
    private val arrowPath = android.graphics.Path()

    private fun drawLinks(canvas: Canvas, d: NotebookDocument, page: Int, left: Float, top: Float, s: Float) {
        val links = d.pages[page].links
        if (links.isEmpty()) return
        val a = 4f * dp
        for (l in links) {
            val x0 = left + l.x * s
            val x1 = left + (l.x + l.w) * s
            val y = top + (l.y + l.h) * s + 2f * dp
            canvas.drawLine(x0, y, x1, y, linkLine)
            // freccina ↗ appena dopo la fine della riga
            val cx = x1 + a * 1.6f
            val cy = y - a * 1.2f
            arrowPath.reset()
            arrowPath.moveTo(cx - a, cy + a)
            arrowPath.lineTo(cx + a, cy - a)
            arrowPath.moveTo(cx - a * 0.2f, cy - a)
            arrowPath.lineTo(cx + a, cy - a)
            arrowPath.lineTo(cx + a, cy + a * 0.2f)
            canvas.drawPath(arrowPath, linkLine)
        }
    }

    private val fillPath = android.graphics.Path()
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private fun drawStroke(c: Canvas, ns: NoteStroke, m: Matrix) {
        if (!pathRendering) {
            renderer.draw(c, ns.stroke, m)
            return
        }
        com.caro.tratto.export.PageRenderer.fillStroke(c, ns, fillPath, fillPaint)
    }

    private val renderer = CanvasStrokeRenderer.create()
    private val nodes = ArrayList<PageNode>()
    private val strokeMatrix = Matrix()

    // Selezione del lasso: esclusa dalla pagina e disegnata a parte con la trasformazione live.
    private var selPage = -1
    private var selList: List<NoteStroke> = emptyList()
    private var selSet: Set<NoteStroke> = emptySet()
    private var selImages: List<PageImage> = emptyList()
    private var selImageSet: Set<PageImage> = emptySet()

    /** Pagine del PDF importato (quaderni nati da un PDF); null = nessun PDF. */
    var pdfPages: com.caro.tratto.data.PdfPages? = null

    /** Bitmap delle immagini del quaderno (null = non disegnarle). */
    var imageProvider: ((String) -> android.graphics.Bitmap?)? = null
    private val selMatrix = Matrix()      // punti pagina → punti pagina
    private val selDraw = Matrix()

    fun setSelection(page: Int, strokes: List<NoteStroke>, images: List<PageImage> = emptyList()) {
        if (selPage >= 0) nodes.getOrNull(selPage)?.dirty = true
        selPage = page
        selList = strokes
        selSet = strokes.toIdentitySet()
        selImages = images
        selImageSet = images.toIdentitySet()
        selMatrix.reset()
        if (page >= 0) nodes.getOrNull(page)?.dirty = true
        invalidate()
    }

    fun setSelectionTransform(t: SelectionTransform) {
        selMatrix.setScale(t.k, t.k, t.ax, t.ay)
        selMatrix.postTranslate(t.dx, t.dy)
        invalidate()
    }

    private val shadowPaint = Paint().apply { color = 0x1A000000 }
    private val deskColor = Color.rgb(0xE6, 0xE9, 0xEE)

    /**
     * Due strati per pagina: [bg] = foglio o pagina del PDF, [node] = foto e inchiostro. In mezzo
     * può andare il ritaglio nitido del PDF (zoom forte) senza ri-registrare l'inchiostro.
     */
    private class PageNode {
        val bg = RenderNode("page-bg")
        val node = RenderNode("page")
        var recordedScale = 0f
        var dirty = true
        var bgDirty = true

        fun discard() {
            bg.discardDisplayList()
            node.discardDisplayList()
        }
    }

    fun invalidatePage(index: Int) {
        nodes.getOrNull(index)?.dirty = true
        invalidate()
    }

    /** È arrivata una resa migliore della pagina del PDF: basta ri-registrare il fondo. */
    private fun invalidatePageBackground(index: Int) {
        nodes.getOrNull(index)?.bgDirty = true
        invalidate()
    }

    fun invalidateAllPages() {
        nodes.forEach { it.dirty = true; it.bgDirty = true }
        details.clear()   // gli indici delle pagine possono essere cambiati
        detailKey = ""
        invalidate()
    }

    private fun syncNodes(count: Int) {
        while (nodes.size < count) nodes.add(PageNode())
        while (nodes.size > count) nodes.removeAt(nodes.size - 1).discard()
    }

    // ---- ritagli nitidi del PDF (zoom forte) ----

    /** Zona [rect] (coordinate pagina) della pagina [page] resa alla risoluzione dello schermo. */
    private class Detail(val page: Int, val rect: RectF, val bitmap: android.graphics.Bitmap)

    private val details = ArrayList<Detail>()
    private var detailKey = ""
    private val detailPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val detailDst = RectF()
    private val requestDetails = Runnable { requestDetails() }

    private fun viewKey() = "${vp.offsetX.toInt()}:${vp.offsetY.toInt()}:${vp.scale}:$width:$height"

    /**
     * Pagine del PDF ingrandite oltre la resa a pagina intera: si rende la sola parte visibile alla
     * risoluzione esatta. Parte a viewport ferma (pan/zoom finiti), il ritaglio vecchio resta finché
     * arriva il nuovo (è in coordinate pagina: segue pan e zoom).
     */
    private fun requestDetails() {
        val d = doc ?: return
        val pdf = pdfPages ?: return
        detailKey = viewKey()
        val s = vp.scale
        val batch = pdf.newRegionBatch()
        val needed = HashSet<Int>()
        for (i in d.pages.indices) {
            val pdfPage = d.pages[i].pdfPage
            if (pdfPage < 0) continue
            val top = vp.offsetY + vp.pageTop(i) * s
            if (top > height || top + vp.pageH * s < 0) continue
            val base = pdf.peek(pdfPage) ?: continue
            val fit = PdfPages.fitRect(base.widthPt, base.heightPt, vp.pageW, vp.pageH, RectF())
            if (fit.width() * s <= base.bitmap.width * 1.15f) continue   // la resa intera basta
            val vis = RectF(-vp.offsetX / s, -top / s, (width - vp.offsetX) / s, (height - top) / s)
            if (!vis.intersect(fit)) continue
            val outW = (vis.width() * s).roundToInt()
            val outH = (vis.height() * s).roundToInt()
            if (outW < 8 || outH < 8) continue
            needed.add(i)
            val k = fit.width() / base.widthPt
            val pdfRect = RectF((vis.left - fit.left) / k, (vis.top - fit.top) / k, (vis.right - fit.left) / k, (vis.bottom - fit.top) / k)
            pdf.renderRegionAsync(batch, pdfPage, pdfRect, outW, outH) { bmp ->
                if (doc !== d) return@renderRegionAsync
                details.removeAll { it.page == i }
                details.add(Detail(i, vis, bmp))
                invalidate()
            }
        }
        if (details.removeAll { it.page !in needed }) invalidate()
    }

    private fun drawDetails(canvas: Canvas, page: Int, s: Float) {
        for (det in details) {
            if (det.page != page) continue
            detailDst.set(det.rect.left * s, det.rect.top * s, det.rect.right * s, det.rect.bottom * s)
            canvas.drawBitmap(det.bitmap, null, detailDst, detailPaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(deskColor)
        val d = doc ?: return
        syncNodes(d.pages.size)
        val s = vp.scale
        val pageWpx = vp.pageW * s
        val pageHpx = vp.pageH * s
        for (i in d.pages.indices) {
            val top = vp.offsetY + vp.pageTop(i) * s
            val pn = nodes[i]
            if (top > height || top + pageHpx < 0) {
                // Fuori schermo: libera la display list delle pagine lontane.
                if (top > height * 3 || top + pageHpx < -height * 2f) {
                    pn.discard()
                    pn.dirty = true
                }
                continue
            }
            if (pn.dirty || !pn.node.hasDisplayList() || !pn.bg.hasDisplayList() || (!zooming && pn.recordedScale != s)) {
                record(d, i, pn, if (zooming && pn.recordedScale > 0f) pn.recordedScale else s)
            } else if (pn.bgDirty) {
                recordBackground(d, i, pn, pn.recordedScale)
            }
            val left = vp.offsetX
            canvas.drawRect(left + 2f, top + 3f, left + pageWpx + 2f, top + pageHpx + 3f, shadowPaint)
            canvas.save()
            canvas.translate(left, top)
            canvas.clipRect(0f, 0f, pageWpx, pageHpx)
            val k = s / pn.recordedScale
            canvas.save()
            if (k != 1f) canvas.scale(k, k)
            canvas.drawRenderNode(pn.bg)
            canvas.restore()
            if (details.isNotEmpty()) drawDetails(canvas, i, s)
            if (k != 1f) canvas.scale(k, k)
            canvas.drawRenderNode(pn.node)
            canvas.restore()
            drawLinks(canvas, d, i, left, top, s)
            if (i == selPage && (selList.isNotEmpty() || selImages.isNotEmpty())) drawSelection(canvas, left, top, s)
        }
        if (pdfPages != null && !zooming && viewKey() != detailKey) {
            // a viewport ferma per un attimo (fine pan/zoom) si chiedono i ritagli nitidi
            removeCallbacks(requestDetails)
            postDelayed(requestDetails, 140)
        }
    }

    /** Tratti selezionati, sopra la pagina, con la trasformazione corrente (pochi tratti: diretto). */
    private fun drawSelection(canvas: Canvas, left: Float, top: Float, s: Float) {
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(s, s)
        canvas.concat(selMatrix)
        selDraw.set(selMatrix)
        selDraw.postScale(s, s)
        imageProvider?.let { p -> PageRenderer.drawImages(canvas, selImages) { p(it.file) } }
        for (ns in selList) if (ns.tool == ToolKind.HIGHLIGHTER) drawStroke(canvas, ns, selDraw)
        for (ns in selList) if (ns.tool != ToolKind.HIGHLIGHTER) drawStroke(canvas, ns, selDraw)
        canvas.restore()
    }

    /**
     * Fondo della pagina. La pagina del PDF intera si chiede al massimo un po' più larga dello
     * schermo: oltre ci pensano i ritagli nitidi (una pagina intera al 400% sarebbe enorme).
     */
    private fun recordBackground(d: NotebookDocument, index: Int, pn: PageNode, s: Float) {
        val bg = pn.bg
        bg.setPosition(0, 0, ceil(vp.pageW * s).toInt(), ceil(vp.pageH * s).toInt())
        bg.clipToBounds = true
        val c = bg.beginRecording()
        try {
            c.scale(s, s)
            val pdfProvider = pdfPages
            val baseWidth = minOf(ceil(vp.pageW * s).toInt(), (maxOf(width, 600) * 1.25f).toInt())
            PageRenderer.drawBackground(c, d, index, 0f, pdfProvider?.let { p ->
                { pdfPage: Int -> p.get(pdfPage, baseWidth) { invalidatePageBackground(index) } }
            })
        } finally {
            bg.endRecording()
        }
        pn.bgDirty = false
    }

    private fun record(d: NotebookDocument, index: Int, pn: PageNode, s: Float) {
        recordBackground(d, index, pn, s)
        val node = pn.node
        node.setPosition(0, 0, ceil(vp.pageW * s).toInt(), ceil(vp.pageH * s).toInt())
        node.clipToBounds = true
        val c = node.beginRecording()
        try {
            // Il canvas va portato in coordinate pagina: CanvasStrokeRenderer NON applica
            // strokeToScreenTransform al canvas, la usa solo per l'antialiasing.
            c.save()
            c.scale(s, s)
            imageProvider?.let { provider ->
                val imgs = d.pages[index].images
                PageRenderer.drawImages(c, if (index == selPage) imgs.filter { it !in selImageSet } else imgs) { provider(it.file) }
            }
            strokeMatrix.setScale(s, s)
            val strokes = d.pages[index].strokes
            // Evidenziatore sotto, poi il resto (stesso ordine di PDF e PNG).
            val skip = if (index == selPage) selSet else emptySet()
            for (ns in strokes) if (ns.tool == ToolKind.HIGHLIGHTER && ns !in skip) drawStroke(c, ns, strokeMatrix)
            for (ns in strokes) if (ns.tool != ToolKind.HIGHLIGHTER && ns !in skip) drawStroke(c, ns, strokeMatrix)
            c.restore()
        } finally {
            node.endRecording()
        }
        pn.recordedScale = s
        pn.dirty = false
    }
}
