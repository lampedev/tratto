package com.caro.tratto.data

import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import com.caro.tratto.editor.densifyInputs
import com.caro.tratto.ink.Brushes
import com.caro.tratto.model.NoteStroke
import com.caro.tratto.model.PageImage
import com.caro.tratto.model.PageLink
import com.caro.tratto.model.PageModel
import com.caro.tratto.model.newPageId
import com.caro.tratto.model.ToolKind
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * strokes.bin: MAGIC, VERSION, pageCount, per pagina: strokeCount, per tratto:
 * tool(byte) variant(byte, da v2) colore(int ARGB) spessore(float pt) lunghezza(int) input Ink codificati(bytes);
 * da v3, dopo i tratti della pagina: imageCount, per immagine nome(UTF) x y w h;
 * da v4, in fondo alla pagina: pagina del PDF di sfondo (int, -1 = nessuna).
 *
 * Lettura in due tempi: [parse] legge i byte (veloce, si fa sotto il lock del quaderno), [build]
 * ricostruisce la geometria Ink su più thread (è la parte lenta: ~1 ms a tratto).
 * Senza dipendenze Android: lo stesso codice gira nel banco di prova JVM.
 */
internal object StrokesFile {
    const val MAGIC = 0x54524154   // "TRAT"
    const val VERSION = 6   // v2: byte "variant" dopo lo strumento; v3: immagini per pagina; v4: pagina PDF; v5: segnalibro; v6: id pagina + collegamenti

    class RawStroke(val tool: ToolKind, val variant: Int, val color: Int, val size: Float, val bytes: ByteArray)
    class RawPage(
        val strokes: List<RawStroke>,
        val images: List<PageImage>,
        val pdfPage: Int = -1,
        val bookmark: String? = null,
        val id: String = newPageId(),
        val links: List<PageLink> = emptyList(),
    )

    /** [complete] = false se il file si interrompe o si rompe a metà: [pages] è quanto letto fino a lì. */
    class Raw(val pages: List<RawPage>, val complete: Boolean) {
        val strokeCount get() = pages.sumOf { it.strokes.size }
    }

    class Built(val pages: MutableList<PageModel>, val skipped: Int)

    fun write(os: OutputStream, pages: List<PageModel>) {
        val out = DataOutputStream(BufferedOutputStream(os, 1 shl 16))
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(pages.size)
        for (page in pages) {
            out.writeInt(page.strokes.size)
            for (ns in page.strokes) {
                val brush = ns.stroke.brush
                out.writeByte(ns.tool.ordinal)
                out.writeByte(ns.variant)
                out.writeInt(brush.colorIntArgb)
                out.writeFloat(brush.size)
                // i tratti sono immutabili: codificati una volta, poi ogni salvataggio riusa i byte
                val bytes = ns.encoded ?: ns.stroke.inputs.encode().also { ns.encoded = it }
                out.writeInt(bytes.size)
                out.write(bytes)
            }
            out.writeInt(page.images.size)
            for (img in page.images) {
                out.writeUTF(img.file)
                out.writeFloat(img.x); out.writeFloat(img.y); out.writeFloat(img.w); out.writeFloat(img.h)
            }
            out.writeInt(page.pdfPage)
            out.writeBoolean(page.bookmark != null)
            page.bookmark?.let { out.writeUTF(it.take(200)) }
            out.writeUTF(page.id)
            out.writeInt(page.links.size)
            for (l in page.links) {
                out.writeFloat(l.x); out.writeFloat(l.y); out.writeFloat(l.w); out.writeFloat(l.h)
                out.writeUTF(l.notebookId.orEmpty())
                out.writeUTF(l.pageId)
                out.writeInt(l.pageIndex)
                out.writeFloat(l.pointX); out.writeFloat(l.pointY)
            }
        }
        out.flush()
    }

    fun parse(data: ByteArray): Raw {
        val input = DataInputStream(ByteArrayInputStream(data))
        val pages = ArrayList<RawPage>()
        val tools = ToolKind.entries
        // pagina in lettura: se il file si rompe a metà si tengono anche i suoi tratti già letti
        var strokes = ArrayList<RawStroke>()
        var images = ArrayList<PageImage>()
        var inPage = false
        try {
            if (input.readInt() != MAGIC) return Raw(emptyList(), complete = false)
            val version = input.readInt()
            if (version > VERSION) return Raw(emptyList(), complete = false)
            val pageCount = input.readInt()
            if (pageCount !in 0..100_000) return Raw(emptyList(), complete = false)
            repeat(pageCount) {
                strokes = ArrayList()
                images = ArrayList()
                inPage = true
                val count = input.readInt()
                if (count !in 0..10_000_000) error("numero di tratti non valido")
                repeat(count) {
                    val tool = tools.getOrElse(input.readByte().toInt()) { ToolKind.PEN }
                    val variant = if (version >= 2) input.readByte().toInt() else 0
                    val color = input.readInt()
                    val size = input.readFloat()
                    val len = input.readInt()
                    if (len !in 0..input.available()) error("tratto troncato")
                    val bytes = ByteArray(len)
                    input.readFully(bytes)
                    strokes.add(RawStroke(tool, variant, color, size, bytes))
                }
                if (version >= 3) {
                    val n = input.readInt()
                    if (n !in 0..100_000) error("numero di immagini non valido")
                    repeat(n) {
                        images.add(PageImage(input.readUTF(), input.readFloat(), input.readFloat(), input.readFloat(), input.readFloat()))
                    }
                }
                val pdfPage = if (version >= 4) input.readInt() else -1
                val bookmark = if (version >= 5 && input.readBoolean()) input.readUTF() else null
                // file vecchi: un id fisso per posizione, così un collegamento fatto prima del salvataggio regge
                val id = if (version >= 6) input.readUTF() else "p${pages.size}"
                val links = ArrayList<PageLink>()
                if (version >= 6) {
                    val n = input.readInt()
                    if (n !in 0..10_000) error("numero di collegamenti non valido")
                    repeat(n) {
                        val x = input.readFloat(); val y = input.readFloat(); val w = input.readFloat(); val h = input.readFloat()
                        val nb = input.readUTF().ifEmpty { null }
                        val pid = input.readUTF()
                        val idx = input.readInt()
                        links.add(PageLink(x, y, w, h, nb, pid, idx, input.readFloat(), input.readFloat()))
                    }
                }
                pages.add(RawPage(strokes, images, pdfPage, bookmark, id, links))
                inPage = false
            }
            return Raw(pages, complete = true)
        } catch (e: Exception) {
            if (inPage && (strokes.isNotEmpty() || images.isNotEmpty())) pages.add(RawPage(strokes, images))
            return Raw(pages, complete = false)
        }
    }

    /**
     * Ricostruisce i tratti in parallelo su [threads] thread. Un tratto che Ink rifiuta viene riparato
     * (si tengono gli input validi) o saltato: il quaderno si apre comunque.
     * [cancelled] interrompe il lavoro (CancellationException), [onProgress] riceve 0..1.
     */
    fun build(raw: Raw, threads: Int, cancelled: () -> Boolean = { false }, onProgress: (Float) -> Unit = {}): Built {
        val flat = raw.pages.flatMap { it.strokes }
        val built = arrayOfNulls<NoteStroke>(flat.size)
        val next = AtomicInteger()
        val done = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val workers = (1..threads.coerceIn(1, 8)).map { n ->
            thread(name = "tratto-apertura-$n") {
                try {
                    while (!cancelled() && failure.get() == null) {
                        val i = next.getAndIncrement()
                        if (i >= flat.size) break
                        built[i] = buildStroke(flat[i])
                        val d = done.incrementAndGet()
                        if (d and 63 == 0) onProgress(d.toFloat() / flat.size)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        workers.forEach { it.join() }
        failure.get()?.let { throw it }
        if (cancelled()) throw CancellationException("apertura annullata")
        var k = 0
        var skipped = 0
        val pages = raw.pages.mapTo(ArrayList(raw.pages.size)) { rp ->
            val strokes = ArrayList<NoteStroke>(rp.strokes.size)
            repeat(rp.strokes.size) {
                val ns = built[k++]
                if (ns != null) strokes.add(ns) else skipped++
            }
            PageModel(strokes, ArrayList(rp.images), rp.pdfPage, rp.bookmark, rp.id, ArrayList(rp.links))
        }
        return Built(pages, skipped)
    }

    private fun buildStroke(r: RawStroke): NoteStroke? {
        val brush = try {
            Brushes.create(r.tool, r.color, r.size, r.variant)
        } catch (e: Exception) {
            runCatching { Brushes.create(r.tool, r.color, Brushes.defaultSettings(r.tool).sizePt) }.getOrNull() ?: return null
        }
        val decoded = try {
            StrokeInputBatch.decode(r.bytes)
        } catch (e: Exception) {
            return null
        }
        try {
            // tratti salvati prima dell'infittimento: lisci anche loro allo zoom massimo
            val inputs = densifyInputs(decoded)
            return NoteStroke(r.tool, Stroke(brush, inputs), r.variant).also { if (inputs === decoded) it.encoded = r.bytes }
        } catch (e: Exception) {
            // input che Ink non accetta più: si tengono quelli validi, nell'ordine
            return try {
                val out = MutableStrokeInputBatch()
                val si = StrokeInput()
                for (i in 0 until decoded.size) {
                    decoded.populate(i, si)
                    try { out.add(si) } catch (_: Exception) { }
                }
                if (out.size == 0) null else NoteStroke(r.tool, Stroke(brush, out), r.variant)
            } catch (_: Exception) {
                null
            }
        }
    }
}
