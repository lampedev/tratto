import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInputBatch
import androidx.ink.storage.decode
import androidx.ink.storage.encode

/** Valori ai bordi (pressione 1, tilt π/2, orientamento ~2π): sopravvivono a encode/decode? */
fun edgeProbe() {
    val twoPi = (2 * Math.PI).toFloat()
    val cases = listOf(
        Triple(1f, 0.5f, 1f), Triple(0f, 0.5f, 1f), Triple(0.999999f, 0.5f, 1f),
        Triple(0.5f, (Math.PI / 2).toFloat(), 1f), Triple(0.5f, 1.5707963f, 1f),
        Triple(0.5f, 0.5f, Math.nextDown(twoPi)), Triple(0.5f, 0.5f, 6.2831f), Triple(0.5f, 0.5f, 6.28318f), Triple(0.5f, 0.5f, 0f),
    )
    for ((p, tilt, ori) in cases) {
        val b = MutableStrokeInputBatch()
        val r = runCatching {
            for (i in 0 until 20) b.add(InputToolType.STYLUS, 100f + i * 0.7f, 200f + i * 0.3f, i * 5L, 0.0353f,
                if (i % 2 == 0) p else 0.37f, if (i % 2 == 0) tilt else 0.21f, if (i % 2 == 0) ori else 3.3f)
            val back = StrokeInputBatch.decode(b.encode())
            val x = back.get(0)
            val dense = runCatching { com.caro.tratto.editor.densifyInputs(back).size.toString() }.getOrElse { "ERRORE densify: ${it.message?.take(120)}" }
            val st = runCatching { androidx.ink.strokes.Stroke(com.caro.tratto.ink.Brushes.create(com.caro.tratto.model.ToolKind.PEN, -16777216, 1f), back); "ok" }.getOrElse { "ERRORE stroke: ${it.message?.take(120)}" }
            "decode ok p=${x.pressure} tilt=${x.tiltRadians} ori=${x.orientationRadians} | densify $dense | stroke $st"
        }
        println("  p=$p tilt=$tilt ori=$ori → ${r.getOrElse { "ERRORE ${it.javaClass.simpleName}: ${it.message?.take(150)}" }}")
    }
}

/** Quanto è fine la quantizzazione di posizione e tempo nella serializzazione? */
fun quantProbe() {
    for (step in listOf(0.1f, 0.01f, 0.001f, 0.0001f)) for (dt in listOf(4L, 1L, 0L)) {
        val b = MutableStrokeInputBatch()
        val r = runCatching {
            for (i in 0 until 30) b.add(InputToolType.STYLUS, 300f + i * step, 400f + i * step * 0.5f, i * dt + (if (dt == 0L) 0L else 0L), 0.0353f, 0.5f, 0.3f, 1f)
            val back = runCatching { StrokeInputBatch.decode(b.encode()) }.getOrElse { return@runCatching "ERRORE decode: ${it.message?.take(160)}" }
            val xs = (0 until back.size).map { back.get(it).x }
            val dist = xs.zipWithNext().count { (a, c) -> a == c }
            val st = runCatching { androidx.ink.strokes.Stroke(com.caro.tratto.ink.Brushes.create(com.caro.tratto.model.ToolKind.PEN, -16777216, 1f), back); "ok" }.getOrElse { "ERRORE stroke: ${it.message?.take(120)}" }
            "n=${back.size} x0=${xs[0]} x1=${xs[1]} uguali-consecutivi=$dist stroke $st"
        }
        println("  passo=$step dt=$dt → ${r.getOrElse { "ERRORE input: ${it.message?.take(160)}" }}")
    }
}

fun toolProbe() {
    for (tool in listOf(InputToolType.STYLUS, InputToolType.TOUCH, InputToolType.MOUSE, InputToolType.UNKNOWN)) for (opt in listOf(true, false)) {
        val b = MutableStrokeInputBatch()
        val r = runCatching {
            for (i in 0 until 20) {
                if (opt) b.add(tool, 100f + i, 100f, i * 4L, 0.0353f, 0.5f, 0.3f, 1f)
                else b.add(tool, 100f + i, 100f, i * 4L)
            }
            val back = StrokeInputBatch.decode(b.encode())
            val d = com.caro.tratto.editor.densifyInputs(back)
            androidx.ink.strokes.Stroke(com.caro.tratto.ink.Brushes.create(com.caro.tratto.model.ToolKind.PEN, -16777216, 1f), d)
            "ok n=${d.size} tool=${back.get(0).toolType}"
        }
        println("  $tool opzionali=$opt → ${r.getOrElse { "ERRORE ${it.javaClass.simpleName}: ${it.message?.take(160)}" }}")
    }
}

/** Tempo di "caricamento" di un quaderno grande: decode + densify + Stroke per N tratti. */
fun loadTimeProbe() {
    val rnd = kotlin.random.Random(3)
    val blobs = ArrayList<ByteArray>()
    repeat(3000) {
        val b = MutableStrokeInputBatch()
        var x = 50f + rnd.nextFloat() * 400f; var y = 50f + rnd.nextFloat() * 700f; var dir = 0f
        val n = 20 + rnd.nextInt(120)
        for (i in 0 until n) {
            b.add(InputToolType.STYLUS, x, y, i * 4L, 0.0353f, 0.3f + rnd.nextFloat() * 0.4f, 0.4f, 1f)
            dir += (rnd.nextFloat() - 0.5f) * 0.8f; x += kotlin.math.cos(dir) * 0.6f; y += kotlin.math.sin(dir) * 0.6f
        }
        blobs.add(com.caro.tratto.editor.densifyInputs(b).encode())
    }
    fun rssMb() = java.io.File("/proc/self/status").readLines().first { it.startsWith("VmRSS") }.filter { it.isDigit() }.toLong() / 1024
    val keep = ArrayList<Any>()
    repeat(2) { round ->
        val t0 = System.nanoTime(); var pts = 0
        val r0 = rssMb()
        for (bytes in blobs) {
            val inputs = com.caro.tratto.editor.densifyInputs(StrokeInputBatch.decode(bytes)); pts += inputs.size
            keep.add(androidx.ink.strokes.Stroke(com.caro.tratto.ink.Brushes.create(com.caro.tratto.model.ToolKind.PEN, -16777216, 0.8f), inputs))
        }
        System.gc()
        println("  RSS ${r0} → ${rssMb()} MB")
        var tDec = 0L; var tDen = 0L; var tSt = 0L
        for (bytes in blobs) {
            val a = System.nanoTime(); val dec = StrokeInputBatch.decode(bytes)
            val b = System.nanoTime(); val den = com.caro.tratto.editor.densifyInputs(dec)
            val c = System.nanoTime(); androidx.ink.strokes.Stroke(com.caro.tratto.ink.Brushes.create(com.caro.tratto.model.ToolKind.PEN, -16777216, 0.8f), den)
            val d = System.nanoTime(); tDec += b - a; tDen += c - b; tSt += d - c
        }
        println("  decode ${tDec / 1_000_000} ms · densify ${tDen / 1_000_000} ms · Stroke ${tSt / 1_000_000} ms")
        println("  giro $round: ${blobs.size} tratti, $pts input, ${(System.nanoTime() - t0) / 1_000_000} ms, ${blobs.sumOf { it.size } / 1024} KB su disco")
    }
}

/** Scrive uno strokes.bin v3 "da lezione": [pages] pagine × [perPage] tratti a mano, per provare l'apertura. */
fun genNotebook(path: String, pages: Int, perPage: Int) {
    val rnd = kotlin.random.Random(11)
    java.io.DataOutputStream(java.io.File(path).outputStream().buffered()).use { out ->
        out.writeInt(0x54524154); out.writeInt(3); out.writeInt(pages)
        repeat(pages) {
            out.writeInt(perPage)
            repeat(perPage) { k ->
                val b = MutableStrokeInputBatch()
                val row = k / 25; val col = k % 25
                var x = 40f + col * 20f; var y = 60f + row * 30f; var dir = rnd.nextFloat() * 6f
                val n = 20 + rnd.nextInt(100)
                for (i in 0 until n) {
                    b.add(InputToolType.STYLUS, x, y, i * 4L, 0.0353f, 0.3f + rnd.nextFloat() * 0.4f, 0.4f, 1f)
                    dir += (rnd.nextFloat() - 0.5f) * 1.2f; x += kotlin.math.cos(dir) * 0.35f; y += kotlin.math.sin(dir) * 0.35f
                }
                val bytes = com.caro.tratto.editor.densifyInputs(b).encode()
                out.writeByte(0); out.writeByte(0); out.writeInt(0xFF111111.toInt()); out.writeFloat(0.9f)
                out.writeInt(bytes.size); out.write(bytes)
            }
            out.writeInt(0)
        }
    }
    println("scritto $path: $pages pagine × $perPage tratti")
}
