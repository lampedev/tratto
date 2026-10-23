import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.caro.tratto.editor.SelectionTransform
import com.caro.tratto.editor.recolorStroke
import com.caro.tratto.editor.selectInLasso
import com.caro.tratto.editor.transformStroke
import com.caro.tratto.ink.Brushes
import com.caro.tratto.model.NoteStroke
import com.caro.tratto.model.ToolKind

private fun seg(x0: Float, y0: Float, x1: Float, y1: Float, tool: ToolKind = ToolKind.PEN): NoteStroke {
    val b = MutableStrokeInputBatch()
    for (i in 0..40) {
        val t = i / 40f
        b.add(InputToolType.STYLUS, x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, (t * 300).toLong(), 0.0353f, 0.5f, 0.5f, 0f)
    }
    val s = Brushes.defaultSettings(tool)
    return NoteStroke(tool, Stroke(Brushes.create(tool, s.color, s.sizePt), b))
}

/** Ritorna il numero di controlli falliti. */
fun selectionCheck(): Int {
    var fail = 0
    fun expect(cond: Boolean, what: String) { if (cond) println("  ok  $what") else { println("  KO  $what"); fail++ } }
    val inside = seg(20f, 20f, 60f, 40f)
    val outside = seg(200f, 200f, 260f, 220f)
    val half = seg(80f, 50f, 160f, 50f)          // metà dentro (x 80..100), metà fuori
    val square = floatArrayOf(10f, 10f, 100f, 10f, 100f, 100f, 10f, 100f)
    val sel = selectInLasso(listOf(inside, outside, half), square)
    expect(inside in sel, "tratto interno selezionato")
    expect(outside !in sel, "tratto esterno escluso")
    expect(half !in sel, "tratto per lo più fuori escluso (soglia 50%)")

    val t = SelectionTransform().apply { k = 2f; ax = 20f; ay = 20f; dx = 10f; dy = 5f }
    val moved = transformStroke(inside, t)
    val b0 = inside.stroke.shape.computeBoundingBox()!!
    val b1 = moved.stroke.shape.computeBoundingBox()!!
    expect(kotlin.math.abs((b1.xMax - b1.xMin) - 2 * (b0.xMax - b0.xMin)) < 0.5f, "scala 2x della larghezza (%.1f→%.1f)".format(b0.xMax - b0.xMin, b1.xMax - b1.xMin))
    expect(kotlin.math.abs(moved.stroke.brush.size - 2 * inside.stroke.brush.size) < 1e-3f, "spessore raddoppiato")
    expect(moved.stroke.inputs.size == inside.stroke.inputs.size, "stessi input")

    val hl = seg(0f, 0f, 50f, 0f, ToolKind.HIGHLIGHTER)
    val hl2 = recolorStroke(hl, 0xFFFF0000.toInt())
    expect(hl2.stroke.brush.colorIntArgb ushr 24 == hl.stroke.brush.colorIntArgb ushr 24, "evidenziatore ricolorato mantiene l'alfa")
    expect(hl2.stroke.brush.colorIntArgb and 0xFFFFFF == 0xFF0000, "nuovo colore applicato")
    return fail
}
