import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import com.caro.tratto.ink.Brushes
import com.caro.tratto.model.ToolKind

/** Tratto orizzontale sintetico (punti PDF) con pressione costante, come lo darebbe la S Pen. */
private fun line(pressure: Float, length: Float = 200f): MutableStrokeInputBatch {
    val batch = MutableStrokeInputBatch()
    val n = 120
    for (i in 0..n) {
        val t = i / n.toFloat()
        batch.add(InputToolType.STYLUS, 10f + t * length, 100f, (t * 400).toLong(), 0.0353f, pressure, 0.6f, 0f)
    }
    return batch
}

/** Spessore al centro: altezza del bounding box di un tratto orizzontale. */
private fun widthOf(stroke: Stroke): Float {
    val box = stroke.shape.computeBoundingBox() ?: return 0f
    return box.yMax - box.yMin
}

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "edge") { edgeProbe(); quantProbe(); toolProbe(); return }
    if (args.isNotEmpty() && args[0] == "time") { loadTimeProbe(); return }
    if (args.isNotEmpty() && args[0] == "gen") { genNotebook(args[1], args[2].toInt(), args[3].toInt()); return }
    if (args.isNotEmpty()) { dumpStrokes(args[0]); return }
    var failures = 0
    println("livello→pt: 1=%.3f 25=%.3f 45=%.3f 70=%.2f 100=%.1f".format(
        Brushes.sizeForLevel(ToolKind.PEN, 1), Brushes.sizeForLevel(ToolKind.PEN, 25),
        Brushes.sizeForLevel(ToolKind.PEN, 45), Brushes.sizeForLevel(ToolKind.PEN, 70),
        Brushes.sizeForLevel(ToolKind.PEN, 100)))
    for (kind in ToolKind.entries) {
        for (level in listOf(1, 45, 100)) {
            try {
                val size = Brushes.sizeForLevel(kind, level)
                val brush = Brushes.create(kind, Brushes.defaultSettings(kind).color, size)
                val widths = listOf(0.15f, 0.5f, 1f).map { p -> widthOf(Stroke(brush, line(p))) }
                val s = Stroke(brush, line(0.5f))
                val groups = s.shape.getRenderGroupCount()
                val outlines = (0 until groups).sumOf { s.shape.getOutlineCount(it) }
                // ida e ritorno della serializzazione
                val back = StrokeInputBatch.decode(s.inputs.encode())
                check(back.size == s.inputs.size) { "serializzazione" }
                check(Brushes.levelForSize(kind, size) == level) { "livello ${Brushes.levelForSize(kind, size)} != $level" }
                println(
                    "%-12s liv %3d  size %7.3f pt  larghezza p.15/.5/1 = %s  gruppi=%d outline=%d".format(
                        kind.name, level, size, widths.joinToString("/") { "%.3f".format(it) }, groups, outlines,
                    ),
                )
            } catch (e: Throwable) {
                failures++
                println("ERRORE $kind livello $level: $e")
            }
        }
    }
    println("sensazione penna (pressione × stabilizzazione):")
    for (kind in listOf(ToolKind.PEN, ToolKind.BALLPOINT, ToolKind.PENCIL, ToolKind.CALLIGRAPHY)) {
        val row = StringBuilder("  %-12s".format(kind.name))
        for (p in 0..2) for (st in 0..3) {
            val v = com.caro.tratto.ink.PenFeel.encode(p, st)
            try {
                check(com.caro.tratto.ink.PenFeel.pressure(v) == p && com.caro.tratto.ink.PenFeel.stabilization(v) == st) { "codifica" }
                val brush = Brushes.create(kind, 0xFF000000.toInt(), Brushes.sizeForLevel(kind, 45), v)
                val lo = widthOf(Stroke(brush, line(0.15f)))
                val hi = widthOf(Stroke(brush, line(1f)))
                if (st == 1) row.append(" p$p:%.2f–%.2f".format(lo, hi))
            } catch (e: Throwable) {
                failures++
                row.append(" ERR(p$p,s$st: $e)")
            }
        }
        println(row)
    }
    run {
        val table = listOf(75 to 55, 100 to 46, 150 to 40, 200 to 35, 300 to 25, 450 to 15, 750 to 10, 1000 to 5)
        val got = table.map { (z, _) -> Brushes.levelForZoom(46, z) }
        val ok = got == table.map { it.second }
        println("spessore proporzionale allo zoom (base 46): $got ${if (ok) "= tabella di Caro" else "≠ tabella!"}")
        if (!ok) failures++
        println("  base 56: 200%→${Brushes.levelForZoom(56, 200)} · tra i punti 125%→${Brushes.levelForZoom(46, 125)} · 50%→${Brushes.levelForZoom(46, 50)}")
    }
    lineEndsCheck()
    tipCheck()
    println("selezione:")
    failures += selectionCheck()
    upsampleCheck()
    failures += densifyCheck()
    println("forme e gomma parziale:")
    failures += shapeCheck()
    println("salva e ricarica:")
    failures += roundTripCheck()
    println("apertura quaderni:")
    failures += openCheck(System.getenv("TRATTO_BIG"))
    println(if (failures == 0) "OK: pennelli e selezione validi" else "FALLITI: $failures")
    if (failures > 0) kotlin.system.exitProcess(1)
}
