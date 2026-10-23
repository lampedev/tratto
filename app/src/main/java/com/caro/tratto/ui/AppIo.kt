package com.caro.tratto.ui

import com.caro.tratto.model.NotebookDocument
import com.caro.tratto.model.PageModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob

/**
 * IO del repository su una coda SERIALE (un thread alla volta, ordine FIFO):
 * l'ultimo salvataggio dell'editor finisce sempre prima che la libreria rilegga la lista.
 */
internal object AppIo {
    @OptIn(ExperimentalCoroutinesApi::class)
    val serial: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

    /** Scope di processo: un salvataggio partito alla chiusura dell'editor arriva comunque in fondo. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + serial)
}

/**
 * Copia superficiale del documento, da fare sul main thread: l'editor modifica doc.pages
 * mentre si scrive, quindi save/export su IO lavorano sempre su una copia.
 * Gli Stroke di Ink sono immutabili: basta copiare le liste.
 */
internal fun NotebookDocument.snapshot(): NotebookDocument =
    NotebookDocument(
        meta = meta,
        pages = pages.mapTo(ArrayList(pages.size)) { PageModel(ArrayList(it.strokes), ArrayList(it.images), it.pdfPage, it.bookmark, it.id, ArrayList(it.links)) },
    )
