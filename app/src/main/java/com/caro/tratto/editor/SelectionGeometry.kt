package com.caro.tratto.editor

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import androidx.ink.strokes.createClosedShape
import com.caro.tratto.model.NoteStroke
import com.caro.tratto.model.ToolKind

/*
 * Geometria della selezione, senza dipendenze Android (testata anche su JVM).
 * Tutte le coordinate sono punti PDF locali alla pagina.
 */

/** Trasformazione della selezione: scala k attorno ad (ax, ay), poi traslazione (dx, dy). */
internal class SelectionTransform {
    var k = 1f
    var ax = 0f
    var ay = 0f
    var dx = 0f
    var dy = 0f

    val isIdentity get() = k == 1f && dx == 0f && dy == 0f

    fun mapX(x: Float) = ax + (x - ax) * k + dx
    fun mapY(y: Float) = ay + (y - ay) * k + dy

    fun reset() {
        k = 1f; ax = 0f; ay = 0f; dx = 0f; dy = 0f
    }
}

/** Ricostruisce il tratto con input trasformati; lo spessore segue la scala. */
internal fun transformStroke(ns: NoteStroke, t: SelectionTransform): NoteStroke {
    val inputs = ns.stroke.inputs
    val out = MutableStrokeInputBatch()
    out.setNoiseSeed(inputs.getNoiseSeed())
    val si = StrokeInput()
    for (i in 0 until inputs.size) {
        inputs.populate(i, si)
        out.add(
            si.toolType, t.mapX(si.x), t.mapY(si.y), si.elapsedTimeMillis,
            si.strokeUnitLengthCm, si.pressure, si.tiltRadians, si.orientationRadians,
        )
    }
    val brush = if (t.k != 1f) ns.stroke.brush.copy(size = (ns.stroke.brush.size * t.k).coerceAtLeast(0.05f)) else ns.stroke.brush
    return NoteStroke(ns.tool, Stroke(brush, out), ns.variant)
}

/** Cambia colore; l'evidenziatore conserva la sua trasparenza. */
internal fun recolorStroke(ns: NoteStroke, color: Int): NoteStroke {
    val c = if (ns.tool == ToolKind.HIGHLIGHTER) {
        (color and 0x00FFFFFF) or (ns.stroke.brush.colorIntArgb and 0xFF000000.toInt())
    } else {
        color or 0xFF000000.toInt()
    }
    return NoteStroke(ns.tool, ns.stroke.copy(ns.stroke.brush.copyWithColorIntArgb(c)), ns.variant)
}

/**
 * Tratti racchiusi dal lasso [points] (x0, y0, x1, y1, ...): selezionato se almeno
 * [threshold] della sua area cade dentro il contorno chiuso.
 */
internal fun selectInLasso(strokes: List<NoteStroke>, points: FloatArray, threshold: Float = 0.5f): List<NoteStroke> {
    if (points.size < 6) return emptyList()
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    val batch = MutableStrokeInputBatch()
    for (i in 0 until points.size / 2) {
        val x = points[2 * i]
        val y = points[2 * i + 1]
        batch.add(InputToolType.STYLUS, x, y, i.toLong())
        minX = minOf(minX, x); maxX = maxOf(maxX, x)
        minY = minOf(minY, y); maxY = maxOf(maxY, y)
    }
    val lasso = runCatching { batch.createClosedShape() }.getOrNull() ?: return emptyList()
    return strokes.filter { ns ->
        val b = ns.stroke.shape.computeBoundingBox() ?: return@filter false
        if (b.xMax < minX || b.xMin > maxX || b.yMax < minY || b.yMin > maxY) return@filter false
        ns.stroke.shape.computeCoverageIsGreaterThan(lasso, threshold)
    }
}

/**
 * Gomma parziale: toglie dal tratto gli input entro [r] (+ mezzo spessore) dal segmento
 * della gomma e restituisce i pezzi rimasti. null = tratto non toccato; lista vuota = sparito.
 * Lavora sugli input: il risultato si salva e si ricarica identico.
 */
internal fun splitByEraser(ns: NoteStroke, ax: Float, ay: Float, bx: Float, by: Float, r: Float): List<NoteStroke>? {
    val inputs = ns.stroke.inputs
    val n = inputs.size
    val hit = r + ns.stroke.brush.size * 0.5f
    val si = StrokeInput()
    val keep = BooleanArray(n)
    var removed = false
    for (i in 0 until n) {
        inputs.populate(i, si)
        keep[i] = distToSegment(si.x, si.y, ax, ay, bx, by) > hit
        if (!keep[i]) removed = true
    }
    if (!removed) return null
    val pieces = ArrayList<NoteStroke>()
    var i = 0
    while (i < n) {
        if (!keep[i]) { i++; continue }
        var j = i
        while (j < n && keep[j]) j++
        if (j - i >= 2) {
            val out = MutableStrokeInputBatch()
            out.setNoiseSeed(inputs.getNoiseSeed())
            inputs.populate(i, si)
            val t0 = si.elapsedTimeMillis
            for (k in i until j) {
                inputs.populate(k, si)
                out.add(
                    si.toolType, si.x, si.y, si.elapsedTimeMillis - t0,
                    si.strokeUnitLengthCm, si.pressure, si.tiltRadians, si.orientationRadians,
                )
            }
            pieces.add(NoteStroke(ns.tool, Stroke(ns.stroke.brush, out), ns.variant))
        }
        i = j
    }
    return pieces
}

private fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val len2 = dx * dx + dy * dy
    val t = if (len2 < 1e-6f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
    val cx = ax + t * dx
    val cy = ay + t * dy
    return kotlin.math.hypot(px - cx, py - cy)
}

/**
 * Infittisce gli input con una spline Catmull-Rom centripeta finché ogni segmento è ≤ [maxSegPt].
 * Ink unisce gli input con segmenti dritti: al 600% le curve scritte al 100% sembrerebbero
 * poligoni. Pressione, tilt, orientamento e tempo sono interpolati linearmente.
 * Se gli input sono già abbastanza fitti restituisce lo stesso batch.
 */
internal fun densifyInputs(inputs: StrokeInputBatch, maxSegPt: Float = 0.25f): StrokeInputBatch {
    val n = inputs.size
    if (n < 2) return inputs
    val xs = FloatArray(n)
    val ys = FloatArray(n)
    val ts = LongArray(n)
    val si = StrokeInput()
    for (i in 0 until n) {
        inputs.populate(i, si)
        xs[i] = si.x
        ys[i] = si.y
        ts[i] = si.elapsedTimeMillis
    }
    // Il tempo degli input Ink è in ms interi: al massimo un punto nuovo per millisecondo,
    // altrimenti punti con lo stesso istante verrebbero fusi dal modello di input.
    fun pieces(i: Int): Int {
        val d = kotlin.math.hypot(xs[i + 1] - xs[i], ys[i + 1] - ys[i])
        val dt = (ts[i + 1] - ts[i]).toInt().coerceAtLeast(1)
        return minOf(kotlin.math.ceil(d / maxSegPt).toInt(), dt)
    }
    if ((0 until n - 1).none { pieces(it) > 1 }) return inputs
    val out = MutableStrokeInputBatch()
    out.setNoiseSeed(inputs.getNoiseSeed())
    val a = StrokeInput()
    val b = StrokeInput()
    for (i in 0 until n - 1) {
        inputs.populate(i, a)
        inputs.populate(i + 1, b)
        out.add(a)
        val k = pieces(i)
        if (k <= 1) continue
        // punti di controllo: i-1, i, i+1, i+2 (agli estremi si duplica il punto)
        val i0 = maxOf(i - 1, 0)
        val i3 = minOf(i + 2, n - 1)
        for (j in 1 until k) {
            val t = j / k.toFloat()
            val (x, y) = centripetal(xs[i0], ys[i0], xs[i], ys[i], xs[i + 1], ys[i + 1], xs[i3], ys[i3], t)
            out.add(
                a.toolType, x, y,
                a.elapsedTimeMillis + ((b.elapsedTimeMillis - a.elapsedTimeMillis) * t).toLong(),
                a.strokeUnitLengthCm,
                lerpOpt(a.pressure, b.pressure, t),
                lerpOpt(a.tiltRadians, b.tiltRadians, t),
                lerpOpt(a.orientationRadians, b.orientationRadians, t),
            )
        }
    }
    inputs.populate(n - 1, a)
    out.add(a)
    return out
}

/** Interpola un valore opzionale (i valori "assenti" di Ink sono negativi: restano assenti). */
private fun lerpOpt(a: Float, b: Float, t: Float): Float = if (a < 0f || b < 0f) a else a + (b - a) * t

/** Catmull-Rom centripeta (alpha 0.5) fra p1 e p2: niente anelli né sbordature negli angoli. */
private fun centripetal(
    x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, t: Float,
): Pair<Float, Float> {
    fun knot(ti: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val d = kotlin.math.sqrt(kotlin.math.hypot(bx - ax, by - ay))
        return ti + maxOf(d, 1e-4f)
    }
    val t0 = 0f
    val t1 = knot(t0, x0, y0, x1, y1)
    val t2 = knot(t1, x1, y1, x2, y2)
    val t3 = knot(t2, x2, y2, x3, y3)
    val u = t1 + (t2 - t1) * t
    fun lerp(pa: Float, pb: Float, ta: Float, tb: Float) = if (tb - ta < 1e-6f) pa else ((tb - u) * pa + (u - ta) * pb) / (tb - ta)
    val a1x = lerp(x0, x1, t0, t1); val a1y = lerp(y0, y1, t0, t1)
    val a2x = lerp(x1, x2, t1, t2); val a2y = lerp(y1, y2, t1, t2)
    val a3x = lerp(x2, x3, t2, t3); val a3y = lerp(y2, y3, t2, t3)
    val b1x = lerp(a1x, a2x, t0, t2); val b1y = lerp(a1y, a2y, t0, t2)
    val b2x = lerp(a2x, a3x, t1, t3); val b2y = lerp(a2y, a3y, t1, t3)
    return lerp(b1x, b2x, t1, t2) to lerp(b1y, b2y, t1, t2)
}
