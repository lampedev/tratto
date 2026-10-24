package com.caro.tratto.export

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import java.io.File
import java.util.IdentityHashMap

/**
 * Il PDF di Android (PdfDocument) salva le foto senza perdita (Flate): una foto a tutta pagina pesa
 * 5–8 MB. Qui si riscrivono in JPEG come farebbe una fotocamera: il PDF pesa una frazione e a occhio
 * è uguale. Le immagini con trasparenza vera (maschera SMask) e quelle piccole restano come sono.
 */
object PdfImages {

    private const val QUALITY = 0.85f
    private const val MIN_PIXELS = 128 * 128

    fun compress(context: Context, file: File) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val tmpDir = File(context.cacheDir, "pdfbox").apply { mkdirs() }
        val out = File(file.parentFile, file.name + ".img.tmp")
        PDDocument.load(file, MemoryUsageSetting.setupMixed(48L shl 20).setTempDir(tmpDir)).use { pdf ->
            // la stessa foto su più pagine è un solo oggetto: la si converte una volta
            val done = IdentityHashMap<COSBase, PDImageXObject>()
            var changed = false
            for (page in pdf.pages) changed = recompress(pdf, page.resources, done, 0) || changed
            if (!changed) return
            pdf.save(out)
        }
        if (!out.renameTo(file)) {
            out.copyTo(file, overwrite = true)
            out.delete()
        }
    }

    private fun recompress(pdf: PDDocument, res: PDResources?, done: IdentityHashMap<COSBase, PDImageXObject>, depth: Int): Boolean {
        if (res == null || depth > 4) return false
        var changed = false
        for (name in res.xObjectNames.toList()) {
            when (val x = runCatching { res.getXObject(name) }.getOrNull()) {
                is PDImageXObject -> {
                    val jpeg = done.getOrPut(x.cosObject) { toJpeg(pdf, x) ?: x }
                    if (jpeg !== x) {
                        res.put(name, jpeg)
                        changed = true
                    }
                }
                is PDFormXObject -> changed = recompress(pdf, x.resources, done, depth + 1) || changed
            }
        }
        return changed
    }

    /** La foto in JPEG, o null se va lasciata com'è (trasparente, piccola, già JPEG, o non converrebbe). */
    private fun toJpeg(pdf: PDDocument, x: PDImageXObject): PDImageXObject? = runCatching {
        if (x.isStencil || x.softMask != null || x.width * x.height < MIN_PIXELS) return null
        if (x.bitsPerComponent != 8 || COSName.DCT_DECODE in x.stream.filters) return null
        val bmp = x.image ?: return null
        try {
            bmp.setHasAlpha(false)   // è opaca (niente SMask): senza, PdfBox le aggiungerebbe una maschera inutile
            JPEGFactory.createFromImage(pdf, bmp, QUALITY).takeIf { it.cosObject.length < x.cosObject.length }
        } finally {
            bmp.recycle()
        }
    }.getOrNull()
}
