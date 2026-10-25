package com.caro.tratto.model

import androidx.ink.strokes.Stroke

/*
 * CONTRATTO CONDIVISO tra editor, storage, export e UI.
 * Unità di misura del mondo: punti PDF (1/72 di pollice). Ogni pagina ha origine (0,0)
 * in alto a sinistra e misura format.widthPt x format.heightPt. I tratti sono salvati in
 * coordinate LOCALI della pagina, così export PDF/PNG e schermo usano la stessa geometria.
 */

/**
 * Misura delle pagine del quaderno. I formati fissi si salvano per nome; [PDF] è la misura della
 * prima pagina di un PDF importato (larghezza/altezza salvate a parte in meta.json).
 */
data class PageFormat(val widthPt: Int, val heightPt: Int, val label: String, val name: String) {
    companion object {
        val A4_PORTRAIT = PageFormat(595, 842, "A4 verticale", "A4_PORTRAIT")
        val A4_LANDSCAPE = PageFormat(842, 595, "A4 orizzontale", "A4_LANDSCAPE")
        val entries = listOf(A4_PORTRAIT, A4_LANDSCAPE)
        const val PDF = "PDF"

        fun pdf(widthPt: Int, heightPt: Int) = PageFormat(widthPt.coerceIn(36, 14400), heightPt.coerceIn(36, 14400), "Pagine del PDF", PDF)

        fun byName(name: String?, widthPt: Int, heightPt: Int): PageFormat =
            entries.firstOrNull { it.name == name } ?: if (name == PDF && widthPt > 0 && heightPt > 0) pdf(widthPt, heightPt) else A4_PORTRAIT
    }
}

enum class PageBackground(val label: String) {
    BLANK("Vuota"),
    LINES("Righe"),
    GRID("Quadretti"),
    DOTS("Puntini"),
    CORNELL("Cornell"),
}

/** Colori carta; [CUSTOM] = colore scelto da Caro (sta in PageStyle.customPaper). Salvati per nome. */
enum class PaperColor(val label: String, val argb: Int) {
    WHITE("Bianca", 0xFFFFFFFF.toInt()),
    BLACK("Nera (OLED)", 0xFF000000.toInt()),
    BLUEPRINT("Blu progetto", 0xFF1B4A8A.toInt()),
    DARK("Grafite", 0xFF25272B.toInt()),
    IVORY("Avorio", 0xFFFBF6E9.toInt()),
    YELLOW("Gialla", 0xFFFFF6CF.toInt()),
    BLUE("Azzurra", 0xFFEDF3FB.toInt()),
    GREEN("Verde", 0xFFEDF6EC.toInt()),
    GRAY("Grigia", 0xFFF0F1F3.toInt()),
    CUSTOM("Personalizzata", 0xFFFFFFFF.toInt()),
}

/**
 * Spaziatura del motivo: righe (Cornell compreso) e quadretti/puntini hanno passi diversi.
 * Le SAMSUNG_* rifanno i fogli di Samsung Notes ([samsungSize] = misura del motivo, vedi
 * PageBackgroundPainter): nei quaderni importati la scrittura resta sulle righe.
 */
enum class LineSpacing(val label: String, val linesMm: Float, val gridMm: Float, val samsungSize: Int = 0) {
    NARROW("Stretta", 6f, 4f),
    MEDIUM("Media", 7.5f, 5f),
    WIDE("Larga", 9f, 7f),
    XL("Molto larga", 11f, 10f),
    SAMSUNG_NARROW("Samsung Notes", 9.45f, 9.45f, 12),
    SAMSUNG_MEDIUM("Samsung Notes", 13.4f, 13.4f, 17),
    SAMSUNG_WIDE("Samsung Notes", 22f, 22f, 28),
}

/** Aspetto del foglio, uguale per tutte le pagine del quaderno. */
data class PageStyle(
    val background: PageBackground = PageBackground.LINES,
    val paper: PaperColor = PaperColor.WHITE,
    val spacing: LineSpacing = LineSpacing.MEDIUM,
    val marginLine: Boolean = false,
    /** Colore carta quando [paper] è CUSTOM. */
    val customPaper: Int = 0xFFFFFFFF.toInt(),
) {
    val paperArgb: Int get() = if (paper == PaperColor.CUSTOM) customPaper or 0xFF000000.toInt() else paper.argb

    /** Carta scura: righe chiare, e chi disegna sopra (lasso, cursori) usa colori chiari. */
    val darkPaper: Boolean get() = luminance(paperArgb) < 0.3f

    companion object {
        /** Luminanza relativa sRGB, 0 = nero, 1 = bianco. */
        fun luminance(argb: Int): Float {
            fun ch(v: Int): Float {
                val c = v / 255f
                return if (c <= 0.03928f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
            }
            return 0.2126f * ch(argb shr 16 and 0xFF) + 0.7152f * ch(argb shr 8 and 0xFF) + 0.0722f * ch(argb and 0xFF)
        }
    }
}

/**
 * Strumenti. ATTENZIONE: strokes.bin salva l'ordinal → nuovi valori SOLO in fondo.
 */
enum class ToolKind(val label: String) {
    PEN("Stilografica"),
    MARKER("Pennarello"),
    HIGHLIGHTER("Evidenziatore"),
    ERASER("Gomma"),
    BALLPOINT("Penna a sfera"),
    PENCIL("Matita"),
    CALLIGRAPHY("Calligrafica"),
    LASSO("Lasso"),
    ;

    /** Strumenti che scrivono "a penna" (stessa slot nella barra). */
    val isPen: Boolean get() = this == PEN || this == BALLPOINT || this == PENCIL || this == CALLIGRAPHY || this == MARKER

    companion object {
        val pens = listOf(PEN, BALLPOINT, PENCIL, CALLIGRAPHY, MARKER)
    }
}

/** Strumento attivo scelto dalla UI. sizePt = spessore base in punti PDF. */
data class ToolSettings(val kind: ToolKind, val color: Int, val sizePt: Float)

data class NotebookMeta(
    val id: String,
    val title: String,
    val format: PageFormat,
    val style: PageStyle,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int,
    /** Cartella che contiene il quaderno; null = radice ("I tuoi quaderni"). */
    val folderId: String? = null,
    /** Quaderno nato da un PDF importato (il file sta nella cartella del quaderno: source.pdf). */
    val fromPdf: Boolean = false,
    /** Fra i preferiti (stellina in libreria, vista "Preferiti"). */
    val favorite: Boolean = false,
    /** Id dei tag del quaderno (vedi [Tag]). */
    val tags: List<String> = emptyList(),
    /** Quaderni a cui portano i collegamenti di questo (per riscriverne il PDF se si spostano). */
    val linkTargets: List<String> = emptyList(),
)

/** Etichetta personale da dare ai quaderni ("Esame", "Da ripassare"…); [color] ARGB. */
data class Tag(val id: String, val name: String, val color: Int)

/**
 * Cartella della libreria; [parentId] null = radice. Le cartelle si possono annidare.
 * [color] = colore scelto (ARGB), null = il blu di serie.
 */
data class Folder(val id: String, val name: String, val parentId: String?, val createdAt: Long, val color: Int? = null)

/**
 * Tratto finito, in coordinate locali della pagina (punti PDF). Colore/spessore stanno in stroke.brush.
 * [variant] = sensazione della penna con cui è stato scritto (pressione/stabilizzazione, vedi PenFeel):
 * serve a ricostruire lo stesso pennello quando il quaderno viene riaperto.
 */
class NoteStroke(val tool: ToolKind, val stroke: Stroke, val variant: Int = 0) {
    /** Input già codificati per strokes.bin: il tratto è immutabile, si codifica una volta sola. */
    @Volatile
    var encoded: ByteArray? = null
}

/**
 * Immagine sulla pagina (foto, screenshot di una slide). Il file sta nella cartella del quaderno
 * (images/[file]); posizione e misura in punti pagina. Immutabile: spostare = nuova istanza.
 */
class PageImage(val file: String, val x: Float, val y: Float, val w: Float, val h: Float) {
    fun moved(t: (Float, Float) -> Pair<Float, Float>, k: Float): PageImage {
        val (nx, ny) = t(x, y)
        return PageImage(file, nx, ny, w * k, h * k)
    }
}

/**
 * [pdfPage] = indice della pagina del PDF importato che fa da sfondo (-1 = foglio normale, col
 * motivo del quaderno). La pagina del PDF è adattata dentro il formato, centrata.
 */
class PageModel(
    val strokes: MutableList<NoteStroke> = mutableListOf(),
    val images: MutableList<PageImage> = mutableListOf(),
    val pdfPage: Int = -1,
    /** Segnalibro sulla pagina: null = nessuno, "" = senza nome ("Pagina N"). Segue la pagina se la si sposta. */
    var bookmark: String? = null,
    /** Identità della pagina, stabile anche se la si sposta: i collegamenti puntano a questa. */
    val id: String = newPageId(),
    /** Collegamenti che partono da zone di questa pagina. */
    val links: MutableList<PageLink> = mutableListOf(),
)

fun newPageId(): String = java.util.UUID.randomUUID().toString()

/**
 * Collegamento da una zona della pagina ([x], [y], [w], [h] in punti) a una pagina di questo
 * quaderno ([notebookId] null) o di un altro. [pageIndex] = dov'era la pagina quando si è creato il
 * collegamento: serve solo se la pagina [pageId] non si trova più. [pointX]/[pointY]: punto preciso
 * nella pagina di arrivo (punti), NaN = pagina intera.
 */
data class PageLink(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val notebookId: String?,
    val pageId: String,
    val pageIndex: Int,
    val pointX: Float = Float.NaN,
    val pointY: Float = Float.NaN,
) {
    val hasPoint: Boolean get() = !pointX.isNaN() && !pointY.isNaN()
    /** Collegamento al quaderno intero (non a una sua pagina): si apre dalla prima pagina. */
    val wholeNotebook: Boolean get() = pageId.isEmpty()
    fun contains(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
}

class NotebookDocument(var meta: NotebookMeta, val pages: MutableList<PageModel>) {
    val format: PageFormat get() = meta.format
    val style: PageStyle get() = meta.style
}
