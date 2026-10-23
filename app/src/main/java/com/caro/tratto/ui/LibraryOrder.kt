package com.caro.tratto.ui

import com.caro.tratto.model.Folder
import com.caro.tratto.model.NotebookMeta
import java.text.Collator
import java.util.Locale

/** Come si ordinano quaderni e cartelle in libreria (impostazioni generali). */
internal enum class LibrarySort(val label: String) {
    NAME("Nome"),
    CREATED("Data di creazione"),
    UPDATED("Ultima modifica");

    /** Le due direzioni: [reversed] = false è la prima (A → Z, oppure i più recenti prima). */
    fun directionLabel(reversed: Boolean) = when (this) {
        NAME -> if (reversed) "Z → A" else "A → Z"
        else -> if (reversed) "Più vecchi prima" else "Più recenti prima"
    }
}

/** I preferiti in cima, poi l'ordine scelto; a parità, il più recente prima. */
internal fun List<NotebookMeta>.sortedForLibrary(sort: LibrarySort, reversed: Boolean): List<NotebookMeta> {
    val names = naturalOrder()
    val key: Comparator<NotebookMeta> = when (sort) {
        LibrarySort.NAME -> Comparator { a, b -> names.compare(a.title, b.title) }
        LibrarySort.CREATED -> compareByDescending { it.createdAt }
        LibrarySort.UPDATED -> compareByDescending { it.updatedAt }
    }
    return sortedWith(
        compareByDescending<NotebookMeta> { it.favorite }
            .then(if (reversed) key.reversed() else key)
            .thenByDescending { it.updatedAt },
    )
}

/** Le cartelle seguono lo stesso ordine; non hanno una data di modifica, lì vanno per nome. */
@JvmName("sortedFoldersForLibrary")
internal fun List<Folder>.sortedForLibrary(sort: LibrarySort, reversed: Boolean): List<Folder> {
    val names = naturalOrder()
    val byName = Comparator<Folder> { a, b -> names.compare(a.name, b.name) }
    return when (sort) {
        LibrarySort.NAME -> sortedWith(if (reversed) byName.reversed() else byName)
        LibrarySort.CREATED -> sortedWith(
            (if (reversed) compareBy<Folder> { it.createdAt } else compareByDescending { it.createdAt }).then(byName),
        )
        LibrarySort.UPDATED -> sortedWith(byName)
    }
}

/**
 * Ordine alfabetico "umano": maiuscole e accenti non pesano, i numeri contano per il loro valore
 * ("Lezione 2" prima di "Lezione 10").
 */
private fun naturalOrder(): Comparator<String> {
    val collator = Collator.getInstance(Locale.ITALIAN).apply { strength = Collator.SECONDARY }
    val chunk = Regex("[0-9]+|[^0-9]+")
    return Comparator { a, b ->
        val ca = chunk.findAll(a.trim()).map { it.value }.toList()
        val cb = chunk.findAll(b.trim()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val c = if (x[0] in '0'..'9' && y[0] in '0'..'9') {
                val nx = x.trimStart('0')
                val ny = y.trimStart('0')
                if (nx.length != ny.length) nx.length - ny.length else nx.compareTo(ny)
            } else {
                collator.compare(x, y)
            }
            if (c != 0) return@Comparator c
        }
        ca.size - cb.size
    }
}

/** Testo senza maiuscole né accenti, per confrontare ("Unità" = "unita"). */
internal fun String.folded(): String =
    java.text.Normalizer.normalize(lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

/** Ogni parola cercata deve stare nel nome o nel percorso ([place]): "fisica lez 3" trova "Fisica › Lezione 3". */
internal fun matchesSearch(query: String, name: String, place: String?): Boolean {
    val words = query.folded().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return false
    val text = (name + " " + place.orEmpty()).folded()
    return words.all { it in text }
}
