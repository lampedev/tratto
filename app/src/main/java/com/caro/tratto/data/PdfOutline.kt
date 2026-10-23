package com.caro.tratto.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File

/** Indice (segnalibri) di un PDF importato: titoli annidati e pagina del PDF a cui portano. */
object PdfOutline {

    /** [pdfPage] = indice della pagina nel PDF, -1 se la voce non porta a una pagina. */
    class Item(val title: String, val level: Int, val pdfPage: Int)

    fun read(context: Context, file: File): List<Item> {
        PDFBoxResourceLoader.init(context.applicationContext)
        val tmpDir = File(context.cacheDir, "pdfbox").apply { mkdirs() }
        PDDocument.load(file, MemoryUsageSetting.setupMixed(32L shl 20).setTempDir(tmpDir)).use { pdf ->
            val outline = pdf.documentCatalog.documentOutline ?: return emptyList()
            val out = ArrayList<Item>()
            fun walk(node: PDOutlineNode, level: Int) {
                var item = node.firstChild
                // limiti: indici enormi o rotti (cicli) non devono bloccare l'app
                while (item != null && out.size < 3000 && level < 12) {
                    val page = runCatching { item.findDestinationPage(pdf) }.getOrNull()
                    val index = page?.let { runCatching { pdf.pages.indexOf(it) }.getOrDefault(-1) } ?: -1
                    out += Item(item.title.orEmpty().trim().ifEmpty { "(senza titolo)" }, level, index)
                    walk(item, level + 1)
                    item = item.nextSibling
                }
            }
            walk(outline, 0)
            return out
        }
    }
}
