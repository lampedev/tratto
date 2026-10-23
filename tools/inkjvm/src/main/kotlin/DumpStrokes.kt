import androidx.ink.strokes.StrokeInputBatch
import androidx.ink.storage.decode
import java.io.DataInputStream
import java.io.File

/** Elenca i tratti di uno strokes.bin (debug): strumento, n. input, primo/ultimo punto. */
fun dumpStrokes(path: String) {
    DataInputStream(File(path).inputStream().buffered()).use { inp ->
        inp.readInt(); val version = inp.readInt(); val pages = inp.readInt()
        repeat(pages) { p ->
            val n = inp.readInt()
            println("pagina $p: $n tratti")
            repeat(n) { k ->
                val tool = inp.readByte().toInt(); if (version >= 2) inp.readByte()
                val color = inp.readInt(); val size = inp.readFloat()
                val bytes = ByteArray(inp.readInt()); inp.readFully(bytes)
                val b = StrokeInputBatch.decode(bytes)
                val a = b.get(0); val z = b.get(b.size - 1)
                println("  #$k tool=$tool size=%.2f n=${b.size} da (%.0f,%.0f) a (%.0f,%.0f) durata=${b.getDurationMillis()}ms".format(size, a.x, a.y, z.x, z.y))
                if (kotlin.math.abs(a.y - z.y) < 1f && b.size > 50) {
                    val st = androidx.ink.strokes.Stroke(com.caro.tratto.ink.Brushes.create(com.caro.tratto.model.ToolKind.entries[tool], color, size), b)
                    println("     spessore: " + widthProfile(st, a.x, z.x, 20).joinToString(" ") { "%.2f".format(it) })
                    val sh = st.shape
                    println("     gruppi=${sh.getRenderGroupCount()} outline=${(0 until sh.getRenderGroupCount()).map { g -> (0 until sh.getOutlineCount(g)).map { o -> sh.getOutlineVertexCount(g, o) } }}")
                    // stesso tratto con input meno densi (1 su 4) per confronto
                    val sparse = androidx.ink.strokes.MutableStrokeInputBatch()
                    for (q in 0 until b.size step 4) { val iq = b.get(q); sparse.add(iq.toolType, iq.x, iq.y, iq.elapsedTimeMillis * 4, iq.strokeUnitLengthCm, iq.pressure, iq.tiltRadians, iq.orientationRadians) }
                    val st2 = androidx.ink.strokes.Stroke(st.brush, sparse)
                    println("     rado: outline=${(0 until st2.shape.getRenderGroupCount()).map { g -> (0 until st2.shape.getOutlineCount(g)).map { o -> st2.shape.getOutlineVertexCount(g, o) } }}")
                    val i0 = b.get(0); val i1 = b.get(1); val i2 = b.get(b.size - 2)
                    println("     input0 p=${i0.pressure} tilt=${i0.tiltRadians} unit=${i0.strokeUnitLengthCm} t=${i0.elapsedTimeMillis}; input1 x=${i1.x} t=${i1.elapsedTimeMillis}; penultimo x=${i2.x}")
                }
            }
        }
    }
}
