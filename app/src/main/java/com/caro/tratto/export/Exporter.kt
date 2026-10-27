package com.caro.tratto.export

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.core.content.FileProvider
import com.caro.tratto.data.ImageLoader
import com.caro.tratto.data.NotebookRepository
import com.caro.tratto.data.PdfPages
import com.caro.tratto.model.NotebookDocument
import com.caro.tratto.model.NotebookMeta
import com.caro.tratto.model.PageImage
import com.caro.tratto.model.PageLink
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

object Exporter {

    /**
     * Foto per l'export alla risoluzione che serve davvero: [dpi] punti per pollice alla misura con
     * cui sono posate sulla pagina (prima: 4096 px non compressi → PDF enormi, lentissimi, e con
     * due foto memoria esaurita). Le bitmap vanno liberate con [ExportImages.recycle].
     */
    private class ExportImages(context: Context, private val doc: NotebookDocument, private val dpi: Int) {
        private val repo = NotebookRepository(context)
        private val loaded = HashMap<String, Bitmap?>()

        fun get(img: PageImage): Bitmap? {
            val side = (maxOf(img.w, img.h) / 72f * dpi).toInt().coerceIn(256, 2200)
            return loaded.getOrPut("${img.file}@$side") { ImageLoader.decode(repo.imageFile(doc.meta.id, img.file), side) }
        }

        fun recycle() {
            loaded.values.forEach { it?.recycle() }
            loaded.clear()
        }
    }

    /**
     * PDF VETTORIALE: 1 unità = 1 punto, pagine della misura reale del formato.
     * Quaderno nato da un PDF: si parte dal PDF originale e si aggiunge l'inchiostro (PdfAnnotator).
     */
    fun exportPdf(
        context: Context,
        doc: NotebookDocument,
        pageIndices: List<Int>? = null,
        /** Collegamenti verso altri quaderni (solo nella copia sincronizzata, dove i PDF hanno un posto). */
        remote: (com.caro.tratto.model.PageLink) -> PdfLinks.Remote? = { null },
        /** Nome del file al posto di quello che viene dal titolo (esportando più quaderni insieme). */
        name: String? = null,
    ): File {
        val indices = pageIndices ?: doc.pages.indices.toList()
        val suffix = when {
            pageIndices == null || indices.size == doc.pages.size -> ""
            else -> "_p" + ranges(indices)
        }
        val file = File(exportsDir(context), name ?: "${safeName(doc.meta.title)}$suffix.pdf")
        val source = NotebookRepository(context).pdfFile(doc.meta.id)
        if (doc.meta.fromPdf && source.exists()) {
            val images = ExportImages(context, doc, dpi = 200)
            try {
                PdfAnnotator.export(context, doc, source, file, indices, images::get) { blanks, out -> writeVectorPdf(context, doc, blanks, out) }
            } finally {
                images.recycle()
            }
        } else {
            writeVectorPdf(context, doc, indices, file)
        }
        // collegamenti cliccabili: un errore qui non deve far perdere l'esportazione
        runCatching { PdfLinks.add(context, doc, indices, file, remote) }
            .onFailure { android.util.Log.w("Tratto", "collegamenti nel PDF", it) }
        return file
    }

    /** Esito di [exportPdfs]: i PDF fatti, nell'ordine dei quaderni; i titoli non riusciti; se è stata interrotta. */
    class Batch(val files: List<File>, val failed: List<String>, val cancelled: Boolean)

    /**
     * Più quaderni insieme, un PDF ciascuno, con nomi tutti diversi ("Titolo", "Titolo (2)"…).
     * I collegamenti verso un altro dei quaderni esportati portano al suo PDF: salvati nella stessa
     * cartella restano cliccabili (se il lettore PDF lo permette); quelli verso gli altri quaderni no.
     */
    fun exportPdfs(
        context: Context,
        notebooks: List<NotebookMeta>,
        cancelled: () -> Boolean = { false },
        onProgress: (done: Int, total: Int, title: String) -> Unit = { _, _, _ -> },
    ): Batch {
        val repo = NotebookRepository(context)
        val used = HashSet<String>()
        val names = notebooks.associate { nb ->
            val base = safeName(nb.title)
            var name = base
            var k = 2
            while (!used.add(name.lowercase())) name = "$base (${k++})"
            nb.id to "$name.pdf"
        }
        val byId = notebooks.associateBy { it.id }
        val pageIds = HashMap<String, List<String>>()   // id del quaderno → id delle sue pagine, in ordine
        val remote: (PageLink) -> PdfLinks.Remote? = remote@{ l ->
            val id = l.notebookId ?: return@remote null
            val meta = byId[id] ?: return@remote null
            val ids = pageIds.getOrPut(id) { runCatching { repo.readRaw(id).raw.pages.map { it.id } }.getOrDefault(emptyList()) }
            val page = ids.indexOf(l.pageId).takeIf { it >= 0 } ?: l.pageIndex.coerceIn(0, maxOf(0, meta.pageCount - 1))
            PdfLinks.Remote(names.getValue(id), page, meta.format.heightPt.toFloat(), pointUsable = !meta.fromPdf)
        }
        val files = ArrayList<File>()
        val failed = ArrayList<String>()
        for ((i, nb) in notebooks.withIndex()) {
            if (cancelled()) return Batch(files, failed, cancelled = true)
            onProgress(i, notebooks.size, nb.title)
            try {
                files += exportPdf(context, repo.build(repo.readRaw(nb.id)).doc, remote = remote, name = names.getValue(nb.id))
            } catch (e: Exception) {
                android.util.Log.w("Tratto", "export: ${nb.title}", e)
                failed += nb.title
            }
        }
        onProgress(notebooks.size, notebooks.size, "")
        return Batch(files, failed, cancelled = false)
    }

    /** Pagine del quaderno disegnate da Android (PdfDocument): foglio, foto e inchiostro vettoriali. */
    private fun writeVectorPdf(context: Context, doc: NotebookDocument, indices: List<Int>, file: File) {
        val pdf = PdfDocument()
        val images = ExportImages(context, doc, dpi = 200)
        try {
            for ((n, i) in indices.withIndex()) {
                val info = PdfDocument.PageInfo.Builder(doc.format.widthPt, doc.format.heightPt, n + 1).create()
                val page = pdf.startPage(info)
                PageRenderer.drawPageVector(page.canvas, doc, i, images = images::get)
                pdf.finishPage(page)
            }
            FileOutputStream(file).use { pdf.writeTo(it) }
        } finally {
            pdf.close()
            images.recycle()
        }
        // foto in JPEG invece che senza perdita: un errore qui lascia il PDF com'è, solo più pesante
        if (indices.any { doc.pages.getOrNull(it)?.images?.isNotEmpty() == true }) {
            runCatching { PdfImages.compress(context, file) }
                .onFailure { android.util.Log.w("Tratto", "foto del PDF in JPEG", it) }
        }
    }

    /** PNG della pagina a `dpi` punti per pollice (300 = qualità stampa). */
    fun exportPng(context: Context, doc: NotebookDocument, pageIndex: Int, dpi: Int = 300): File {
        val k = dpi / 72f
        val w = (doc.format.widthPt * k).roundToInt()
        val h = (doc.format.heightPt * k).roundToInt()
        val file = File(exportsDir(context), "${safeName(doc.meta.title)}_p${pageIndex + 1}.png")
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val images = ExportImages(context, doc, dpi)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.scale(k, k)
            val source = NotebookRepository(context).pdfFile(doc.meta.id)
            val pdf = if (doc.meta.fromPdf && source.exists()) { p: Int -> PdfPages.renderOnce(source, p, w) } else null
            PageRenderer.drawPageVector(canvas, doc, pageIndex, images = images::get, pdf = pdf)
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
            images.recycle()
        }
        return file
    }

    /** Apre il "Condividi" di Android (Drive, Telegram, ...). */
    fun share(context: Context, files: List<File>, mimeType: String) {
        if (files.isEmpty()) return
        val authority = "${context.packageName}.fileprovider"
        val uris: List<Uri> = files.map { FileProvider.getUriForFile(context, authority, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mimeType
        intent.clipData = ClipData.newUri(context.contentResolver, files[0].name, uris[0]).apply {
            for (u in uris.drop(1)) addItem(ClipData.Item(u))
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, "Condividi")
        // Tratto stesso no: riceverebbe i suoi PDF e li importerebbe come quaderni nuovi
        chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(context, com.caro.tratto.MainActivity::class.java)))
        if (context !is Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    private fun exportsDir(context: Context): File {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        // pulizia: gli export vecchi di un giorno non servono più
        val cutoff = System.currentTimeMillis() - 24 * 3600 * 1000L
        dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        return dir
    }

    /** [0,1,2,3,4,7] → "1-5_8" (numeri di pagina da 1). */
    private fun ranges(indices: List<Int>): String {
        val out = ArrayList<String>()
        var start = -1
        var prev = -2
        for (i in indices.sorted() + Int.MAX_VALUE) {
            if (i != prev + 1) {
                if (start >= 0) out += if (start == prev) "${start + 1}" else "${start + 1}-${prev + 1}"
                start = i
            }
            prev = i
        }
        return out.joinToString("_").take(60)
    }

    private fun safeName(title: String): String =
        title.trim().replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), "_").take(80).ifBlank { "Tratto" }
}
