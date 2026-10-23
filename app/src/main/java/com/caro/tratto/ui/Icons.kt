package com.caro.tratto.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * Icone dell'app disegnate a mano: path SVG 24x24, outline 2 px, estremi arrotondati.
 * Il colore reale arriva dal tint di Icon(), qui il nero è solo un segnaposto.
 */
object TrattoIcons {
    val Back: ImageVector by lazy { outline("Indietro", "M19 12H5", "M11 18l-6-6 6-6") }

    val Undo: ImageVector by lazy {
        outline("Annulla", "M9 14L4 9l5-5", "M4 9h10.5a5.5 5.5 0 0 1 0 11H11")
    }

    val Redo: ImageVector by lazy {
        outline("Ripeti", "M15 14l5-5-5-5", "M20 9H9.5a5.5 5.5 0 0 0 0 11H13")
    }

    /** Penna: corpo sottile con punta fine. */
    val Pen: ImageVector by lazy {
        outline(
            "Penna",
            "M16.5 5a1.77 1.77 0 0 1 2.5 2.5L8 18.5l-4 1.5 1.5-4z",
            "M14.5 7l2.5 2.5",
        )
    }

    /** Pennarello: corpo largo e punta a scalpello. */
    val Marker: ImageVector by lazy {
        outline(
            "Pennarello",
            "M13.5 4.5a2.83 2.83 0 0 1 4 4l-8 8-4-4z",
            "M5.5 12.5L4.25 16.25l1.5 1.5L9.5 16.5",
            "M11.5 6.5l4 4",
        )
    }

    /** Evidenziatore: corpo tozzo, punta piatta e traccia sotto. */
    val Highlighter: ImageVector by lazy {
        outline(
            "Evidenziatore",
            "M15 3l6 6-7.5 7.5-6-6z",
            "M9 12l-3.5 3.5 3 3 3.5-3.5",
            "M3.5 21h8.5",
        )
    }

    /** Stilografica: pennino con taglio e foro. */
    val Fountain: ImageVector by lazy {
        outline(
            "Stilografica",
            "M12 3l5 7-5 11-5-11z",
            "M12 13v8",
            "M12 9.3a1.2 1.2 0 1 1 0 2.4a1.2 1.2 0 1 1 0-2.4z",
        )
    }

    /** Matita: corpo esagonale con punta temperata. */
    val Pencil: ImageVector by lazy {
        outline(
            "Matita",
            "M15 4.5l4.5 4.5L9 19.5 4 20l.5-5z",
            "M12.5 7l4.5 4.5",
            "M4.5 15l4.5 4.5",
        )
    }

    /** Calligrafica: pennino piatto obliquo. */
    val Calligraphy: ImageVector by lazy {
        outline(
            "Calligrafica",
            "M14 4l6 6-8 8-5 1 1-5z",
            "M8 14l2 2",
            "M3.5 20.5L8 16",
        )
    }

    /** Lasso: anello aperto con coda. */
    val Lasso: ImageVector by lazy {
        outline(
            "Lasso",
            "M12 4c4.4 0 8 2.2 8 5s-3.6 5-8 5-8-2.2-8-5 3.6-5 8-5z",
            "M7.5 13c-1.2 1.4-.8 3.3 1 3.8 1.5.4 1.9 1.6 1.2 3.2",
        )
    }

    val Copy: ImageVector by lazy {
        outline("Duplica", "M9 9h10v10H9z", "M5 15V5h10")
    }

    /** Forme: triangolo + cerchio. */
    val Shapes: ImageVector by lazy {
        outline("Forme", "M8 4l5.5 9h-11z", "M16 11.5a4.5 4.5 0 1 1 0 9a4.5 4.5 0 1 1 0-9z")
    }

    /** Linea dritta: righello inclinato con tacche. */
    val Ruler: ImageVector by lazy {
        outline("Linea dritta", "M3 17L17 3l4 4L7 21z", "M7 13l2 2", "M10 10l2 2", "M13 7l2 2")
    }

    /** Lucchetto (blocca zoom). */
    val Lock: ImageVector by lazy {
        outline("Blocca zoom", "M6 11h12v9H6z", "M8.5 11V8a3.5 3.5 0 0 1 7 0v3")
    }

    val Eraser: ImageVector by lazy {
        outline("Gomma", "M4 14l9-9 6 6-9 9z", "M8 10l6 6", "M10 20h10")
    }

    /** Condividi / esporta: freccia che esce dal vassoio. */
    val Share: ImageVector by lazy {
        outline(
            "Esporta",
            "M12 15V3",
            "M8 7l4-4 4 4",
            "M5 12v6a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-6",
        )
    }

    val Plus: ImageVector by lazy { outline("Più", "M12 5v14", "M5 12h14") }

    /** Documento con freccia in giù: importa un PDF. */
    val Import: ImageVector by lazy {
        outline("Importa", "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z", "M14 3v5h5", "M12 11v6", "M9 14l3 3 3-3")
    }

    /** Mano con indice alzato: "scrivi col dito". */
    val Hand: ImageVector by lazy {
        outline(
            "Dito",
            "M9 15V4.5a1.5 1.5 0 0 1 3 0V12",
            "M12 11.5a1.5 1.5 0 0 1 3 0v1",
            "M15 12a1.5 1.5 0 0 1 3 0v3a6 6 0 0 1-6 6h-1a5 5 0 0 1-3.8-1.8l-3-3.6a1.5 1.5 0 0 1 2.3-1.9L9 15",
        )
    }

    val Page: ImageVector by lazy {
        outline(
            "Pagina",
            "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z",
            "M14 3v5h5",
        )
    }

    val PageAdd: ImageVector by lazy {
        outline(
            "Nuova pagina",
            "M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z",
            "M14 3v5h5",
            "M12 11v6",
            "M9 14h6",
        )
    }

    val Image: ImageVector by lazy {
        outline(
            "Immagine",
            "M3 5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
            "M21 15l-5-5L5 21",
            "M7 9a2 2 0 1 0 4 0a2 2 0 1 0-4 0",
        )
    }

    val Check: ImageVector by lazy { outline("Selezionato", "M5 12.5l4.5 4.5L19 7.5") }

    val Trash: ImageVector by lazy {
        outline(
            "Elimina",
            "M4 7h16",
            "M9 7V4.5h6V7",
            "M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12",
            "M10 11v6",
            "M14 11v6",
        )
    }

    /** Impostazioni: tre cursori. */
    val Settings: ImageVector by lazy {
        outline(
            "Impostazioni",
            "M4 6h9", "M19 6h1", "M16 3.8a2.2 2.2 0 1 1 0 4.4a2.2 2.2 0 1 1 0-4.4z",
            "M4 12h1", "M11 12h9", "M8 9.8a2.2 2.2 0 1 1 0 4.4a2.2 2.2 0 1 1 0-4.4z",
            "M4 18h9", "M19 18h1", "M16 15.8a2.2 2.2 0 1 1 0 4.4a2.2 2.2 0 1 1 0-4.4z",
        )
    }

    /** Tag: etichetta con il foro. */
    val Tag: ImageVector by lazy {
        outline("Tag", "M3.5 11.6V4.5a1 1 0 0 1 1-1h7.1l8.9 8.9a1.4 1.4 0 0 1 0 2l-6.1 6.1a1.4 1.4 0 0 1-2 0z", "M8 8h.01")
    }

    private const val BOOKMARK = "M6.5 3.5h11v17l-5.5-4-5.5 4z"

    /** Segnalibro: nastrino vuoto / pieno. */
    val Bookmark: ImageVector by lazy { outline("Segnalibri", BOOKMARK) }
    val BookmarkFilled: ImageVector by lazy { filled("Segnalibro", BOOKMARK) }

    /** Pagine recenti: orologio. */
    val Recent: ImageVector by lazy { outline("Pagine recenti", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18z", "M12 7v5l3 2") }

    val Search: ImageVector by lazy { outline("Cerca", "M11 4a7 7 0 1 0 0 14a7 7 0 1 0 0-14z", "M20 20l-4-4") }

    val Close: ImageVector by lazy { outline("Chiudi", "M6 6l12 12", "M18 6L6 18") }

    /** Rinomina: matita su una riga. */
    val Rename: ImageVector by lazy {
        outline("Rinomina", "M4 20h4L18.5 9.5a2.12 2.12 0 0 0-3-3L5 17v3z", "M13.5 8.5l2 2", "M12 20h8")
    }

    /** Sposta: cartella con freccia. */
    val MoveTo: ImageVector by lazy {
        outline(
            "Sposta",
            "M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z",
            "M8.5 13h7",
            "M12.5 10l3 3-3 3",
        )
    }

    /** Colore: tavolozza del pittore. */
    val Palette: ImageVector by lazy {
        outline(
            "Colore",
            "M12 3a9 9 0 1 0 0 18c1.1 0 1.8-.8 1.8-1.8 0-.5-.2-.9-.5-1.2-.3-.3-.5-.7-.5-1.2 0-1 .8-1.8 1.8-1.8H17a4 4 0 0 0 4-4c0-4.4-4-8-9-8z",
            "M7 12a.5.5 0 1 0 1 0a.5.5 0 1 0-1 0z",
            "M9.5 8a.5.5 0 1 0 1 0a.5.5 0 1 0-1 0z",
            "M14 7.5a.5.5 0 1 0 1 0a.5.5 0 1 0-1 0z",
        )
    }

    /** Sincronizza: nuvola con freccia in su. */
    val CloudSync: ImageVector by lazy {
        outline("Sincronizza", "M16 16l-4-4-4 4", "M12 12v9", "M20.39 18.39A5 5 0 0 0 18 9h-1.26A8 8 0 1 0 3 16.3")
    }

    private const val STAR = "M12 3L14.41 9.28L21.13 9.63L15.9 13.87L17.64 20.37L12 16.7L6.36 20.37L8.1 13.87L2.87 9.63L9.59 9.28z"

    /** Preferito: stella vuota / piena. */
    val Star: ImageVector by lazy { outline("Preferito", STAR) }
    val StarFilled: ImageVector by lazy { filled("Preferito", STAR) }

    /** Menu: tre puntini verticali (pieni). */
    val More: ImageVector by lazy {
        filled(
            "Altro",
            "M12 3.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2z",
            "M12 10.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2z",
            "M12 17.4a1.6 1.6 0 1 1 0 3.2a1.6 1.6 0 1 1 0-3.2z",
        )
    }
}

private fun builder(name: String) = ImageVector.Builder(
    name = name,
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
)

private fun outline(name: String, vararg paths: String): ImageVector =
    builder(name).apply {
        for (path in paths) {
            addPath(
                pathData = addPathNodes(path),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()

private fun filled(name: String, vararg paths: String): ImageVector =
    builder(name).apply {
        for (path in paths) {
            addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        }
    }.build()
