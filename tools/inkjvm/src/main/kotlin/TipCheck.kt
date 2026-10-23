import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.caro.tratto.ink.Brushes
import com.caro.tratto.model.ToolKind

/** Vertici del contorno di un "punto" (tratto cortissimo): misura quanto è tonda la punta. */
fun tipCheck() {
    println("rotondità della punta (vertici del contorno di un punto):")
    for (kind in listOf(ToolKind.PEN, ToolKind.BALLPOINT)) {
        val row = StringBuilder("  %-10s".format(kind.name))
        for (level in listOf(1, 5, 20, 30, 45, 70, 100)) {
            val b = MutableStrokeInputBatch()
            b.add(InputToolType.STYLUS, 100f, 100f, 0L, 0.0353f, 0.6f, 0f, 0f)
            b.add(InputToolType.STYLUS, 100.01f, 100f, 8L, 0.0353f, 0.6f, 0f, 0f)
            val st = Stroke(Brushes.create(kind, 0xFF000000.toInt(), Brushes.sizeForLevel(kind, level)), b)
            val v = (0 until st.shape.getRenderGroupCount()).sumOf { g -> (0 until st.shape.getOutlineCount(g)).sumOf { o -> st.shape.getOutlineVertexCount(g, o) } }
            row.append(" L$level:$v")
        }
        println(row)
    }
    println("confronto epsilon (stilografica livello 30, tratto curvo lungo 20 pt):")
    val base = Brushes.create(ToolKind.PEN, 0xFF000000.toInt(), Brushes.sizeForLevel(ToolKind.PEN, 30))
    for (eps in listOf(0.05f, 0.02f, 0.0079f, 0.004f, 0.002f)) {
        val b = MutableStrokeInputBatch()
        for (i in 0..60) {
            val a = i / 60.0 * Math.PI
            b.add(InputToolType.STYLUS, (100 + 10 * Math.cos(a)).toFloat(), (100 + 10 * Math.sin(a)).toFloat(), i * 6L, 0.0353f, 0.6f, 0f, 0f)
        }
        val st = Stroke(base.copy(epsilon = eps), b)
        val v = (0 until st.shape.getRenderGroupCount()).sumOf { g -> (0 until st.shape.getOutlineCount(g)).sumOf { o -> st.shape.getOutlineVertexCount(g, o) } }
        println("  eps=%.4f pt → vertici contorno=$v".format(eps))
    }
}
