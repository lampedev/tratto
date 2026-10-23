package com.caro.tratto.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PathIterator
import android.graphics.RectF
import androidx.ink.geometry.populateOutlines
import com.caro.tratto.data.PdfPages
import com.caro.tratto.ink.Brushes
import com.caro.tratto.model.NoteStroke
import com.caro.tratto.model.NotebookDocument
import com.caro.tratto.model.PageImage
import com.caro.tratto.model.PageModel
import com.caro.tratto.model.ToolKind
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

/**
 * Export di un quaderno nato da un PDF: si riparte dal PDF originale (testo, scansioni, link restano
 * identici, niente ricompressione) e sopra ogni pagina si aggiungono foto e inchiostro come
 * tracciati vettoriali riempiti (stesso contorno Ink dello schermo e degli altri PDF).
 * Le pagine nuove inserite nel quaderno si disegnano come negli altri quaderni e si innestano.
 * L'ordine delle pagine segue il quaderno (pagine spostate, duplicate, eliminate).
 */
internal object PdfAnnotator {

    fun export(
        context: Context,
        doc: NotebookDocument,
        source: File,
        out: File,
        indices: List<Int>,
        images: (PageImage) -> Bitmap?,
        writeBlankPages: (List<Int>, File) -> Unit,
    ) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val tmpDir = File(context.cacheDir, "pdfbox").apply { mkdirs() }
        val memory = { MemoryUsageSetting.setupMixed(48L shl 20).setTempDir(tmpDir) }
        PDDocument.load(source, memory()).use { pdf ->
            pdf.isAllSecurityToBeRemoved = true   // PDF con permessi (non si può "modificare"): li togliamo
            val originals = pdf.pages.toList()
            val blanks = indices.filter { doc.pages[it].pdfPage !in originals.indices }
            val blankFile = File(tmpDir, "nuove.pdf")
            if (blanks.isNotEmpty()) writeBlankPages(blanks, blankFile)
            val blankDoc = if (blanks.isNotEmpty()) PDDocument.load(blankFile, memory()) else null
            try {
                val used = HashSet<Int>()
                val target = ArrayList<PDPage>(indices.size)
                var nextBlank = 0
                for (i in indices) {
                    val page = doc.pages[i]
                    val p = if (page.pdfPage in originals.indices) {
                        val orig = originals[page.pdfPage]
                        // pagina duplicata nel quaderno: copia a parte, perché l'inchiostro è diverso
                        val p = if (used.add(page.pdfPage)) orig else pdf.importPage(orig).also { pdf.pages.remove(it) }
                        drawInk(pdf, p, doc, page, images)
                        p
                    } else {
                        pdf.importPage(blankDoc!!.getPage(nextBlank++)).also { pdf.pages.remove(it) }
                    }
                    target.add(p)
                }
                val tree = pdf.pages
                for (p in originals) tree.remove(p)
                for (p in target) tree.add(p)
                if (used.size < originals.size) {
                    // pagine tolte: segnalibri, struttura e destinazioni le richiamerebbero tutte nel file
                    val catalog = pdf.documentCatalog.cosObject
                    catalog.removeItem(COSName.OUTLINES)
                    catalog.removeItem(COSName.STRUCT_TREE_ROOT)
                    catalog.removeItem(COSName.NAMES)
                    catalog.removeItem(COSName.DESTS)
                }
                pdf.save(out)
            } finally {
                blankDoc?.close()
                blankFile.delete()
            }
        }
    }

    /** Foto e inchiostro della pagina del quaderno sopra la pagina del PDF. */
    private fun drawInk(pdf: PDDocument, p: PDPage, doc: NotebookDocument, page: PageModel, images: (PageImage) -> Bitmap?) {
        if (page.strokes.isEmpty() && page.images.isEmpty()) return
        val crop = p.cropBox
        val rot = ((p.rotation % 360) + 360) % 360
        val turned = rot == 90 || rot == 270
        // misura della pagina come si vede (già ruotata) e dove sta dentro il foglio del quaderno
        val dispW = if (turned) crop.height else crop.width
        val dispH = if (turned) crop.width else crop.height
        val fit = PdfPages.fitRect(dispW, dispH, doc.format.widthPt.toFloat(), doc.format.heightPt.toFloat(), RectF())
        val k = fit.width() / dispW
        PDPageContentStream(pdf, p, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
            // foglio del quaderno (origine in alto a sinistra, y in giù) → pagina vista → spazio del PDF
            cs.transform(viewToUser(crop, rot))
            cs.transform(Matrix(1f / k, 0f, 0f, 1f / k, -fit.left / k, -fit.top / k))
            for (img in page.images) {
                val bmp = images(img) ?: continue
                val x = if (bmp.hasAlpha()) LosslessFactory.createFromImage(pdf, bmp) else JPEGFactory.createFromImage(pdf, bmp, 0.9f)
                cs.saveGraphicsState()
                cs.transform(Matrix(img.w, 0f, 0f, -img.h, img.x, img.y + img.h))   // l'immagine non va capovolta
                cs.drawImage(x, 0f, 0f, 1f, 1f)
                cs.restoreGraphicsState()
            }
            val states = HashMap<Int, PDExtendedGraphicsState>()
            val path = Path()
            // evidenziatore sotto, poi il resto: come sullo schermo
            for (ns in page.strokes) if (ns.tool == ToolKind.HIGHLIGHTER) fillStroke(cs, ns, path, states)
            for (ns in page.strokes) if (ns.tool != ToolKind.HIGHLIGHTER) fillStroke(cs, ns, path, states)
        }
    }

    /**
     * Punto del foglio del quaderno (punti, origine in alto a sinistra) → spazio utente della pagina [p]
     * del PDF esportato: la stessa trasformazione dell'inchiostro (pagina adattata e centrata, /Rotate).
     * Vale anche per le pagine fatte da PdfDocument (stessa misura del foglio, nessuna rotazione).
     */
    fun notebookToUser(p: PDPage, doc: NotebookDocument): (Float, Float) -> android.graphics.PointF {
        val c = p.cropBox
        val rot = ((p.rotation % 360) + 360) % 360
        val turned = rot == 90 || rot == 270
        val dispW = if (turned) c.height else c.width
        val dispH = if (turned) c.width else c.height
        val fit = PdfPages.fitRect(dispW, dispH, doc.format.widthPt.toFloat(), doc.format.heightPt.toFloat(), RectF())
        val k = fit.width() / dispW
        val m = viewToUser(c, rot)
        return { x, y -> m.transformPoint((x - fit.left) / k, (y - fit.top) / k) }
    }

    /**
     * Coordinate della pagina vista (origine in alto a sinistra, y in giù, rotazione /Rotate già
     * applicata) → spazio utente del PDF.
     */
    private fun viewToUser(c: PDRectangle, rot: Int): Matrix = when (rot) {
        90 -> Matrix(0f, 1f, 1f, 0f, c.lowerLeftX, c.lowerLeftY)
        180 -> Matrix(-1f, 0f, 0f, 1f, c.upperRightX, c.lowerLeftY)
        270 -> Matrix(0f, -1f, -1f, 0f, c.upperRightX, c.upperRightY)
        else -> Matrix(1f, 0f, 0f, -1f, c.lowerLeftX, c.upperRightY)
    }

    private fun fillStroke(cs: PDPageContentStream, ns: NoteStroke, path: Path, states: HashMap<Int, PDExtendedGraphicsState>) {
        val color = ns.stroke.brush.colorIntArgb
        val alpha = ((color ushr 24) * Brushes.opacityMultiplier(ns.tool)).toInt().coerceIn(0, 255)
        if (alpha == 0) return
        val gs = states.getOrPut(alpha) { PDExtendedGraphicsState().apply { nonStrokingAlphaConstant = alpha / 255f } }
        cs.setGraphicsStateParameters(gs)
        cs.setNonStrokingColor(color shr 16 and 0xFF, color shr 8 and 0xFF, color and 0xFF)
        val shape = ns.stroke.shape
        val pts = FloatArray(8)
        for (group in 0 until shape.getRenderGroupCount()) {
            shape.populateOutlines(group, path)
            if (path.isEmpty || !hasSegments(path, pts)) continue
            val it = path.pathIterator
            loop@ while (it.hasNext()) {
                when (it.next(pts, 0)) {
                    PathIterator.VERB_MOVE -> cs.moveTo(pts[0], pts[1])
                    PathIterator.VERB_LINE -> cs.lineTo(pts[2], pts[3])
                    PathIterator.VERB_QUAD, PathIterator.VERB_CONIC -> {
                        // quadratica → cubica equivalente (le coniche di Ink non ci sono: approssimate)
                        cs.curveTo(
                            pts[0] + 2f / 3f * (pts[2] - pts[0]), pts[1] + 2f / 3f * (pts[3] - pts[1]),
                            pts[4] + 2f / 3f * (pts[2] - pts[4]), pts[5] + 2f / 3f * (pts[3] - pts[5]),
                            pts[4], pts[5],
                        )
                    }
                    PathIterator.VERB_CUBIC -> cs.curveTo(pts[2], pts[3], pts[4], pts[5], pts[6], pts[7])
                    PathIterator.VERB_CLOSE -> cs.closePath()
                    PathIterator.VERB_DONE -> break@loop
                }
            }
            cs.fill(Path.FillType.WINDING)
        }
    }

    /** Un contorno fatto solo di "moveTo" non si riempie: nel PDF resterebbe un tracciato senza operatore. */
    private fun hasSegments(path: Path, pts: FloatArray): Boolean {
        val it = path.pathIterator
        while (it.hasNext()) {
            when (it.next(pts, 0)) {
                PathIterator.VERB_LINE, PathIterator.VERB_QUAD, PathIterator.VERB_CONIC, PathIterator.VERB_CUBIC -> return true
                PathIterator.VERB_DONE -> return false
            }
        }
        return false
    }
}
