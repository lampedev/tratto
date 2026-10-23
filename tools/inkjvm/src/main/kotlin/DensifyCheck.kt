import androidx.ink.brush.InputToolType
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.caro.tratto.editor.densifyInputs
import com.caro.tratto.ink.Brushes
import com.caro.tratto.model.ToolKind

/** Lato più lungo del contorno (pt): misura quanto si vedono i "segmenti" ingrandendo. */
private fun maxOutlineEdge(st: Stroke): Float {
    var m = 0f
    val a = MutableVec(); val b = MutableVec()
    val sh = st.shape
    for (g in 0 until sh.getRenderGroupCount()) for (o in 0 until sh.getOutlineCount(g)) {
        val n = sh.getOutlineVertexCount(g, o)
        for (i in 0 until n) {
            sh.populateOutlinePosition(g, o, i, a); sh.populateOutlinePosition(g, o, (i + 1) % n, b)
            m = maxOf(m, kotlin.math.hypot(b.x - a.x, b.y - a.y))
        }
    }
    return m
}

fun densifyCheck(): Int {
    var fail = 0
    println("infittimento (mezzo cerchio r=10 pt da 13 input, stilografica livello 30):")
    val raw = MutableStrokeInputBatch()
    for (i in 0..12) {
        val a = i / 12.0 * Math.PI
        raw.add(InputToolType.STYLUS, (100 + 10 * Math.cos(a)).toFloat(), (100 + 10 * Math.sin(a)).toFloat(), i * 4L, 0.0353f, 0.5f + i * 0.02f, 0.3f, 0f)
    }
    val brush = Brushes.create(ToolKind.PEN, 0xFF000000.toInt(), Brushes.sizeForLevel(ToolKind.PEN, 30))
    val dense = densifyInputs(raw)
    val s1 = Stroke(brush, raw)
    val s2 = Stroke(brush, dense)
    println("  input ${raw.size} → ${dense.size}; lato massimo contorno %.2f pt → %.2f pt (al 600%% ≈ %.0f px → %.0f px)".format(
        maxOutlineEdge(s1), maxOutlineEdge(s2), maxOutlineEdge(s1) * 14, maxOutlineEdge(s2) * 14))
    // scarto massimo di una corda dal cerchio r=10 pt, in pixel al 600% (14 px/pt): < 0.5 px = invisibile
    val sag = maxOutlineEdge(s2) * maxOutlineEdge(s2) / (8 * 10f) * 14f
    if (sag > 0.5f) { println("  KO scarto %.2f px".format(sag)); fail++ } else println("  ok  curva liscia (scarto %.2f px al 600%%)".format(sag))
    // il raggio resta ~10: nessuna sbordatura
    var maxR = 0f; var minR = 99f
    for (i in 0 until dense.size) { val p = dense.get(i); val r = kotlin.math.hypot(p.x - 100f, p.y - 100f); maxR = maxOf(maxR, r); minR = minOf(minR, r) }
    if (maxR < 10.05f && minR > 9.4f) println("  ok  resta sul cerchio (r %.2f–%.2f)".format(minR, maxR)) else { println("  KO raggio %.2f–%.2f".format(minR, maxR)); fail++ }
    val again = densifyInputs(dense)
    if (again === dense) println("  ok  idempotente") else { println("  KO non idempotente"); fail++ }
    return fail
}
