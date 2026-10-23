package com.caro.tratto.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.io.File
import java.util.concurrent.Executors

/**
 * Bitmap delle immagini di un quaderno per lo schermo: ridotte a [maxSide] px, decodificate in
 * background (lo schermo non aspetta mai il disco) e tenute in una cache LRU limitata in byte.
 */
class ImageLoader(private val fileFor: (String) -> File, private val maxSide: Int = 2048) {

    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }
    private val pending = HashSet<String>()
    private val main = Handler(Looper.getMainLooper())

    /** Bitmap se già pronta; altrimenti null e la decodifica parte in background ([onReady] sul main thread). */
    fun getAsync(name: String, onReady: () -> Unit): Bitmap? {
        cache.get(name)?.let { return it }
        synchronized(pending) { if (!pending.add(name)) return null }
        decoder.execute {
            val bmp = decode(fileFor(name), maxSide)
            if (bmp != null) cache.put(name, bmp)
            synchronized(pending) { pending.remove(name) }
            if (bmp != null) main.post(onReady)
        }
        return null
    }

    companion object {
        private val decoder = Executors.newSingleThreadExecutor()

        /**
         * Decodifica con campionamento a potenze di 2 fino a stare sotto [maxSide].
         * Le foto JPEG (senza trasparenza) in RGB_565: metà memoria, e nel PDF nessun canale alfa.
         */
        fun decode(file: File, maxSide: Int): Bitmap? = runCatching {
            if (!file.exists()) return null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                if (file.extension.equals("jpg", true) || file.extension.equals("jpeg", true)) {
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
            }
            val bmp = BitmapFactory.decodeFile(file.path, opts) ?: return null
            val side = maxOf(bmp.width, bmp.height)
            if (side <= maxSide) return bmp
            // rifinitura: esattamente maxSide sul lato lungo
            val k = maxSide.toFloat() / side
            Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
                .also { if (it !== bmp) bmp.recycle() }
        }.getOrNull()
    }
}
