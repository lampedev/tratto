package com.caro.tratto.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.LruCache
import java.io.Closeable
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * Pagine del PDF di un quaderno, rese con il PdfRenderer di Android su un thread dedicato (il
 * renderer non è thread-safe e tiene aperta una pagina alla volta) e tenute in una cache LRU.
 *
 * Si chiede una larghezza in pixel: se in cache c'è una resa più piccola la si usa subito (un po'
 * sfocata) e intanto parte quella giusta. Le richieste vecchie (pagine già scorse via) si saltano.
 * Le bitmap non si riciclano mai a mano: possono essere ancora dentro un RenderNode dello schermo.
 */
class PdfPages(private val file: File) : Closeable {

    /** Pagina resa: [widthPt] x [heightPt] = misura della pagina del PDF (punti, già ruotata). */
    class Rendered(val bitmap: Bitmap, val widthPt: Float, val heightPt: Float)

    private class Request(val width: Int, var time: Long, var onReady: () -> Unit)

    private val cache = object : LruCache<Int, Rendered>(CACHE_BYTES) {
        override fun sizeOf(key: Int, value: Rendered) = value.bitmap.allocationByteCount
    }
    private val requests = LinkedHashMap<Int, Request>()
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "tratto-pdf").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val main = Handler(Looper.getMainLooper())
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    @Volatile private var closed = false

    /** Resa della pagina se c'è (anche più piccola del voluto); se serve, quella giusta parte in background. */
    fun get(page: Int, widthPx: Int, onReady: () -> Unit): Rendered? {
        val want = widthPx.coerceIn(64, MAX_WIDTH)
        val have = cache.get(page)
        if (have != null && have.bitmap.width >= want * 0.85f) return have
        val now = SystemClock.uptimeMillis()
        synchronized(requests) {
            val r = requests[page]
            if (r != null && r.width >= want) {
                r.time = now
                r.onReady = onReady
            } else {
                requests.remove(page)
                requests[page] = Request(want, now, onReady)
                worker.execute(::serveNewest)
            }
        }
        return have
    }

    /** Resa sincrona (miniature, esportazione PNG): aspetta il thread del PDF. */
    fun renderBlocking(page: Int, widthPx: Int): Rendered? {
        if (closed) return null
        val want = widthPx.coerceIn(16, MAX_WIDTH)
        cache.get(page)?.takeIf { it.bitmap.width >= want }?.let { return it }
        return runCatching { worker.submit(Callable { render(page, want, keep = false) }).get() }.getOrNull()
    }

    /** Resa già pronta, senza chiederne altre. */
    fun peek(page: Int): Rendered? = cache.get(page)

    @Volatile private var regionBatch = 0

    /** Nuovo gruppo di ritagli: quelli dei gruppi precedenti ancora in coda (si stava scorrendo) si saltano. */
    fun newRegionBatch(): Int = ++regionBatch

    /**
     * Ritaglio nitido in background: la zona [pageRect] della pagina (punti della pagina del PDF)
     * resa in [outW] x [outH] pixel, per lo zoom forte; [onDone] sul main thread.
     */
    fun renderRegionAsync(batch: Int, page: Int, pageRect: RectF, outW: Int, outH: Int, onDone: (Bitmap) -> Unit) {
        if (closed || outW <= 0 || outH <= 0) return
        val rect = RectF(pageRect)
        worker.execute {
            if (batch != regionBatch || closed) return@execute
            val bmp = renderRegionNow(page, rect, outW, outH) ?: return@execute
            main.post { if (!closed) onDone(bmp) }
        }
    }

    /** Sul thread del PDF. */
    private fun renderRegionNow(page: Int, pageRect: RectF, outW: Int, outH: Int): Bitmap? = runCatching {
        val r = open() ?: return null
        if (page !in 0 until r.pageCount) return null
        r.openPage(page).use { p ->
            val bmp = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)
            val m = Matrix()
            m.setTranslate(-pageRect.left, -pageRect.top)
            m.postScale(outW / pageRect.width(), outH / pageRect.height())
            p.render(bmp, Rect(0, 0, outW, outH), m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp
        }
    }.getOrNull()

    /** Toglie dalla coda le richieste in sospeso (si è cambiato zoom o pagina). */
    fun cancelPending() = synchronized(requests) { requests.clear() }

    private fun serveNewest() {
        val now = SystemClock.uptimeMillis()
        val (page, req) = synchronized(requests) {
            // la più recente per prima: è la pagina che si sta guardando adesso
            var pick: Map.Entry<Int, Request>? = null
            for (e in requests.entries) if (pick == null || e.value.time >= pick.value.time) pick = e
            pick ?: return
            requests.remove(pick.key)
            pick.key to pick.value
        }
        if (now - req.time > STALE_MS || closed) return
        if (render(page, req.width, keep = true) != null) main.post { if (!closed) req.onReady() }
    }

    private fun open(): PdfRenderer? {
        if (closed) return null
        renderer?.let { return it }
        return runCatching {
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            pfd = fd
            PdfRenderer(fd).also { renderer = it }
        }.getOrNull()
    }

    /** Sul thread del PDF. */
    private fun render(page: Int, width: Int, keep: Boolean): Rendered? = runCatching {
        val r = open() ?: return null
        if (page !in 0 until r.pageCount) return null
        r.openPage(page).use { p ->
            val wPt = p.width.toFloat()
            val hPt = p.height.toFloat()
            var w = width
            var h = (w * hPt / wPt).roundToInt().coerceAtLeast(1)
            // pagine molto lunghe: limite in pixel totali, non solo in larghezza
            if (w.toLong() * h > MAX_PIXELS) {
                val k = Math.sqrt(MAX_PIXELS.toDouble() / (w.toLong() * h)).toFloat()
                w = (w * k).toInt().coerceAtLeast(1); h = (h * k).toInt().coerceAtLeast(1)
            }
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(Color.WHITE)   // le pagine del PDF possono essere trasparenti
            p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            Rendered(bmp, wPt, hPt).also { if (keep) cache.put(page, it) }
        }
    }.getOrNull()

    override fun close() {
        closed = true
        cancelPending()
        worker.execute {
            runCatching { renderer?.close() }
            runCatching { pfd?.close() }
            renderer = null
            pfd = null
        }
        worker.shutdown()
        cache.evictAll()
    }

    companion object {
        private const val CACHE_BYTES = 96 * 1024 * 1024
        private const val MAX_WIDTH = 2600
        private const val MAX_PIXELS = 2600L * 3700L
        private const val STALE_MS = 2500L

        /**
         * Numero di pagine e misura (punti) della prima pagina. Eccezioni: SecurityException se il PDF
         * è protetto da password, IOException se non è un PDF leggibile.
         */
        fun inspect(file: File): Triple<Int, Float, Float> =
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
                PdfRenderer(fd).use { r ->
                    if (r.pageCount <= 0) throw java.io.IOException("il PDF non ha pagine")
                    r.openPage(0).use { p -> Triple(r.pageCount, p.width.toFloat(), p.height.toFloat()) }
                }
            }

        /** Resa singola con un renderer usa-e-getta (copertine, export). */
        fun renderOnce(file: File, page: Int, widthPx: Int): Rendered? {
            val pages = PdfPages(file)
            return try {
                pages.renderBlocking(page, widthPx)
            } finally {
                pages.close()
            }
        }

        /** Dove sta la pagina del PDF dentro il foglio del quaderno: adattata e centrata. */
        fun fitRect(widthPt: Float, heightPt: Float, frameW: Float, frameH: Float, out: RectF): RectF {
            val k = minOf(frameW / widthPt, frameH / heightPt)
            val w = widthPt * k
            val h = heightPt * k
            return out.apply { set((frameW - w) / 2f, (frameH - h) / 2f, (frameW + w) / 2f, (frameH + h) / 2f) }
        }
    }
}
