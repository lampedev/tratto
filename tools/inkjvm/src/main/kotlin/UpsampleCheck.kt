import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.caro.tratto.editor.densifyInputs

private fun maxEdge(st: Stroke): Float {
    var m = 0f
    val a = MutableVec(); val b = MutableVec()
    val sh = st.shape
    for (g in 0 until sh.getRenderGroupCount()) for (o in 0 until sh.getOutlineCount(g)) {
        val n = sh.getOutlineVertexCount(g, o)
        for (i in 0 until n - 1) {
            sh.populateOutlinePosition(g, o, i, a); sh.populateOutlinePosition(g, o, i + 1, b)
            m = maxOf(m, kotlin.math.hypot(b.x - a.x, b.y - a.y))
        }
    }
    return m
}

/** Scrittura realistica: 240 Hz (S Pen), 300 pt/s, anelli stretti r=2 pt (come una "l" corsiva). */
fun upsampleCheck() {
    println("ricampionamento del modello di input (anelli r=2 pt, 240 Hz, 300 pt/s, spessore 0.66 pt):")
    val raw = MutableStrokeInputBatch()
    val dt = 1000.0 / 240
    var t = 0.0; var ang = 0.0; var cx = 50.0
    for (i in 0 until 600) {
        raw.add(InputToolType.STYLUS, (cx + 2 * Math.cos(ang)).toFloat(), (100 + 2 * Math.sin(ang)).toFloat(), t.toLong(), 0.0353f, 0.6f, 0.3f, 0f)
        val step = 300.0 * dt / 1000.0   // pt per campione
        ang += step / 2.0; cx += 0.15; t += dt
    }
    for (hz in listOf(180, 480, 1000)) {
        val fam = BrushFamily(tip = BrushTip(), inputModel = BrushFamily.InputModel.SlidingWindowModel(20L, hz))
        val brush = Brush.createWithColorIntArgb(fam, 0xFF000000.toInt(), 0.66f, 0.008f)
        val t0 = System.nanoTime(); val s = Stroke(brush, raw); val ms = (System.nanoTime() - t0) / 1e6
        val v = (0 until s.shape.getRenderGroupCount()).sumOf { g -> (0 until s.shape.getOutlineCount(g)).sumOf { o -> s.shape.getOutlineVertexCount(g, o) } }
        val sd = Stroke(brush, densifyInputs(raw))
        println("  %4d Hz: lato max %.2f pt (%.0f px al 600%%), vertici %d, %.1f ms | con spline: lato max %.2f pt".format(hz, maxEdge(s), maxEdge(s) * 14, v, ms, maxEdge(sd)))
    }
}
