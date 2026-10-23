import androidx.ink.geometry.MutableVec
import androidx.ink.strokes.Stroke
import com.caro.tratto.editor.Shape
import com.caro.tratto.editor.ShapeRecognizer
import com.caro.tratto.ink.Brushes
import com.caro.tratto.model.ToolKind

/** Spessore (estensione verticale del contorno) di un tratto orizzontale, per fasce di x. */
fun widthProfile(stroke: Stroke, x0: Float, x1: Float, bins: Int): List<Float> {
    val minY = FloatArray(bins) { Float.MAX_VALUE }
    val maxY = FloatArray(bins) { -Float.MAX_VALUE }
    val v = MutableVec()
    val shape = stroke.shape
    for (g in 0 until shape.getRenderGroupCount()) for (o in 0 until shape.getOutlineCount(g)) {
        for (i in 0 until shape.getOutlineVertexCount(g, o)) {
            shape.populateOutlinePosition(g, o, i, v)
            val b = (((v.x - x0) / (x1 - x0)) * bins).toInt()
            if (b in 0 until bins) { minY[b] = minOf(minY[b], v.y); maxY[b] = maxOf(maxY[b], v.y) }
        }
    }
    return (0 until bins).map { if (maxY[it] >= minY[it]) maxY[it] - minY[it] else -1f }
}

fun lineEndsCheck() {
    val s = Brushes.defaultSettings(ToolKind.PEN)
    val brush = Brushes.create(ToolKind.PEN, s.color, s.sizePt)
    val st = ShapeRecognizer.toStroke(Shape.Line(0f, 100f, 400f, 100f), brush)
    println("linea forma (size ${"%.2f".format(s.sizePt)} pt), spessore per fasce di 20 pt:")
    println("  " + widthProfile(st, 0f, 400f, 20).joinToString(" ") { "%.2f".format(it) })
}
