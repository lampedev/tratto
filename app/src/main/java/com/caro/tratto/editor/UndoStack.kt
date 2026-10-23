package com.caro.tratto.editor

import com.caro.tratto.model.NoteStroke
import com.caro.tratto.model.NotebookDocument
import com.caro.tratto.model.PageImage
import com.caro.tratto.model.PageModel

/** Una modifica annullabile del documento. */
internal sealed interface Edit {
    /** Ritorna gli indici delle pagine da ridisegnare. */
    fun undo(doc: NotebookDocument): Set<Int>
    fun redo(doc: NotebookDocument): Set<Int>

    class AddStroke(val page: Int, val stroke: NoteStroke) : Edit {
        override fun undo(doc: NotebookDocument): Set<Int> {
            doc.pages[page].strokes.removeAll { it === stroke }
            return setOf(page)
        }

        override fun redo(doc: NotebookDocument): Set<Int> {
            doc.pages[page].strokes.add(stroke)
            return setOf(page)
        }
    }

    /** Tratti cancellati in un gesto di gomma, nell'ordine in cui sono stati rimossi. */
    class RemoveStrokes(val removals: List<Removal>) : Edit {
        override fun undo(doc: NotebookDocument): Set<Int> {
            // Reinserisce all'indietro: ogni indice torna valido esattamente com'era.
            for (r in removals.asReversed()) {
                val list = doc.pages[r.page].strokes
                list.add(r.index.coerceAtMost(list.size), r.stroke)
            }
            return removals.mapTo(HashSet()) { it.page }
        }

        override fun redo(doc: NotebookDocument): Set<Int> {
            for (r in removals) doc.pages[r.page].strokes.removeAll { it === r.stroke }
            return removals.mapTo(HashSet()) { it.page }
        }
    }

    /** Tratti aggiunti in blocco (es. duplicazione della selezione). */
    class AddStrokes(val page: Int, val strokes: List<NoteStroke>) : Edit {
        override fun undo(doc: NotebookDocument): Set<Int> {
            val set = strokes.toIdentitySet()
            doc.pages[page].strokes.removeAll { it in set }
            return setOf(page)
        }

        override fun redo(doc: NotebookDocument): Set<Int> {
            doc.pages[page].strokes.addAll(strokes)
            return setOf(page)
        }
    }

    /** Tratti sostituiti al loro posto (spostamento, ridimensionamento, cambio colore). */
    class ReplaceStrokes(val page: Int, val before: List<NoteStroke>, val after: List<NoteStroke>) : Edit {
        override fun undo(doc: NotebookDocument): Set<Int> {
            swap(doc.pages[page].strokes, after, before)
            return setOf(page)
        }

        override fun redo(doc: NotebookDocument): Set<Int> {
            swap(doc.pages[page].strokes, before, after)
            return setOf(page)
        }

        private fun swap(list: MutableList<NoteStroke>, from: List<NoteStroke>, to: List<NoteStroke>) {
            for (i in from.indices) {
                val at = list.indexOfFirst { it === from[i] }
                if (at >= 0) list[at] = to[i]
            }
        }
    }

    /** Contenuto di pagine sostituito in blocco (gomma parziale: un tratto diventa più pezzi). */
    class PageStrokes(private val changes: Map<Int, Pair<List<NoteStroke>, List<NoteStroke>>>) : Edit {
        override fun undo(doc: NotebookDocument): Set<Int> {
            for ((page, c) in changes) doc.pages[page].strokes.apply { clear(); addAll(c.first) }
            return changes.keys
        }

        override fun redo(doc: NotebookDocument): Set<Int> {
            for ((page, c) in changes) doc.pages[page].strokes.apply { clear(); addAll(c.second) }
            return changes.keys
        }
    }

    /** Elenco immagini di una pagina sostituito (inserimento, spostamento, eliminazione). */
    class PageImages(val page: Int, val before: List<PageImage>, val after: List<PageImage>) : Edit {
        override fun undo(doc: NotebookDocument): Set<Int> {
            doc.pages[page].images.apply { clear(); addAll(before) }
            return setOf(page)
        }

        override fun redo(doc: NotebookDocument): Set<Int> {
            doc.pages[page].images.apply { clear(); addAll(after) }
            return setOf(page)
        }
    }

    /** Più modifiche come un solo passo (es. spostare insieme tratti e immagini). */
    class Compound(val edits: List<Edit>) : Edit {
        override fun undo(doc: NotebookDocument): Set<Int> =
            edits.asReversed().flatMapTo(HashSet()) { it.undo(doc) }

        override fun redo(doc: NotebookDocument): Set<Int> =
            edits.flatMapTo(HashSet()) { it.redo(doc) }
    }

    /** Pagina inserita in posizione [index] (vuota o copia). */
    class AddPage(val index: Int, private val page: PageModel = PageModel()) : Edit {
        override fun undo(doc: NotebookDocument): Set<Int> {
            doc.pages.removeAt(index)
            return emptySet()
        }

        override fun redo(doc: NotebookDocument): Set<Int> {
            doc.pages.add(index, page)
            return setOf(index)
        }
    }

    class MovePage(val from: Int, val to: Int) : Edit {
        override fun undo(doc: NotebookDocument): Set<Int> {
            doc.pages.add(from, doc.pages.removeAt(to))
            return setOf(from, to)
        }

        override fun redo(doc: NotebookDocument): Set<Int> {
            doc.pages.add(to, doc.pages.removeAt(from))
            return setOf(from, to)
        }
    }

    class RemovePage(val index: Int, private val page: PageModel) : Edit {
        override fun undo(doc: NotebookDocument): Set<Int> {
            doc.pages.add(index, page)
            return setOf(index)
        }

        override fun redo(doc: NotebookDocument): Set<Int> {
            doc.pages.removeAt(index)
            return emptySet()
        }
    }
}

internal fun <T> Collection<T>.toIdentitySet(): MutableSet<T> =
    java.util.Collections.newSetFromMap(java.util.IdentityHashMap<T, Boolean>()).also { it.addAll(this) }

internal class Removal(val page: Int, val index: Int, val stroke: NoteStroke)

internal class UndoStack(private val maxSize: Int = 300) {
    private val undo = ArrayDeque<Edit>()
    private val redo = ArrayDeque<Edit>()

    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun push(edit: Edit) {
        undo.addLast(edit)
        if (undo.size > maxSize) undo.removeFirst()
        redo.clear()
    }

    fun undo(doc: NotebookDocument): Set<Int>? {
        val edit = undo.removeLastOrNull() ?: return null
        redo.addLast(edit)
        return edit.undo(doc)
    }

    fun redo(doc: NotebookDocument): Set<Int>? {
        val edit = redo.removeLastOrNull() ?: return null
        undo.addLast(edit)
        return edit.redo(doc)
    }

    fun clear() {
        undo.clear()
        redo.clear()
    }
}
