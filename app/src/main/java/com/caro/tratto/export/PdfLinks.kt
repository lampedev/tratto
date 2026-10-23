package com.caro.tratto.export

import android.content.Context
import com.caro.tratto.model.NotebookDocument
import com.caro.tratto.model.PageLink
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSInteger
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.common.filespecification.PDSimpleFileSpecification
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDAction
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionRemoteGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import java.io.File

/**
 * Collegamenti cliccabili nel PDF esportato: un'annotazione invisibile sopra ogni zona collegata.
 * Verso una pagina dello stesso quaderno (se è fra quelle esportate) un salto interno; verso un altro
 * quaderno il suo PDF, con un percorso relativo (funziona quando i PDF stanno nelle stesse cartelle,
 * come nella copia sincronizzata, e se il lettore PDF lo permette).
 */
object PdfLinks {

    /** Il PDF di un altro quaderno: percorso relativo, pagina (da 0), e come metterci un punto. */
    class Remote(val relPath: String, val page: Int, val heightPt: Float, val pointUsable: Boolean)

    /** Margine sopra il punto d'arrivo: il punto si vede un po' sotto il bordo della finestra. */
    private const val TOP_MARGIN_PT = 120f

    fun add(context: Context, doc: NotebookDocument, indices: List<Int>, file: File, remote: (PageLink) -> Remote?) {
        if (indices.none { doc.pages.getOrNull(it)?.links?.isNotEmpty() == true }) return
        PDFBoxResourceLoader.init(context.applicationContext)
        val tmpDir = File(context.cacheDir, "pdfbox").apply { mkdirs() }
        val out = File(file.parentFile, file.name + ".link.tmp")
        PDDocument.load(file, MemoryUsageSetting.setupMixed(48L shl 20).setTempDir(tmpDir)).use { pdf ->
            val pages = pdf.pages.toList()
            for ((n, i) in indices.withIndex()) {
                val model = doc.pages.getOrNull(i) ?: continue
                if (model.links.isEmpty()) continue
                val page = pages.getOrNull(n) ?: continue
                val toUser = PdfAnnotator.notebookToUser(page, doc)
                val annots = page.annotations
                for (l in model.links) {
                    val action = action(doc, indices, pages, l, remote) ?: continue
                    // riquadro della zona nello spazio del PDF (con la rotazione i lati si scambiano)
                    val a = toUser(l.x, l.y)
                    val b = toUser(l.x + l.w, l.y + l.h)
                    annots.add(
                        PDAnnotationLink().apply {
                            rectangle = PDRectangle(minOf(a.x, b.x), minOf(a.y, b.y), kotlin.math.abs(b.x - a.x), kotlin.math.abs(b.y - a.y))
                            borderStyle = PDBorderStyleDictionary().apply { width = 0f }
                            this.action = action
                        },
                    )
                }
                page.annotations = annots
            }
            pdf.save(out)
        }
        if (!out.renameTo(file)) {
            out.copyTo(file, overwrite = true)
            out.delete()
        }
    }

    private fun action(
        doc: NotebookDocument,
        indices: List<Int>,
        pages: List<com.tom_roush.pdfbox.pdmodel.PDPage>,
        l: PageLink,
        remote: (PageLink) -> Remote?,
    ): PDAction? {
        if (l.notebookId == null) {
            // quaderno intero: la prima delle pagine esportate
            val target = if (l.wholeNotebook) indices.minOrNull() ?: 0
            else doc.pages.indexOfFirst { it.id == l.pageId }.takeIf { it >= 0 } ?: l.pageIndex
            val n = indices.indexOf(target)
            if (n < 0) return null   // pagina non esportata
            val tp = pages[n]
            val dest = if (l.hasPoint) {
                val pt = PdfAnnotator.notebookToUser(tp, doc)(l.pointX, l.pointY)
                PDPageXYZDestination().apply {
                    page = tp
                    left = (pt.x - 60f).toInt().coerceAtLeast(0)
                    top = (pt.y + TOP_MARGIN_PT).toInt()
                }
            } else {
                PDPageFitDestination().apply { page = tp }
            }
            return PDActionGoTo().apply { destination = dest }
        }
        val r = remote(l) ?: return null
        return PDActionRemoteGoTo().apply {
            file = PDSimpleFileSpecification().apply { this.file = r.relPath }
            // destinazione in un altro file: [numero di pagina /XYZ sinistra alto zoom] oppure [n /Fit]
            val d = COSArray()
            d.add(COSInteger.get(r.page.toLong()))
            if (l.hasPoint && r.pointUsable) {
                d.add(COSName.getPDFName("XYZ"))
                d.add(COSFloat((l.pointX - 60f).coerceAtLeast(0f)))
                d.add(COSFloat(r.heightPt - l.pointY + TOP_MARGIN_PT))
                d.add(COSNull.NULL)
            } else {
                d.add(COSName.getPDFName("Fit"))
            }
            setD(d)
        }
    }
}
