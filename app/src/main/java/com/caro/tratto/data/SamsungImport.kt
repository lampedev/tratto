package com.caro.tratto.data

import android.content.Context
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.ink.brush.InputToolType
import androidx.ink.storage.encode
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import com.caro.tratto.editor.densifyInputs
import com.caro.tratto.ink.PenFeel
import com.caro.tratto.model.LineSpacing
import com.caro.tratto.model.PageBackground
import com.caro.tratto.model.PageFormat
import com.caro.tratto.model.PageImage
import com.caro.tratto.model.PageStyle
import com.caro.tratto.model.PaperColor
import com.caro.tratto.model.ToolKind
import java.io.File
import kotlin.math.floor

/**
 * Da un quaderno di Samsung Notes ([SamsungNotes.Note]) alle pagine di Tratto, già codificate per
 * strokes.bin. Coordinate: la pagina Samsung (larga di solito 1440 o 1848 px) si scala sulla
 * larghezza del formato; le pagine molto lunghe (modalità a scorrimento) si tagliano in fogli A4.
 */
internal object SamsungImport {

    class Converted(
        val format: PageFormat,
        val style: PageStyle,
        val pages: List<StrokesFile.RawPage>,
        val fromPdf: Boolean,
        /** Cose non importate (immagini o PDF mancanti, tratti illeggibili). */
        val skipped: Int,
    )

    /**
     * [pdfOut] = dove mettere il PDF di sfondo (source.pdf del quaderno), se la nota ne ha;
     * [image] copia un'immagine nel quaderno e ne ritorna il nome (o null); [tmp] = cartella di lavoro.
     */
    fun convert(context: Context, note: SamsungNotes.Note, pdfOut: File, tmp: File, image: (File) -> String?): Converted {
        var skipped = 0
        val pages = note.pages.filter { it.width > 0f && it.height > 0f }
            .dropLastWhile { it.isEmpty }
            .ifEmpty { note.pages.filter { it.width > 0f && it.height > 0f }.take(1) }
        if (pages.isEmpty()) throw java.io.IOException("la nota non ha pagine leggibili")

        // PDF di sfondo: uno solo nel quaderno; se la nota ne usa più d'uno si uniscono
        val pdfIds = pages.mapNotNull { it.pdf?.media }.distinct().filter { note.media[it] != null }
        val pdfStart = HashMap<Int, Int>()
        var pdfSizes: List<Pair<Float, Float>> = emptyList()
        if (pdfIds.isNotEmpty()) {
            val files = pdfIds.map { note.media.getValue(it) }
            var offset = 0
            pdfIds.zip(files).forEach { (id, f) ->
                pdfStart[id] = offset
                offset += runCatching { PdfPages.inspect(f).first }.getOrDefault(0)
            }
            if (files.size == 1) files[0].copyTo(pdfOut, overwrite = true) else mergePdfs(context, files, pdfOut)
            pdfSizes = pageSizes(pdfOut)
        }
        fun pdfIndex(p: SamsungNotes.PdfPaper?): Int {
            val start = p?.let { pdfStart[it.media] } ?: return -1
            return (start + p.page).takeIf { it in pdfSizes.indices } ?: -1
        }

        val first = pages.first()
        val firstPdf = pdfIndex(first.pdf)
        val format = when {
            firstPdf >= 0 -> PageFormat.pdf(Math.round(pdfSizes[firstPdf].first), Math.round(pdfSizes[firstPdf].second))
            first.width <= first.height ->
                if (first.height / first.width >= 1.35f) PageFormat.A4_PORTRAIT
                else PageFormat.pdf(595, Math.round(595 * first.height / first.width))
            else ->
                if (first.width / first.height in 1.35f..1.5f) PageFormat.A4_LANDSCAPE
                else PageFormat.pdf(Math.round(595 * first.width / first.height), 595)
        }
        val fw = format.widthPt.toFloat()
        val fh = format.heightPt.toFloat()
        val styled = pages.firstOrNull { it.pdf == null } ?: first
        val style = style(styled)

        val out = ArrayList<StrokesFile.RawPage>()
        val pictures = HashMap<Int, String?>()   // stessa immagine su più pagine: copiata una volta
        for (page in pages) {
            val pdf = pdfIndex(page.pdf)
            if (page.pdf != null && pdf < 0) skipped++
            if (pdf >= 0) {
                // la pagina del PDF sta nel rettangolo salvato da Samsung; in Tratto è adattata al foglio
                val (pw, ph) = pdfSizes[pdf]
                val fit = PdfPages.fitRect(pw, ph, fw, fh, RectF())
                val p = page.pdf!!
                val rw = p.right - p.left
                val rh = p.bottom - p.top
                val sx = if (rw > 1f) fit.width() / rw else fw / page.width
                val sy = if (rh > 1f) fit.height() / rh else sx
                val ox = if (rw > 1f) fit.left - p.left * sx else 0f
                val oy = if (rh > 1f) fit.top - p.top * sy else 0f
                out.add(rawPage(page.inks, page.pictures, page.texts, note, tmp, sx, sy, ox, oy, pdf, pictures, image) { skipped++ })
                continue
            }
            val s = fw / page.width
            // taglio delle pagine lunghe: un foglio = altezza del formato, a righe intere se c'è un motivo Samsung
            var chunk = fh / s
            val pitch = style.spacing.samsungSize * 1.35f * minOf(page.width, page.height) / 360f
            if (style.spacing.samsungSize > 0 && pitch > 0f) chunk = maxOf(pitch, floor(chunk / pitch) * pitch)
            if (page.height <= chunk * 1.1f) {
                out.add(rawPage(page.inks, page.pictures, page.texts, note, tmp, s, s, 0f, 0f, -1, pictures, image) { skipped++ })
                continue
            }
            val n = Math.ceil((page.height / chunk).toDouble()).toInt().coerceIn(1, 500)
            val inks = page.inks.groupBy { (it.centerY() / chunk).toInt().coerceIn(0, n - 1) }
            val pics = page.pictures.groupBy { (((it.top + it.bottom) / 2f) / chunk).toInt().coerceIn(0, n - 1) }
            val texts = page.texts.groupBy { (((it.top + it.bottom) / 2f) / chunk).toInt().coerceIn(0, n - 1) }
            val last = maxOf(inks.keys.maxOrNull() ?: 0, pics.keys.maxOrNull() ?: 0, texts.keys.maxOrNull() ?: 0)
            for (k in 0..last) {
                out.add(rawPage(inks[k].orEmpty(), pics[k].orEmpty(), texts[k].orEmpty(), note, tmp, s, s, 0f, -k * chunk * s, -1, pictures, image) { skipped++ })
            }
        }
        return Converted(format, style, out, fromPdf = pdfIds.isNotEmpty(), skipped = skipped)
    }

    /** Motivo e carta del foglio Samsung → stile del quaderno. */
    private fun style(page: SamsungNotes.Page): PageStyle {
        val t = page.template
        val background = when (t) {
            in 1..3, 10, 11 -> PageBackground.LINES
            in 4..6 -> PageBackground.GRID
            in 7..9 -> PageBackground.DOTS
            else -> PageBackground.BLANK
        }
        val spacing = when (if (t in 1..9) (t - 1) % 3 else 0) {
            1 -> LineSpacing.SAMSUNG_MEDIUM
            2 -> LineSpacing.SAMSUNG_WIDE
            else -> LineSpacing.SAMSUNG_NARROW
        }
        val paper = page.paper?.let { it or 0xFF000000.toInt() }
        return if (paper == null || PageStyle.luminance(paper) > 0.93f) {
            PageStyle(background = background, paper = PaperColor.WHITE, spacing = spacing)
        } else {
            PageStyle(background = background, paper = PaperColor.CUSTOM, spacing = spacing, customPaper = paper)
        }
    }

    /** Tratti e immagini di un foglio: x' = x·sx + ox, y' = y·sy + oy. */
    private fun rawPage(
        inks: List<SamsungNotes.Ink>,
        pictures: List<SamsungNotes.Picture>,
        texts: List<SamsungNotes.TextBox>,
        note: SamsungNotes.Note,
        tmp: File,
        sx: Float,
        sy: Float,
        ox: Float,
        oy: Float,
        pdfPage: Int,
        copied: HashMap<Int, String?>,
        image: (File) -> String?,
        onSkip: () -> Unit,
    ): StrokesFile.RawPage {
        val strokes = ArrayList<StrokesFile.RawStroke>(inks.size)
        for (ink in inks) {
            val tool = tool(note.penName(ink), ink.pressure != null) ?: continue
            val raw = runCatching { rawStroke(ink, tool, sx, sy, ox, oy) }.getOrNull()
            if (raw != null) strokes.add(raw) else onSkip()
        }
        val images = ArrayList<PageImage>()
        for (p in pictures) {
            val file = note.media[p.media]
            val name = if (file == null) null else copied.getOrPut(p.media) { image(file) }
            if (name == null) { onSkip(); continue }
            images.add(PageImage(name, p.left * sx + ox, p.top * sy + oy, (p.right - p.left) * sx, (p.bottom - p.top) * sy))
        }
        for (t in texts) {
            val w = (t.right - t.left) * sx
            val h = (t.bottom - t.top) * sy
            val name = runCatching { textImage(t.text, w, h, tmp) }.getOrNull()?.let(image)
            if (name == null) { onSkip(); continue }
            images.add(PageImage(name, t.left * sx + ox, t.top * sy + oy, w, h))
        }
        return StrokesFile.RawPage(strokes, images, pdfPage)
    }

    /** Penna Samsung → strumento di Tratto; null = da non importare (gomma, puntatore). */
    private fun tool(pen: String, handwritten: Boolean): ToolKind? = when {
        !handwritten -> ToolKind.BALLPOINT   // forme e linee: pressione costante, angoli vivi
        pen.contains("Eraser") || pen.startsWith("Select") || pen.contains("Laser") -> null
        pen.contains("Marker") || pen.contains("Highlighter") -> ToolKind.HIGHLIGHTER
        pen.contains("Pencil") || pen.contains("Crayon") -> ToolKind.PENCIL
        pen.startsWith("InkPen") || pen.startsWith("StraightInkPen") || pen == "DefaultPen" || pen == "Beautify" -> ToolKind.BALLPOINT
        else -> ToolKind.PEN   // stilografica, pennello, calligrafiche
    }

    // input grezzi, senza stabilizzazione: la forma del tratto resta quella scritta in Samsung Notes
    private val VARIANT = PenFeel.encode(1, 0)

    /** Pressione delle forme: con la penna a sfera dà proprio lo spessore salvato. */
    private const val SHAPE_PRESSURE = 0.6f

    /**
     * Lo "spessore" Samsung non è la larghezza del segno: la stilografica a pressione media traccia
     * circa il 45% (misurato sui PDF di Samsung Notes), la penna a inchiostro fra metà e tutto, la
     * matita quasi tutto. Le penne di Tratto a pressione media danno ~1× il loro spessore.
     */
    private fun widthFactor(tool: ToolKind): Float = when (tool) {
        ToolKind.PEN -> 0.47f
        ToolKind.BALLPOINT -> 0.7f
        ToolKind.PENCIL -> 0.85f
        else -> 1f
    }

    private fun rawStroke(ink: SamsungNotes.Ink, tool: ToolKind, sx: Float, sy: Float, ox: Float, oy: Float): StrokesFile.RawStroke? {
        val batch = MutableStrokeInputBatch()
        val pressure = ink.pressure
        var t = -1L
        for (i in 0 until ink.count) {
            val x = ink.xs[i] * sx + ox
            val y = ink.ys[i] * sy + oy
            if (!x.isFinite() || !y.isFinite()) continue
            // tempo strettamente crescente: Ink fonde gli input con lo stesso istante
            t = maxOf(t + 1, ink.times[i])
            batch.add(
                InputToolType.STYLUS, x, y, t,
                StrokeInput.NO_STROKE_UNIT_LENGTH,
                pressure?.get(i) ?: SHAPE_PRESSURE,
                StrokeInput.NO_TILT,
                StrokeInput.NO_ORIENTATION,
            )
        }
        if (batch.size == 0) return null
        val size = (ink.size * sx * if (pressure == null) 1f else widthFactor(tool)).coerceIn(0.1f, if (tool == ToolKind.HIGHLIGHTER) 60f else 40f)
        val color = if (tool == ToolKind.HIGHLIGHTER) ink.color else ink.color or 0xFF000000.toInt()
        return StrokesFile.RawStroke(tool, VARIANT, color, size, densifyInputs(batch).encode())
    }

    /**
     * Testo di una casella di Samsung Notes come PNG trasparente di [wPt]×[hPt] punti (3 px per punto):
     * il corpo più grande che ci sta, fino a 12 pt, nero come in Samsung Notes.
     */
    private fun textImage(text: String, wPt: Float, hPt: Float, tmp: File): File? {
        if (wPt < 4f || hPt < 4f) return null
        val k = 3f
        val w = (wPt * k).toInt().coerceIn(8, 4000)
        val h = (hPt * k).toInt().coerceIn(8, 4000)
        val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF202020.toInt() }
        fun layout(size: Float): android.text.StaticLayout {
            paint.textSize = size
            return android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, w).setIncludePad(false).build()
        }
        var lo = 4f * k
        var hi = 12f * k
        if (layout(lo).height > h) hi = lo
        repeat(12) {
            val mid = (lo + hi) / 2f
            if (layout(mid).height <= h) lo = mid else hi = mid
        }
        val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            layout(lo).draw(android.graphics.Canvas(bmp))
            val f = File(tmp, "testo_${System.nanoTime()}.png")
            f.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            return f
        } finally {
            bmp.recycle()
        }
    }

    /** Misure (punti) di tutte le pagine del PDF. */
    private fun pageSizes(file: File): List<Pair<Float, Float>> = runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                List(r.pageCount) { i -> r.openPage(i).use { it.width.toFloat() to it.height.toFloat() } }
            }
        }
    }.getOrDefault(emptyList())

    private fun mergePdfs(context: Context, files: List<File>, out: File) {
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context.applicationContext)
        val merger = com.tom_roush.pdfbox.multipdf.PDFMergerUtility()
        files.forEach { merger.addSource(it) }
        merger.destinationFileName = out.path
        val tmp = File(context.cacheDir, "pdfbox").apply { mkdirs() }
        merger.mergeDocuments(com.tom_roush.pdfbox.io.MemoryUsageSetting.setupMixed(48L shl 20).setTempDir(tmp))
    }
}
