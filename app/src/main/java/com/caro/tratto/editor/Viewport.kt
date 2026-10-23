package com.caro.tratto.editor

import android.graphics.Matrix
import com.caro.tratto.model.PageFormat
import kotlin.math.floor

/**
 * Trasformazione documento → schermo: screen = doc * scale + offset.
 * Il documento è la pila verticale delle pagine (punti PDF), separate da GAP_PT.
 */
internal class Viewport {
    var format: PageFormat = PageFormat.A4_PORTRAIT
    var pageCount = 1

    var scale = 1f          // px per punto
    var offsetX = 0f
    var offsetY = 0f
    var fitScale = 1f       // scala "adatta alla larghezza"

    val pageW get() = format.widthPt.toFloat()
    val pageH get() = format.heightPt.toFloat()

    fun pageTop(index: Int) = index * (pageH + GAP_PT)
    val docHeight get() = pageCount * pageH + (pageCount - 1).coerceAtLeast(0) * GAP_PT

    fun toDocX(sx: Float) = (sx - offsetX) / scale
    fun toDocY(sy: Float) = (sy - offsetY) / scale

    /** Pagina che contiene il punto schermo, oppure -1 (gap o fuori dal foglio). */
    fun pageAt(sx: Float, sy: Float): Int {
        val dx = toDocX(sx)
        val dy = toDocY(sy)
        if (dx < 0f || dx > pageW || dy < 0f) return -1
        val i = floor(dy / (pageH + GAP_PT)).toInt()
        if (i >= pageCount) return -1
        return if (dy - pageTop(i) <= pageH) i else -1
    }

    /** Pagina più vicina al punto (per la gomma che attraversa i gap). */
    fun nearestPage(sy: Float): Int =
        floor(toDocY(sy) / (pageH + GAP_PT)).toInt().coerceIn(0, pageCount - 1)

    /** Matrice schermo → coordinate locali della pagina. */
    fun screenToPage(page: Int, out: Matrix): Matrix {
        out.setTranslate(-offsetX, -offsetY)
        out.postScale(1f / scale, 1f / scale)
        out.postTranslate(0f, -pageTop(page))
        return out
    }

    fun clamp(viewW: Int, viewH: Int, marginPx: Float) {
        val contentW = pageW * scale
        offsetX = if (contentW + 2 * marginPx <= viewW) {
            (viewW - contentW) / 2f
        } else {
            offsetX.coerceIn(viewW - contentW - marginPx, marginPx)
        }
        val contentH = docHeight * scale
        val minY = viewH - contentH - marginPx * 4   // un po' di spazio sotto l'ultima pagina
        offsetY = if (minY > marginPx) marginPx else offsetY.coerceIn(minY, marginPx)
    }

    companion object {
        const val GAP_PT = 24f
    }
}
