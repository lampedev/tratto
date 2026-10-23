import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import com.caro.tratto.editor.densifyInputs
import com.caro.tratto.ink.Brushes
import com.caro.tratto.ink.PenFeel
import com.caro.tratto.model.ToolKind
import kotlin.random.Random

/**
 * Salva e ricarica come fa l'app (input infittiti → encode → decode → infittiti → Stroke) su tratti
 * "da S Pen" casuali: scrittura normale, al 1000%, penna ferma, campioni con lo stesso istante.
 * Un'eccezione qui = un quaderno che non si riapre.
 */
fun roundTripCheck(): Int {
    val rnd = Random(7)
    val errors = LinkedHashMap<String, Int>()
    var total = 0
    for (scenario in listOf("normale", "zoom1000", "ferma", "stesso-istante", "lentissima")) {
        repeat(400) {
            val raw = MutableStrokeInputBatch()
            var x = 100f + rnd.nextFloat() * 300f
            var y = 100f + rnd.nextFloat() * 500f
            var t = 0L
            var dir = rnd.nextFloat() * 6.28f
            val n = 5 + rnd.nextInt(150)
            val step = when (scenario) { "zoom1000" -> 0.01f + rnd.nextFloat() * 0.08f; "lentissima" -> 0.0005f + rnd.nextFloat() * 0.003f; else -> 0.2f + rnd.nextFloat() * 1.2f }
            for (i in 0 until n) {
                runCatching {
                    raw.add(InputToolType.STYLUS, x, y, t, 0.0353f / (if (scenario == "zoom1000") 10f else 1f),
                        (0.2f + rnd.nextFloat() * 0.6f), rnd.nextFloat() * 1.2f, rnd.nextFloat() * 6.28f)
                }
                dir += (rnd.nextFloat() - 0.5f) * 0.6f
                val still = scenario == "ferma" && rnd.nextInt(3) == 0
                if (!still) { x += kotlin.math.cos(dir) * step; y += kotlin.math.sin(dir) * step }
                t += if (scenario == "stesso-istante" && rnd.nextInt(3) == 0) 0L else 4L + rnd.nextInt(5)
            }
            val kind = listOf(ToolKind.PEN, ToolKind.BALLPOINT, ToolKind.PENCIL, ToolKind.CALLIGRAPHY, ToolKind.MARKER, ToolKind.HIGHLIGHTER).random(rnd)
            val variant = PenFeel.encode(rnd.nextInt(3), rnd.nextInt(4))
            val size = Brushes.sizeForLevel(kind, 1 + rnd.nextInt(100))
            total++
            val stage = StringBuilder()
            try {
                stage.setLength(0); stage.append("disegno")
                val brush = Brushes.create(kind, 0xFF000000.toInt(), size, variant)
                val dense = densifyInputs(raw)
                Stroke(brush, dense)
                stage.setLength(0); stage.append("encode/decode")
                val back = StrokeInputBatch.decode(dense.encode())
                stage.setLength(0); stage.append("ricarica")
                val again = densifyInputs(back)
                Stroke(Brushes.create(kind, 0xFF000000.toInt(), size, variant), again)
            } catch (e: Throwable) {
                val key = "$scenario/$stage: ${e.javaClass.simpleName}: ${e.message?.take(120)}"
                errors[key] = (errors[key] ?: 0) + 1
            }
        }
    }
    if (errors.isEmpty()) println("  ok  salva/ricarica: $total tratti casuali, nessun errore")
    else errors.forEach { (k, v) -> println("  KO  $v× $k") }
    return errors.values.sum()
}
