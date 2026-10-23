package com.caro.tratto.editor

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * "Disegna e tieni premuto": riconosce linea, triangolo, rettangolo/quadrilatero ed ellisse
 * da un tratto a mano libera. Puro Kotlin + Ink (testato anche su JVM). Coordinate in punti pagina.
 */

internal sealed interface Shape {
    class Line(val x0: Float, val y0: Float, val x1: Float, val y1: Float) : Shape
    /** Poligono chiuso: vertici x0,y0,x1,y1,... */
    class Polygon(val pts: FloatArray) : Shape {
        val corners get() = pts.size / 2
    }
    class Ellipse(val cx: Float, val cy: Float, val rx: Float, val ry: Float, val angle: Float) : Shape
}

internal object ShapeRecognizer {

    fun recognize(p: FloatArray): Shape? {
        val n = p.size / 2
        if (n < 6) return null
        var length = 0f
        for (i in 1 until n) length += hypot(p[2 * i] - p[2 * i - 2], p[2 * i + 1] - p[2 * i - 1])
        if (length < 10f) return null
        val chord = hypot(p[2 * n - 2] - p[0], p[2 * n - 1] - p[1])

        // linea: quasi tutto il percorso è la corda e nessun punto si allontana molto
        if (chord > 0.85f * length) {
            var maxDev = 0f
            for (i in 0 until n) maxDev = max(maxDev, distToLine(p[2 * i], p[2 * i + 1], p[0], p[1], p[2 * n - 2], p[2 * n - 1]))
            if (maxDev < max(2.5f, 0.07f * chord)) {
                return snapLine(p[0], p[1], p[2 * n - 2], p[2 * n - 1])
            }
            return null
        }
        // forme chiuse: inizio e fine vicini
        if (chord > 0.22f * length) return null

        // forma chiusa: si confrontano ellisse e poligono (3–4 angoli) e vince chi sta più vicino ai punti.
        // (Prima: un cerchio un po' ondulato, semplificato, sembrava un quadrato a 4 angoli.)
        return recognizeClosed(trimClosure(p), length)
    }

    /**
     * A mano la chiusura "sborda" oltre il punto di partenza: si taglia il tratto dove torna più
     * vicino all'inizio (nell'ultimo 35%), così il giro è uno solo e non nasce un angolo in più.
     */
    private fun trimClosure(p: FloatArray): FloatArray {
        val n = p.size / 2
        var best = n - 1
        var bestD = Float.MAX_VALUE
        for (k in (n * 0.65f).toInt() until n) {
            val d = hypot(p[2 * k] - p[0], p[2 * k + 1] - p[1])
            if (d < bestD) { bestD = d; best = k }
        }
        return p.copyOf(2 * (best + 1))
    }

    private fun recognizeClosed(p: FloatArray, fullLength: Float): Shape? {
        val n = p.size / 2
        var length = 0f
        for (i in 1 until n) length += hypot(p[2 * i] - p[2 * i - 2], p[2 * i + 1] - p[2 * i - 1])
        if (length < 0.5f * fullLength || n < 6) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            minX = min(minX, p[2 * i]); maxX = max(maxX, p[2 * i]); minY = min(minY, p[2 * i + 1]); maxY = max(maxY, p[2 * i + 1])
        }
        val scale = ((maxX - minX) + (maxY - minY)) / 4f            // ≈ raggio medio
        if (scale < 4f) return null
        val ellipse = fitEllipse(p)
        val ellipseErr = ellipse?.let { meanDistanceToEllipse(p, it) } ?: Float.MAX_VALUE
        val simplified = rdp(p, 0.045f * length)
        val corners = mergeCorners(cornersOf(simplified, minTurnDeg = 35f), 0.08f * length)
        val polygon = when (corners.size / 2) {
            3 -> Shape.Polygon(corners)
            4 -> Shape.Polygon(squareUp(corners))
            else -> null
        }
        val polygonErr = polygon?.let { meanDistanceToPolygon(p, it.pts) } ?: Float.MAX_VALUE
        return when {
            ellipse != null && ellipseErr <= polygonErr && ellipseErr < 0.20f * scale -> ellipse
            polygon != null && polygonErr < ellipseErr && polygonErr < 0.12f * scale -> polygon
            else -> null
        }
    }

    /** Unisce angoli consecutivi più vicini di [minDist] (anche fra l'ultimo e il primo). */
    private fun mergeCorners(c: FloatArray, minDist: Float): FloatArray {
        val pts = ArrayList<Pair<Float, Float>>()
        for (i in 0 until c.size / 2) pts.add(c[2 * i] to c[2 * i + 1])
        var changed = true
        while (changed && pts.size > 3) {
            changed = false
            for (i in pts.indices) {
                val j = (i + 1) % pts.size
                if (hypot(pts[i].first - pts[j].first, pts[i].second - pts[j].second) < minDist) {
                    val m = (pts[i].first + pts[j].first) / 2 to (pts[i].second + pts[j].second) / 2
                    pts[i] = m
                    pts.removeAt(j)
                    changed = true
                    break
                }
            }
        }
        return FloatArray(pts.size * 2) { if (it % 2 == 0) pts[it / 2].first else pts[it / 2].second }
    }

    /** Misure intermedie del riconoscimento (solo per i test). */
    internal fun debug(p: FloatArray): String {
        val n = p.size / 2
        var length = 0f
        for (i in 1 until n) length += hypot(p[2 * i] - p[2 * i - 2], p[2 * i + 1] - p[2 * i - 1])
        val chord = hypot(p[2 * n - 2] - p[0], p[2 * n - 1] - p[1])
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until n) { minX = min(minX, p[2 * i]); maxX = max(maxX, p[2 * i]); minY = min(minY, p[2 * i + 1]); maxY = max(maxY, p[2 * i + 1]) }
        val scale = ((maxX - minX) + (maxY - minY)) / 4f
        val e = fitEllipse(p)
        val ee = e?.let { meanDistanceToEllipse(p, it) }
        val corners = mergeCorners(cornersOf(rdp(p, 0.045f * length), 35f), 0.08f * length)
        val poly = when (corners.size / 2) { 3 -> corners; 4 -> squareUp(corners); else -> null }
        val pe = poly?.let { meanDistanceToPolygon(p, it) }
        return "n=$n chord/len=%.3f scale=%.1f ellErr/scale=%s corners=%d polyErr/scale=%s".format(
            chord / length, scale, ee?.let { "%.3f".format(it / scale) }, corners.size / 2, pe?.let { "%.3f".format(it / scale) })
    }

    /** Distanza media dei punti dal contorno del poligono chiuso. */
    private fun meanDistanceToPolygon(p: FloatArray, poly: FloatArray): Float {
        val n = p.size / 2
        val m = poly.size / 2
        var sum = 0f
        for (i in 0 until n) {
            var best = Float.MAX_VALUE
            for (j in 0 until m) {
                val k = (j + 1) % m
                best = min(best, distToSegment(p[2 * i], p[2 * i + 1], poly[2 * j], poly[2 * j + 1], poly[2 * k], poly[2 * k + 1]))
            }
            sum += best
        }
        return sum / n
    }

    /** Distanza media (approssimata) dei punti dall'ellisse. */
    private fun meanDistanceToEllipse(p: FloatArray, e: Shape.Ellipse): Float {
        val n = p.size / 2
        val ca = cos(e.angle); val sa = sin(e.angle)
        var sum = 0f
        for (i in 0 until n) {
            val dx = p[2 * i] - e.cx; val dy = p[2 * i + 1] - e.cy
            val u = dx * ca + dy * sa; val v = -dx * sa + dy * ca
            val rho = sqrt((u / e.rx) * (u / e.rx) + (v / e.ry) * (v / e.ry))
            if (rho < 1e-4f) { sum += min(e.rx, e.ry); continue }
            // punto dell'ellisse sulla stessa semiretta dal centro
            val ex = u / rho; val ey = v / rho
            sum += hypot(u - ex, v - ey)
        }
        return sum / n
    }

    private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 < 1e-6f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    // ---------------- linea ----------------

    /** Linee quasi orizzontali/verticali diventano perfettamente dritte (±6°). */
    /** Aggancia la linea a orizzontale/verticale (±6°) e, con [snap45], anche a 45° (±5°). */
    fun snapLine(x0: Float, y0: Float, x1: Float, y1: Float, snap45: Boolean = false): Shape.Line {
        if (snap45) {
            val dx = x1 - x0
            val dy = y1 - y0
            val ang = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
            val a = ((ang % 90f) + 90f) % 90f
            if (abs(a - 45f) < 5f) {
                val d = (abs(dx) + abs(dy)) / 2f
                return Shape.Line(x0, y0, x0 + d * kotlin.math.sign(dx), y0 + d * kotlin.math.sign(dy))
            }
        }
        return snapLineAxis(x0, y0, x1, y1)
    }

    private fun snapLineAxis(x0: Float, y0: Float, x1: Float, y1: Float): Shape.Line {
        val ang = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble())).toFloat()
        val a = ((ang % 90f) + 90f) % 90f
        return when {
            a < 6f || a > 84f -> {
                val horizontal = abs(x1 - x0) >= abs(y1 - y0)
                if (horizontal) Shape.Line(x0, y0, x1, y0) else Shape.Line(x0, y0, x0, y1)
            }
            else -> Shape.Line(x0, y0, x1, y1)
        }
    }

    // ---------------- poligoni ----------------

    /** Quadrilatero con lati quasi assiali (±10°) → rettangolo allineato. */
    private fun squareUp(c: FloatArray): FloatArray {
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val ang = Math.toDegrees(atan2((c[2 * j + 1] - c[2 * i + 1]).toDouble(), (c[2 * j] - c[2 * i]).toDouble())).toFloat()
            val a = ((ang % 90f) + 90f) % 90f
            if (a > 10f && a < 80f) return c
        }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until 4) {
            minX = min(minX, c[2 * i]); maxX = max(maxX, c[2 * i])
            minY = min(minY, c[2 * i + 1]); maxY = max(maxY, c[2 * i + 1])
        }
        return floatArrayOf(minX, minY, maxX, minY, maxX, maxY, minX, maxY)
    }

    /** Vertici del poligono chiuso dove la direzione cambia più di [minTurnDeg]. */
    private fun cornersOf(s: FloatArray, minTurnDeg: Float): FloatArray {
        var m = s.size / 2
        // il punto finale coincide (quasi) con l'iniziale: lo scartiamo
        if (m > 2 && hypot(s[2 * m - 2] - s[0], s[2 * m - 1] - s[1]) < 1e-3f + 0.15f * perimeter(s)) m--
        val out = ArrayList<Float>()
        for (i in 0 until m) {
            val a = (i - 1 + m) % m
            val b = (i + 1) % m
            val d1 = atan2(s[2 * i + 1] - s[2 * a + 1], s[2 * i] - s[2 * a])
            val d2 = atan2(s[2 * b + 1] - s[2 * i + 1], s[2 * b] - s[2 * i])
            var turn = abs(d2 - d1)
            if (turn > PI) turn = (2 * PI - turn).toFloat()
            if (Math.toDegrees(turn.toDouble()) > minTurnDeg) {
                out.add(s[2 * i]); out.add(s[2 * i + 1])
            }
        }
        return out.toFloatArray()
    }

    private fun perimeter(s: FloatArray): Float {
        var l = 0f
        for (i in 1 until s.size / 2) l += hypot(s[2 * i] - s[2 * i - 2], s[2 * i + 1] - s[2 * i - 1])
        return l
    }

    // ---------------- ellisse ----------------

    /** Ellisse ai minimi quadrati "alla buona": centro = baricentro, assi = PCA, raggi = RMS (robusti al tremolio). */
    private fun fitEllipse(p: FloatArray): Shape.Ellipse? {
        val n = p.size / 2
        var cx = 0f; var cy = 0f
        for (i in 0 until n) { cx += p[2 * i]; cy += p[2 * i + 1] }
        cx /= n; cy /= n
        var sxx = 0f; var syy = 0f; var sxy = 0f
        for (i in 0 until n) {
            val dx = p[2 * i] - cx; val dy = p[2 * i + 1] - cy
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val angle = 0.5f * atan2(2 * sxy, sxx - syy)
        val ca = cos(angle); val sa = sin(angle)
        var su = 0f; var sv = 0f
        for (i in 0 until n) {
            val dx = p[2 * i] - cx; val dy = p[2 * i + 1] - cy
            val u = dx * ca + dy * sa; val v = -dx * sa + dy * ca
            su += u * u; sv += v * v
        }
        // per punti distribuiti sull'ellisse: media(u²) = rx²/2
        val rx = sqrt(2f * su / n)
        val ry = sqrt(2f * sv / n)
        if (rx < 3f || ry < 3f) return null
        if (min(rx, ry) / max(rx, ry) > 0.88f) {
            val r = (rx + ry) / 2f
            return Shape.Ellipse(cx, cy, r, r, 0f)
        }
        val deg = Math.toDegrees(angle.toDouble()).toFloat()
        val snapped = when {
            abs(deg) < 8f -> 0f
            abs(abs(deg) - 90f) < 8f -> (PI / 2).toFloat()
            else -> angle
        }
        return Shape.Ellipse(cx, cy, rx, ry, snapped)
    }

    // ---------------- utilità ----------------

    private fun distToLine(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax; val dy = by - ay
        val len = hypot(dx, dy)
        if (len < 1e-4f) return hypot(px - ax, py - ay)
        return abs(dy * px - dx * py + bx * ay - by * ax) / len
    }

    /** Douglas–Peucker sulla polilinea. */
    private fun rdp(p: FloatArray, eps: Float): FloatArray {
        val n = p.size / 2
        val keep = BooleanArray(n)
        keep[0] = true; keep[n - 1] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, n - 1))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            var maxD = 0f; var idx = -1
            for (i in a + 1 until b) {
                val d = distToLine(p[2 * i], p[2 * i + 1], p[2 * a], p[2 * a + 1], p[2 * b], p[2 * b + 1])
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > eps) {
                keep[idx] = true
                stack.addLast(intArrayOf(a, idx)); stack.addLast(intArrayOf(idx, b))
            }
        }
        val out = ArrayList<Float>()
        for (i in 0 until n) if (keep[i]) { out.add(p[2 * i]); out.add(p[2 * i + 1]) }
        return out.toFloatArray()
    }

    // ---------------- forma → tratto ----------------

    /** Punti campionati lungo la forma ogni [step] pt (chiusa se poligono/ellisse). */
    fun sample(shape: Shape, step: Float = 1f): FloatArray {
        val out = ArrayList<Float>()
        fun seg(x0: Float, y0: Float, x1: Float, y1: Float, includeEnd: Boolean) {
            val len = hypot(x1 - x0, y1 - y0)
            val k = max(1, (len / step).toInt())
            for (i in 0 until k) { val t = i / k.toFloat(); out.add(x0 + (x1 - x0) * t); out.add(y0 + (y1 - y0) * t) }
            if (includeEnd) { out.add(x1); out.add(y1) }
        }
        when (shape) {
            is Shape.Line -> seg(shape.x0, shape.y0, shape.x1, shape.y1, true)
            is Shape.Polygon -> {
                val m = shape.corners
                for (i in 0 until m) {
                    val j = (i + 1) % m
                    seg(shape.pts[2 * i], shape.pts[2 * i + 1], shape.pts[2 * j], shape.pts[2 * j + 1], i == m - 1)
                }
            }
            is Shape.Ellipse -> {
                val per = (2 * PI * sqrt((shape.rx * shape.rx + shape.ry * shape.ry) / 2.0)).toFloat()
                val k = max(36, (per / step).toInt())
                val ca = cos(shape.angle); val sa = sin(shape.angle)
                for (i in 0..k) {
                    val a = (2 * PI * i / k).toFloat()
                    val ux = shape.rx * cos(a); val uy = shape.ry * sin(a)
                    out.add(shape.cx + ux * ca - uy * sa); out.add(shape.cy + ux * sa + uy * ca)
                }
            }
        }
        return out.toFloatArray()
    }

    /** Tratto Ink della forma, con pressione costante (spessore uniforme e pulito). */
    fun toStroke(shape: Shape, brush: Brush): Stroke {
        val pts = sample(shape, step = (brush.size * 0.5f).coerceIn(0.4f, 2f))
        val batch = MutableStrokeInputBatch()
        for (i in 0 until pts.size / 2) {
            batch.add(InputToolType.STYLUS, pts[2 * i], pts[2 * i + 1], i * 2L, StrokeInputNoUnit, 0.6f, 0f, 0f)
        }
        return Stroke(brush, batch)
    }

    private val StrokeInputNoUnit = androidx.ink.strokes.StrokeInput.NO_STROKE_UNIT_LENGTH
}
