import com.caro.tratto.data.StrokesFile
import com.caro.tratto.model.PageImage
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Apertura dei quaderni (StrokesFile, lo stesso codice dell'app): un file grande si apre in parallelo
 * e uguale a se stesso, un file troncato restituisce tutto ciò che precede il punto rotto.
 */
fun openCheck(bigFile: String?): Int {
    var fail = pdfPagesCheck()
    val data = if (bigFile != null && File(bigFile).exists()) File(bigFile).readBytes() else return 0.also { println("  (manca il file grande di prova)") }
    val raw = StrokesFile.parse(data)
    println("  file grande: ${raw.pages.size} pagine, ${raw.strokeCount} tratti, completo=${raw.complete}")
    for (threads in listOf(1, 4)) {
        val t0 = System.nanoTime()
        val built = StrokesFile.build(raw, threads)
        println("  ricostruito con $threads thread in ${(System.nanoTime() - t0) / 1_000_000} ms, saltati ${built.skipped}")
        if (built.skipped != 0 || built.pages.sumOf { it.strokes.size } != raw.strokeCount) fail++
        if (threads == 4) {
            // salva → riapri → salva: il secondo file è identico al primo (byte riusati, niente deriva)
            val first = ByteArrayOutputStream().also { StrokesFile.write(it, built.pages) }.toByteArray()
            val reopened = StrokesFile.build(StrokesFile.parse(first), 4)
            val cached = reopened.pages.sumOf { p -> p.strokes.count { it.encoded != null } }
            val second = ByteArrayOutputStream().also { StrokesFile.write(it, reopened.pages) }.toByteArray()
            val same = second.contentEquals(first)
            println(if (same) "  ok  salva/riapri/salva identico byte per byte ($cached/${raw.strokeCount} tratti senza ricodifica)" else "  KO  risalvato diverso (${second.size} vs ${first.size} byte)")
            if (!same) fail++
            // con un'immagine e troncato a metà: si recupera quanto precede
            built.pages[0].images.add(PageImage("foto.jpg", 10f, 20f, 300f, 200f))
            val withImg = ByteArrayOutputStream().also { StrokesFile.write(it, built.pages) }.toByteArray()
            val back = StrokesFile.parse(withImg)
            if (!back.complete || back.pages[0].images.singleOrNull()?.file != "foto.jpg") { println("  KO  immagine persa"); fail++ }
            val cut = StrokesFile.parse(withImg.copyOf(withImg.size / 2))
            val cutBuilt = StrokesFile.build(cut, 4)
            val got = cutBuilt.pages.sumOf { it.strokes.size }
            val okCut = !cut.complete && got in (raw.strokeCount * 4 / 10)..(raw.strokeCount * 6 / 10)
            println("  ${if (okCut) "ok " else "KO "} troncato a metà: recuperati $got tratti su ${raw.strokeCount} (${cutBuilt.pages.size} pagine)")
            if (!okCut) fail++
            val junk = StrokesFile.parse(ByteArray(100) { 7 })
            if (junk.complete || junk.pages.isNotEmpty()) { println("  KO  file spazzatura"); fail++ } else println("  ok  file spazzatura → vuoto, nessun crash")
            // annullamento: si ferma subito con CancellationException
            val stopped = runCatching { StrokesFile.build(raw, 4, cancelled = { true }) }.exceptionOrNull()
            if (stopped is java.util.concurrent.CancellationException) println("  ok  annullamento") else { println("  KO  annullamento: $stopped"); fail++ }
        }
    }
    return fail
}

/** Quaderni da PDF (strokes.bin v4): la pagina del PDF di ogni foglio sopravvive a salva/riapri. */
private fun pdfPagesCheck(): Int {
    val pages = listOf(3, -1, 0, 0, 7).map { com.caro.tratto.model.PageModel(pdfPage = it) }
    val out = ByteArrayOutputStream()
    StrokesFile.write(out, pages)
    val back = StrokesFile.build(StrokesFile.parse(out.toByteArray()), 1).pages.map { it.pdfPage }
    val ok = back == listOf(3, -1, 0, 0, 7)
    println(if (ok) "  ok  pagine PDF salvate e riaperte: $back" else "  KO  pagine PDF: $back")
    return if (ok) 0 else 1
}
