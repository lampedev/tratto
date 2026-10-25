package com.caro.tratto.data

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Lettore dei quaderni di Samsung Notes (.sdocx, il "File Samsung Notes" del Condividi).
 *
 * Il formato non è pubblico: la struttura segue lo studio di twangodev/sdocx
 * (docs/reverse-engineering/file-format.md), verificata sui file d'esempio del loro corpus.
 * Un .sdocx è uno ZIP con note.note (intestazione, nomi delle penne), pageIdInfo.dat (ordine
 * delle pagine), una <uuid>.page per pagina (livelli → oggetti → "frame" binari) e media/.
 *
 * Si legge solo ciò che Tratto sa rappresentare: tratti a mano (vettoriali, con pressione),
 * forme e linee (diventano tratti), immagini, PDF di sfondo e il motivo del foglio. Testo
 * digitato, audio e il resto si contano in [Note.skipped]. Senza dipendenze Android.
 * Coordinate: pixel del documento Samsung (larghezza tipica 1848), origine in alto a sinistra.
 */
internal object SamsungNotes {

    /** Tratto; [pressure] null = forma o linea (spessore costante). [times] in ms dall'inizio. */
    class Ink(
        val penId: Int,
        val color: Int,
        val size: Float,
        val xs: FloatArray,
        val ys: FloatArray,
        val pressure: FloatArray?,
        val times: LongArray,
    ) {
        val count get() = xs.size
        fun centerY(): Float = (ys.min() + ys.max()) / 2f
    }

    class Picture(val media: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

    /** Pagina [page] del PDF [media] disegnata nel rettangolo dato (coordinate della pagina Samsung). */
    class PdfPaper(val media: Int, val page: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

    class Page(
        val width: Float,
        val height: Float,
        /** Motivo del foglio: 0 nessuno, 1–3 righe, 4–6 quadretti, 7–9 puntini (stretti/medi/larghi), 16 PDF. */
        val template: Int,
        /** Colore della carta (ARGB) o null se non salvato. */
        val paper: Int?,
        val pdf: PdfPaper?,
        val inks: List<Ink>,
        val pictures: List<Picture>,
    ) {
        val isEmpty get() = inks.isEmpty() && pictures.isEmpty() && pdf == null
    }

    class Note(
        /** Titolo dato in Samsung Notes (null se la nota non ne ha uno). */
        val title: String?,
        /** Creazione e ultima modifica, ms dal 1970 (0 se mancano). */
        val created: Long,
        val modified: Long,
        val pages: List<Page>,
        /** Penne per id ("com.samsung…FountainPen"); le forme hanno id -1. */
        val pens: Map<Int, String>,
        /** File estratti in media/, per id di collegamento. */
        val media: Map<Int, File>,
        /** C'era testo scritto con la tastiera (titolo a parte): non si importa. */
        val typedText: Boolean,
        /** Oggetti che Tratto non sa rappresentare (caselle di testo, audio, tabelle…). */
        val skipped: Int,
    ) {
        fun penName(ink: Ink): String = pens[ink.penId]?.substringAfterLast('.').orEmpty()
    }

    private val MEDIA_EXT = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif", "pdf")

    /**
     * Legge il quaderno da [input]; le immagini e i PDF finiscono in [mediaDir] (da cancellare dopo).
     * IOException con il motivo in italiano se il file non è un quaderno leggibile.
     */
    fun read(input: InputStream, mediaDir: File): Note {
        val pages = HashMap<String, PageOut>()
        var order: ByteArray? = null
        var note: ByteArray? = null
        var manifest: ByteArray? = null
        val files = HashMap<String, File>()
        try {
            ZipInputStream(input.buffered(1 shl 16)).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name
                    when {
                        name == "note.note" -> note = zip.readBytes()
                        name == "pageIdInfo.dat" -> order = zip.readBytes()
                        name == "media/mediaInfo.dat" -> manifest = zip.readBytes()
                        name.endsWith(".page") && '/' !in name ->
                            // si legge subito: in memoria resta il modello, non i byte della pagina
                            pages[name.removeSuffix(".page")] = runCatching { parsePage(zip.readBytes()) }.getOrElse { PageOut.broken() }
                        name.startsWith("media/") && name.substringAfterLast('.').lowercase() in MEDIA_EXT -> {
                            val short = name.substringAfterLast('/')
                            val f = File(mediaDir, "${files.size}_" + short.replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(80))
                            f.outputStream().use { zip.copyTo(it) }
                            files[short] = f
                        }
                    }
                }
            }
        } catch (e: java.util.zip.ZipException) {
            throw IOException("file rovinato o nota bloccata con password")
        }
        val noteBytes = note ?: throw IOException(if (pages.isEmpty()) "non è un file di Samsung Notes (o è una nota bloccata)" else "file di Samsung Notes incompleto")
        val header = runCatching { parseNote(noteBytes) }.getOrNull()
        // ordine delle pagine: pageIdInfo.dat; se manca o è rotto, quello dello ZIP
        val ids = order?.let { runCatching { parseOrder(it) }.getOrNull() }?.filter { it in pages } ?: pages.keys.toList()
        val media = manifest?.let { runCatching { parseManifest(it) }.getOrNull() }.orEmpty()
            .mapNotNull { (id, name) -> files[name]?.let { id to it } }.toMap()
        val outs = ids.map { pages.getValue(it) }
        return Note(
            title = header?.title,
            created = header?.created ?: 0L,
            modified = header?.modified ?: 0L,
            pages = outs.map { it.page() },
            pens = header?.strings.orEmpty(),
            media = media,
            typedText = (header?.bodyText ?: false) || outs.any { it.typedText },
            skipped = outs.sumOf { it.skipped },
        )
    }

    // ---------------- note.note ----------------

    private class NoteHeader(val title: String?, val created: Long, val modified: Long, val strings: Map<Int, String>, val bodyText: Boolean)

    private fun parseNote(b: ByteArray): NoteHeader {
        val r = Reader(b)
        val flexible = r.u32()
        r.mask()
        val fields = r.mask()
        r.i32()   // versione
        r.string()   // id della nota
        r.i32()   // revisione
        // i tempi sono in microsecondi
        val created = r.i64() / 1000
        val modified = r.i64() / 1000
        r.skip(20)   // misure, margini, versione minima
        val titleSize = r.u32()
        val title = text(b, r.pos, r.pos + titleSize)?.trim()?.ifEmpty { null }
        r.skip(titleSize)
        val bodySize = r.u32()
        val bodyText = !text(b, r.pos, r.pos + bodySize).isNullOrBlank()
        r.pos = flexible
        val strings = HashMap<Int, String>()
        // campi facoltativi in ordine di bit: si scorre fino alla tabella dei nomi (bit 10)
        for (bit in 0..10) {
            if (fields shr bit and 1L == 0L) continue
            when (bit) {
                0 -> r.string()
                1 -> { r.i32(); r.i32(); r.string() }
                2 -> { r.string(); r.string(); r.string(); r.i32() }
                3 -> r.skip(16)
                6 -> r.string()
                7 -> r.i32()
                9 -> { r.i32(); r.i64() }
                10 -> {
                    val end = r.pos + 4 + r.u32()
                    repeat(r.u16()) {
                        val id = r.i32()
                        r.string()?.let { strings[id] = it }
                    }
                    r.pos = end
                }
                else -> break   // campo sconosciuto prima della tabella: niente nomi delle penne
            }
        }
        return NoteHeader(title, created, modified, strings, bodyText)
    }

    private fun parseOrder(b: ByteArray): List<String> {
        val r = Reader(b)
        r.skip(32)
        return List(r.u16()) {
            val id = r.string().orEmpty()
            r.skip(32)
            id
        }
    }

    /** id di collegamento → nome del file in media/. */
    private fun parseManifest(b: ByteArray): List<Pair<Int, String>> {
        val r = Reader(b)
        val tail = String(b, maxOf(0, b.size - 4), minOf(4, b.size), Charsets.US_ASCII)
        val out = ArrayList<Pair<Int, String>>()
        if (tail == "EOFX") {
            r.u32()   // versione
            repeat(r.u16()) {
                val end = r.pos + 4 + r.u32()
                val id = r.i32()
                r.string()?.let { out.add(id to it) }
                r.pos = end
            }
        } else {
            repeat(r.u16()) {
                val id = r.i32()
                val name = r.string()
                r.skip(64 + 2 + 8)
                name?.let { out.add(id to it) }
            }
        }
        return out
    }

    // ---------------- <uuid>.page ----------------

    private class PageOut(
        val width: Float,
        val height: Float,
        val template: Int,
        val paper: Int?,
        val pdf: PdfPaper?,
    ) {
        val inks = ArrayList<Ink>()
        val pictures = ArrayList<Picture>()
        var typedText = false
        var skipped = 0

        fun page() = Page(width, height, template, paper, pdf, inks, pictures)

        companion object {
            fun broken() = PageOut(0f, 0f, 0, null, null).apply { skipped = 1 }
        }
    }

    private fun parsePage(b: ByteArray): PageOut {
        val r = Reader(b)
        val layers = r.u32()
        val flexible = r.u32()
        r.mask()
        val fields = r.mask()
        r.i32()   // orientamento
        val w = r.u32().toFloat()
        val h = r.u32().toFloat()
        r.skip(8)   // scostamento
        r.string()
        r.i64()
        val version = r.i32()
        r.pos = flexible
        var template = 0
        var paper: Int? = null
        var pdf: PdfPaper? = null
        run fields@{
            for (bit in 0..9) {
                if (fields shr bit and 1L == 0L) continue
                when (bit) {
                    0 -> r.skip(32)
                    1 -> repeat(r.u16()) { r.string() }
                    2 -> r.string()
                    3, 4, 6, 7 -> r.i32()
                    5 -> paper = r.i32()
                    8 -> repeat(r.u16()) {
                        val media = r.i32()
                        val index = r.i32()
                        // dalla versione 2034 il rettangolo è in interi, prima in float
                        val rect = FloatArray(4) { if (version >= 2034) r.i32().toFloat() else r.f32() }
                        if (pdf == null) pdf = PdfPaper(media, index, rect[0], rect[1], rect[2], rect[3])
                    }
                    9 -> template = r.i32()
                    else -> return@fields
                }
            }
        }
        val out = PageOut(w, h, if (pdf != null) 16 else template, paper, pdf)
        r.pos = layers
        val count = r.u16()
        r.u16()   // livello corrente
        repeat(count) {
            val start = r.pos
            val headerSize = r.u32()
            r.u32()
            val hidden = r.mask() and 1L != 0L
            r.pos = start + headerSize
            repeat(r.u32()) { readObject(b, r, out, hidden) }
            r.skip(32)
        }
        return out
    }

    /** Un oggetto e i suoi figli (che lo seguono subito). Un oggetto illeggibile si salta e si conta. */
    private fun readObject(b: ByteArray, r: Reader, out: PageOut, hidden: Boolean) {
        val type = r.u8()
        val children = r.u16()
        val size = r.u32()
        val start = r.pos
        if (size < 32 || start + size > b.size) throw IOException("oggetto fuori misura")
        val end = start + size - 32   // in fondo c'è l'impronta SHA-256 dell'oggetto
        if (!hidden) {
            try {
                val frames = frames(b, start, end)
                val visible = frames.firstOrNull()?.let { it.type == 0 && it.props and 0x8L != 0L } ?: false
                if (visible) when (type) {
                    1 -> stroke(b, frames)?.let(out.inks::add)
                    7 -> out.inks.addAll(shape(b, frames))
                    8 -> out.inks.addAll(line(b, frames))
                    3 -> picture(b, frames)?.let(out.pictures::add) ?: out.skipped++
                    2 -> { out.typedText = true; out.skipped++ }
                    4, 100 -> Unit   // contenitori: contano i figli
                    else -> out.skipped++
                }
            } catch (e: Exception) {
                out.skipped++
            }
        }
        r.pos = start + size
        repeat(children) { readObject(b, r, out, hidden) }
    }

    private class Frame(val start: Int, val size: Int, val type: Int, val flexible: Int, val props: Long, val fields: Long, val fixed: Int)

    /** Catena di frame dell'oggetto: base (tipo 0), poi quelli del suo tipo. */
    private fun frames(b: ByteArray, start: Int, end: Int): List<Frame> {
        val out = ArrayList<Frame>(4)
        var p = start
        while (p + 10 <= end) {
            val r = Reader(b, p)
            val size = r.u32()
            val type = r.i16()
            val flexible = r.u32()
            val props = r.mask()
            val fields = r.mask()
            if (size < 10 || p + size > end) break
            out.add(Frame(p, size, type, p + flexible, props, fields, r.pos))
            p += size
        }
        return out
    }

    /** Rettangolo dell'oggetto dal frame base: sinistra, alto, destra, basso. */
    private fun bounds(b: ByteArray, base: Frame): DoubleArray {
        val r = Reader(b, base.fixed)
        r.u32()
        r.skip(r.u16())   // UUID
        r.i64()
        return DoubleArray(4) { r.f64() }
    }

    private fun stroke(b: ByteArray, frames: List<Frame>): Ink? {
        val f = frames.firstOrNull { it.type == 1 } ?: return null
        if (f.props and 0x8L != 0L) return null   // tratto di gomma
        val r = Reader(b, f.fixed)
        val n = r.u16()
        if (n == 0) return null
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val ps = FloatArray(n)
        val ts = LongArray(n)
        if (f.props and 0x1L != 0L) {
            // compresso: primo valore pieno, poi differenze su 16 bit (segno + modulo in virgola fissa)
            var x = r.f64()
            var y = r.f64()
            xs[0] = x.toFloat(); ys[0] = y.toFloat()
            for (i in 1 until n) {
                x += delta(r.u16(), 32.0); y += delta(r.u16(), 32.0)
                xs[i] = x.toFloat(); ys[i] = y.toFloat()
            }
            var p = r.f32().toDouble()
            ps[0] = p.toFloat()
            for (i in 1 until n) { p += delta(r.u16(), 4096.0); ps[i] = p.toFloat() }
            var t = r.i32().toLong()
            ts[0] = t
            for (i in 1 until n) { t += r.u16(); ts[i] = t }
        } else {
            for (i in 0 until n) { xs[i] = r.f64().toFloat(); ys[i] = r.f64().toFloat() }
            for (i in 0 until n) ps[i] = r.f32()
            for (i in 0 until n) ts[i] = r.i32().toLong()
        }
        // stile: campi facoltativi in ordine di bit (spessore in byte di ciascuno)
        r.pos = f.flexible
        var pen = -1
        var legacyPen = -1
        var color = 0xFF000000.toInt()
        var size = 4f
        for (bit in 0..25) {
            if (f.fields shr bit and 1L == 0L) continue
            when (bit) {
                0 -> legacyPen = r.i32()
                2 -> color = r.i32()
                3 -> size = r.f32()
                7 -> pen = r.i32()
                4 -> r.skip(1)
                14, 16, 25 -> r.skip(2)
                5, 24 -> break   // campi di lunghezza variabile: quelli che servono sono già letti
                else -> r.skip(4)
            }
        }
        if (pen < 0) pen = legacyPen
        val t0 = ts[0]
        for (i in 0 until n) ts[i] -= t0
        return Ink(pen, color, size, xs, ys, ps.map { it.coerceIn(0f, 1f) }.toFloatArray(), ts)
    }

    private fun delta(raw: Int, q: Double): Double {
        val m = (raw and 0x7FFF) / q
        return if (raw and 0x8000 != 0) -m else m
    }

    // ---------------- forme e linee → tratti ----------------

    /** Contorno: colore (null = nascosto), spessore, frecce all'inizio e alla fine. */
    private class Outline(val color: Int?, val width: Float, val beginArrow: Int, val endArrow: Int)

    private fun outline(b: ByteArray, frames: List<Frame>): Outline {
        val f = frames.firstOrNull { it.type == 6 } ?: return Outline(0xFF000000.toInt(), 2f, 0, 0)
        if (f.fields and 0x3L != 0L) return Outline(0xFF000000.toInt(), 2f, 0, 0)   // campi ignoti prima dello stile
        val r = Reader(b, f.flexible)
        var color: Int? = 0xFF000000.toInt()
        var width = 2f
        var begin = 0
        var end = 0
        if (f.fields and 0x4L != 0L) {
            val next = r.pos + 4 + r.u32()
            r.mask()
            val kind = r.u8()
            val argb = r.i32()
            color = when (kind) {
                2 -> null   // nessun contorno
                else -> argb   // tinta unita (le sfumature: si usa il colore di base)
            }
            r.pos = next
        }
        if (f.fields and 0x8L != 0L) {
            val next = r.pos + 4 + r.u32()
            width = r.f32()
            r.skip(4)   // composto, tratteggio, estremità, giunzioni
            begin = r.u8(); r.u8()
            end = r.u8()
            r.pos = next
        }
        return Outline(color, width, begin, end)
    }

    private fun shape(b: ByteArray, frames: List<Frame>): List<Ink> {
        val line = outline(b, frames)
        val color = line.color ?: return emptyList()
        val f = frames.firstOrNull { it.type == 7 } ?: return emptyList()
        val r = Reader(b, f.fixed)
        val kind = r.u32()
        val rect = DoubleArray(4) { r.f64() }
        val rotation = r.f32()
        val pathSize = r.u32()
        val pathStart = r.pos
        val paths = if (pathSize > 0) runCatching { path(Reader(b, pathStart), pathStart + pathSize) }.getOrNull() else null
        val polylines = paths?.takeIf { it.isNotEmpty() } ?: template(kind, rect, rotation)
        return polylines.map { solid(it, color, line.width) }
    }

    private fun line(b: ByteArray, frames: List<Frame>): List<Ink> {
        val line = outline(b, frames)
        val color = line.color ?: return emptyList()
        val f = frames.firstOrNull { it.type == 8 } ?: return emptyList()
        val r = Reader(b, f.fixed)
        r.u8(); r.u8()
        r.skip(r.u8() * 16)   // punti di controllo
        val x0 = r.f64(); val y0 = r.f64()
        val x1 = r.f64(); val y1 = r.f64()
        // linee a gomito o curve: il percorso salvato (bit 3) le descrive già
        var shapePath: List<FloatArray>? = null
        if (f.fields and 0x8L != 0L && f.fields and 0x1L == 0L) {
            val p = Reader(b, f.flexible)
            if (f.fields and 0x2L != 0L) p.skip(4)
            if (f.fields and 0x4L != 0L) p.skip(4)
            shapePath = runCatching { path(p, f.start + f.size) }.getOrNull()?.takeIf { it.isNotEmpty() }
        }
        val main = shapePath ?: listOf(floatArrayOf(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat()))
        val out = main.mapTo(ArrayList()) { solid(it, color, line.width) }
        if (line.endArrow != 0) out.add(solid(arrow(main.last(), atEnd = true, line.width), color, line.width))
        if (line.beginArrow != 0) out.add(solid(arrow(main.first(), atEnd = false, line.width), color, line.width))
        return out
    }

    /** Punta di freccia (due trattini) all'estremo della polilinea [pts] (x,y,x,y…). */
    private fun arrow(pts: FloatArray, atEnd: Boolean, width: Float): FloatArray {
        val n = pts.size / 2
        val (tip, from) = if (atEnd) (n - 1) to (n - 2).coerceAtLeast(0) else 0 to minOf(1, n - 1)
        val tx = pts[tip * 2]; val ty = pts[tip * 2 + 1]
        val angle = atan2(ty - pts[from * 2 + 1], tx - pts[from * 2]).toDouble()
        val len = maxOf(width * 4f, 18f)
        fun wing(a: Double) = floatArrayOf((tx - len * cos(angle + a)).toFloat(), (ty - len * sin(angle + a)).toFloat())
        val l = wing(0.45)
        val rr = wing(-0.45)
        return floatArrayOf(l[0], l[1], tx, ty, rr[0], rr[1])
    }

    /** Percorso salvato (u32 comandi, verbo + coordinate f64) → polilinee x,y,x,y… */
    private fun path(r: Reader, limit: Int): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var cur = ArrayList<Float>()
        var sx = 0f; var sy = 0f
        var px = 0f; var py = 0f
        fun flush() { if (cur.size >= 4) out.add(cur.toFloatArray()); cur = ArrayList() }
        fun add(x: Float, y: Float) { cur.add(x); cur.add(y); px = x; py = y }
        val count = r.u32()
        if (count > 100_000) throw IOException("percorso troppo lungo")
        repeat(count) {
            if (r.pos >= limit) throw IOException("percorso tronco")
            when (r.u8()) {
                1 -> { flush(); val x = r.f64().toFloat(); val y = r.f64().toFloat(); sx = x; sy = y; add(x, y) }
                2 -> add(r.f64().toFloat(), r.f64().toFloat())
                3 -> {
                    val cx = r.f64().toFloat(); val cy = r.f64().toFloat(); val x = r.f64().toFloat(); val y = r.f64().toFloat()
                    val x0 = px; val y0 = py
                    for (i in 1..16) {
                        val t = i / 16f; val u = 1 - t
                        add(u * u * x0 + 2 * u * t * cx + t * t * x, u * u * y0 + 2 * u * t * cy + t * t * y)
                    }
                }
                4 -> {
                    val c = FloatArray(6) { r.f64().toFloat() }
                    val x0 = px; val y0 = py
                    for (i in 1..20) {
                        val t = i / 20f; val u = 1 - t
                        add(
                            u * u * u * x0 + 3 * u * u * t * c[0] + 3 * u * t * t * c[2] + t * t * t * c[4],
                            u * u * u * y0 + 3 * u * u * t * c[1] + 3 * u * t * t * c[3] + t * t * t * c[5],
                        )
                    }
                }
                5 -> { r.skip(32); val x = r.f64().toFloat(); val y = r.f64().toFloat(); add(x, y) }   // arco: approssimato
                6 -> { add(sx, sy); flush(); px = sx; py = sy }
                7 -> {
                    flush()
                    val l = r.f64(); val t = r.f64(); val rr = r.f64(); val bb = r.f64()
                    out.add(ellipse(l, t, rr, bb, 0f))
                }
                else -> throw IOException("comando di percorso sconosciuto")
            }
        }
        flush()
        return out
    }

    /** Forme senza percorso salvato: dal tipo e dal rettangolo. */
    private fun template(kind: Int, rc: DoubleArray, rotation: Float): List<FloatArray> {
        val (l, t, r, b) = rc.map { it.toFloat() }
        val mx = (l + r) / 2; val my = (t + b) / 2
        val pts = when (kind) {
            1 -> return listOf(ellipse(rc[0], rc[1], rc[2], rc[3], rotation))
            2 -> floatArrayOf(mx, t, r, b, l, b, mx, t)
            3 -> floatArrayOf(l, t, r, b, l, b, l, t)
            8 -> floatArrayOf(mx, t, r, my, mx, b, l, my, mx, t)
            else -> floatArrayOf(l, t, r, t, r, b, l, b, l, t)
        }
        if (rotation != 0f) rotate(pts, mx, my, rotation)
        return listOf(pts)
    }

    private fun ellipse(l: Double, t: Double, r: Double, b: Double, rotation: Float): FloatArray {
        val cx = (l + r) / 2; val cy = (t + b) / 2; val rx = (r - l) / 2; val ry = (b - t) / 2
        val steps = 72
        val out = FloatArray((steps + 1) * 2)
        for (i in 0..steps) {
            val a = 2 * Math.PI * i / steps
            out[i * 2] = (cx + rx * cos(a)).toFloat()
            out[i * 2 + 1] = (cy + ry * sin(a)).toFloat()
        }
        if (rotation != 0f) rotate(out, cx.toFloat(), cy.toFloat(), rotation)
        return out
    }

    private fun rotate(pts: FloatArray, cx: Float, cy: Float, degrees: Float) {
        val a = Math.toRadians(degrees.toDouble())
        val c = cos(a).toFloat(); val s = sin(a).toFloat()
        for (i in pts.indices step 2) {
            val dx = pts[i] - cx; val dy = pts[i + 1] - cy
            pts[i] = cx + dx * c - dy * s
            pts[i + 1] = cy + dx * s + dy * c
        }
    }

    /** Polilinea → tratto a spessore costante, con punti fitti (Ink unisce i punti con segmenti dritti). */
    private fun solid(pts: FloatArray, color: Int, width: Float): Ink {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val step = 2f
        for (i in 0 until pts.size / 2) {
            val x = pts[i * 2]; val y = pts[i * 2 + 1]
            if (xs.isNotEmpty()) {
                val x0 = xs.last(); val y0 = ys.last()
                val k = (hypot(x - x0, y - y0) / step).toInt()
                for (j in 1 until k) { xs.add(x0 + (x - x0) * j / k); ys.add(y0 + (y - y0) * j / k) }
            }
            xs.add(x); ys.add(y)
        }
        return Ink(-1, color, width, xs.toFloatArray(), ys.toFloatArray(), null, LongArray(xs.size) { it * 2L })
    }

    // ---------------- immagini ----------------

    private fun picture(b: ByteArray, frames: List<Frame>): Picture? {
        val base = frames.firstOrNull { it.type == 0 } ?: return null
        val f = frames.firstOrNull { it.type == 7 } ?: return null
        // id del file: nel riempimento "immagine" del frame 7 (bit 5), dopo i campi che lo precedono
        if (f.fields and 0x20L == 0L) return null
        val r = Reader(b, f.flexible)
        if (f.fields and 0x1L != 0L) r.skip(r.u32())   // testo
        if (f.fields and 0x2L != 0L) r.skip(1)
        if (f.fields and 0x4L != 0L) r.skip(4)
        if (f.fields and 0x8L != 0L) r.skip(4)
        if (f.fields and 0x10L != 0L) r.skip(4)
        r.u32()   // misura del riempimento
        if (r.u8() != 2) return null
        r.u8()   // modo di riempimento
        val media = r.i32()
        if (media < 0) return null
        val bb = bounds(b, base)
        return Picture(media, bb[0].toFloat(), bb[1].toFloat(), bb[2].toFloat(), bb[3].toFloat())
    }

    /** Testo di un oggetto titolo/corpo: nel frame 7, bit 0 (u32 misura, u32 caratteri UTF-16, testo). */
    private fun text(b: ByteArray, start: Int, end: Int): String? = runCatching {
        frames(b, start, end).firstOrNull { it.type == 7 && it.fields and 0x1L != 0L }?.let { f ->
            val r = Reader(b, f.flexible)
            r.u32()
            val n = r.u32()
            r.skip(n * 2)
            String(b, r.pos - n * 2, n * 2, Charsets.UTF_16LE)
        }
    }.getOrNull()

    /** Letture little-endian con controllo dei limiti (IndexOutOfBounds → oggetto saltato). */
    private class Reader(val b: ByteArray, var pos: Int = 0) {
        fun u8(): Int = b[pos++].toInt() and 0xFF
        fun u16(): Int = u8() or (u8() shl 8)
        fun i16(): Int = u16().toShort().toInt()
        fun i32(): Int = u16() or (u16() shl 16)
        fun u32(): Int = i32().also { if (it < 0) throw IOException("valore fuori misura") }
        fun i64(): Long = (i32().toLong() and 0xFFFFFFFFL) or (i32().toLong() shl 32)
        fun f32(): Float = Float.fromBits(i32())
        fun f64(): Double = Double.fromBits(i64())
        fun skip(n: Int) {
            if (n < 0 || pos + n > b.size) throw IOException("fuori dal file")
            pos += n
        }

        /** Maschera di bit preceduta dal numero di byte. */
        fun mask(): Long {
            val n = u8()
            var v = 0L
            for (i in 0 until n) { val x = u8().toLong(); if (i < 8) v = v or (x shl (8 * i)) }
            return v
        }

        /** Stringa UTF-16: u16 numero di unità, poi le unità; 0xFFFF = assente. */
        fun string(): String? {
            val n = u16()
            if (n == 0xFFFF) return null
            skip(n * 2)
            return String(b, pos - n * 2, n * 2, Charsets.UTF_16LE)
        }
    }
}
