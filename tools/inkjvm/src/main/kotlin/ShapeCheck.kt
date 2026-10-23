import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.caro.tratto.editor.Shape
import com.caro.tratto.editor.ShapeRecognizer
import com.caro.tratto.editor.splitByEraser
import com.caro.tratto.ink.Brushes
import com.caro.tratto.model.NoteStroke
import com.caro.tratto.model.ToolKind
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private val rnd = Random(7)
private fun jitter(v: Float, a: Float) = v + (rnd.nextFloat() - 0.5f) * 2 * a

/** Percorso "a mano" attraverso i vertici, con tremolio. */
private fun handPath(vararg v: Float, closed: Boolean, noise: Float = 1.2f, step: Float = 2f): FloatArray {
    val out = ArrayList<Float>()
    val m = v.size / 2
    val segs = if (closed) m else m - 1
    for (s in 0 until segs) {
        val a = s; val b = (s + 1) % m
        val len = kotlin.math.hypot(v[2 * b] - v[2 * a], v[2 * b + 1] - v[2 * a + 1])
        val k = (len / step).toInt().coerceAtLeast(2)
        for (i in 0 until k) {
            val t = i / k.toFloat()
            out.add(jitter(v[2 * a] + (v[2 * b] - v[2 * a]) * t, noise))
            out.add(jitter(v[2 * a + 1] + (v[2 * b + 1] - v[2 * a + 1]) * t, noise))
        }
    }
    if (closed) { out.add(jitter(v[0], noise) + 3f); out.add(jitter(v[1], noise) + 2f) } else { out.add(v[2 * m - 2]); out.add(v[2 * m - 1]) }
    return out.toFloatArray()
}

private fun handEllipse(cx: Float, cy: Float, rx: Float, ry: Float): FloatArray {
    val out = ArrayList<Float>()
    for (i in 0..95) {
        val a = 2 * PI * i / 90   // un po' oltre il giro, come a mano
        out.add(jitter(cx + rx * cos(a).toFloat(), 1.5f)); out.add(jitter(cy + ry * sin(a).toFloat(), 1.5f))
    }
    return out.toFloatArray()
}

fun shapeCheck(): Int {
    var fail = 0
    fun expect(cond: Boolean, what: String) { if (cond) println("  ok  $what") else { println("  KO  $what"); fail++ } }
    val line = ShapeRecognizer.recognize(handPath(10f, 10f, 200f, 14f, closed = false))
    expect(line is Shape.Line && line.y0 == line.y1, "linea quasi orizzontale → dritta")
    val diag = ShapeRecognizer.recognize(handPath(10f, 10f, 150f, 120f, closed = false))
    expect(diag is Shape.Line, "linea obliqua")
    val tri = ShapeRecognizer.recognize(handPath(50f, 10f, 120f, 130f, 0f, 125f, closed = true))
    expect(tri is Shape.Polygon && tri.corners == 3, "triangolo (${(tri as? Shape.Polygon)?.corners})")
    val rect = ShapeRecognizer.recognize(handPath(20f, 20f, 220f, 22f, 218f, 140f, 22f, 138f, closed = true))
    expect(rect is Shape.Polygon && rect.corners == 4 && rect.pts[1] == rect.pts[3], "rettangolo raddrizzato")
    val circ = ShapeRecognizer.recognize(handEllipse(100f, 100f, 60f, 58f))
    expect(circ is Shape.Ellipse && circ.rx == circ.ry, "cerchio")
    val ell = ShapeRecognizer.recognize(handEllipse(100f, 100f, 90f, 40f))
    expect(ell is Shape.Ellipse && ell.rx != ell.ry, "ellisse")
    // cerchio pulito (senza tremolio): prima veniva preso per un rettangolo
    val clean = ArrayList<Float>(); for (i in 0..200) { val a = 2 * PI * i / 192; clean.add((100 + 60 * cos(a)).toFloat()); clean.add((100 + 60 * sin(a)).toFloat()) }
    val cleanShape = ShapeRecognizer.recognize(clean.toFloatArray())
    expect(cleanShape is Shape.Ellipse, "cerchio pulito → cerchio (era ${cleanShape?.javaClass?.simpleName})")
    val square = ShapeRecognizer.recognize(handPath(20f, 20f, 140f, 20f, 140f, 140f, 20f, 140f, closed = true, noise = 0.3f))
    expect(square is Shape.Polygon && square.corners == 4, "quadrato pulito → quadrilatero")
    val l45 = ShapeRecognizer.snapLine(0f, 0f, 100f, 96f, snap45 = true)
    expect(kotlin.math.abs((l45.x1 - l45.x0) - (l45.y1 - l45.y0)) < 0.01f, "linea a ~44° → 45° esatti")
    // --- forme "a mano" realistiche: campioni a velocità variabile, ondulazione, chiusura che sborda ---
    fun handClosed(f: (Double) -> Pair<Double, Double>, turns: Double = 1.06): FloatArray {
        val out = ArrayList<Float>(); val n = 260
        for (i in 0..n) {
            val t0 = i / n.toDouble(); val t = (t0 + 0.08 * Math.sin(2 * PI * t0)) * turns  // velocità non uniforme
            val (x, y) = f(t)
            out.add((x + 0.3 * Math.sin(37.0 * t)).toFloat()); out.add((y + 0.3 * Math.cos(29.0 * t)).toFloat())
        }
        return out.toFloatArray()
    }
    fun roundedPoly(vs: List<Pair<Double, Double>>, r: Double): (Double) -> Pair<Double, Double> {
        // perimetro di un poligono con angoli smussati (approssimato campionando i lati)
        val pts = ArrayList<Pair<Double, Double>>()
        for (k in vs.indices) {
            val a = vs[k]; val b = vs[(k + 1) % vs.size]
            for (j in 0 until 40) { val t = j / 40.0; pts.add(a.first + (b.first - a.first) * t to a.second + (b.second - a.second) * t) }
        }
        // smussatura: media mobile circolare
        val w = (r / 3).toInt().coerceAtLeast(1)
        val sm = pts.indices.map { i -> val win = (-w..w).map { pts[(i + it + pts.size) % pts.size] }; win.sumOf { it.first } / win.size to win.sumOf { it.second } / win.size }
        return { t -> sm[(((t % 1.0) + 1.0) % 1.0 * sm.size).toInt().coerceIn(0, sm.size - 1)] }
    }
    fun dbg(name: String, pts: FloatArray) = println("     [$name] " + ShapeRecognizer.debug(pts))
    dbg("cerchio", handClosed({ t -> val a = 2 * PI * t; val r = 60 * (1 + 0.05 * Math.sin(3 * a)) * (0.97 + 0.06 * t); 200 + r * Math.cos(a) to 200 + r * Math.sin(a) }))
    dbg("rett", handClosed(roundedPoly(listOf(0.0 to 0.0, 160.0 to 0.0, 160.0 to 100.0, 0.0 to 100.0), 12.0)))
    val wobblyCircle = ShapeRecognizer.recognize(handClosed({ t -> val a = 2 * PI * t; val r = 60 * (1 + 0.05 * Math.sin(3 * a)) * (0.97 + 0.06 * t); 200 + r * Math.cos(a) to 200 + r * Math.sin(a) }))
    expect(wobblyCircle is Shape.Ellipse && wobblyCircle.rx == wobblyCircle.ry, "cerchio a mano ondulato/spirale → cerchio (${wobblyCircle?.javaClass?.simpleName})")
    val tilted = ShapeRecognizer.recognize(handClosed({ t -> val a = 2 * PI * t; val u = 90 * Math.cos(a) * (1 + 0.04 * Math.sin(2 * a)); val v = 45 * Math.sin(a); 200 + u * 0.866 - v * 0.5 to 200 + u * 0.5 + v * 0.866 }))
    expect(tilted is Shape.Ellipse && tilted.rx != tilted.ry, "ellisse inclinata a mano → ellisse (${tilted?.javaClass?.simpleName})")
    val rrect = ShapeRecognizer.recognize(handClosed(roundedPoly(listOf(0.0 to 0.0, 160.0 to 0.0, 160.0 to 100.0, 0.0 to 100.0), 12.0)))
    expect(rrect is Shape.Polygon && rrect.corners == 4, "rettangolo a mano con angoli tondi → rettangolo (${rrect?.javaClass?.simpleName})")
    val rsq = ShapeRecognizer.recognize(handClosed(roundedPoly(listOf(0.0 to 0.0, 100.0 to 0.0, 100.0 to 100.0, 0.0 to 100.0), 10.0)))
    expect(rsq is Shape.Polygon && rsq.corners == 4, "quadrato a mano con angoli tondi → quadrato (${rsq?.javaClass?.simpleName})")
    val rtri = ShapeRecognizer.recognize(handClosed(roundedPoly(listOf(60.0 to 0.0, 130.0 to 110.0, 0.0 to 110.0), 10.0)))
    expect(rtri is Shape.Polygon && rtri.corners == 3, "triangolo a mano → triangolo (${rtri?.javaClass?.simpleName})")
    val scribble = ShapeRecognizer.recognize(floatArrayOf(0f, 0f, 30f, 40f, 60f, 0f, 90f, 40f, 120f, 0f, 150f, 40f, 180f, 0f))
    expect(scribble == null, "zig-zag aperto non riconosciuto")
    val s = Brushes.defaultSettings(ToolKind.PEN)
    val st = ShapeRecognizer.toStroke(circ!!, Brushes.create(ToolKind.PEN, s.color, s.sizePt))
    expect(st.shape.getRenderGroupCount() == 1, "tratto della forma costruito (${st.inputs.size} input)")

    // gomma parziale: linea 0..200 tagliata al centro
    val b = MutableStrokeInputBatch()
    for (i in 0..100) b.add(InputToolType.STYLUS, i * 2f, 50f, i * 4L, 0.0353f, 0.5f, 0f, 0f)
    val ns = NoteStroke(ToolKind.PEN, Stroke(Brushes.create(ToolKind.PEN, s.color, s.sizePt), b))
    val pieces = splitByEraser(ns, 100f, 30f, 100f, 70f, 8f)
    expect(pieces != null && pieces.size == 2, "taglio al centro → 2 pezzi (${pieces?.size})")
    val untouched = splitByEraser(ns, 100f, 200f, 120f, 200f, 8f)
    expect(untouched == null, "gomma lontana → nessun cambio")
    val all = splitByEraser(ns, -10f, 50f, 210f, 50f, 8f)
    expect(all != null && all.isEmpty(), "gomma su tutto → tratto eliminato")
    return fail
}
