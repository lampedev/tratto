package com.caro.tratto.editor

import android.graphics.RectF
import com.caro.tratto.model.NoteStroke
import com.caro.tratto.model.PageImage

/**
 * Appunti interni dell'app: tratti e immagini copiati col lasso, in coordinate pagina.
 * Vivono finché l'app è aperta e funzionano anche fra quaderni diversi
 * ([notebookId] dice da dove vengono i file delle immagini).
 */
object NoteClipboard {
    var notebookId: String? = null
        private set
    var strokes: List<NoteStroke> = emptyList()
        private set
    var images: List<PageImage> = emptyList()
        private set
    val bounds = RectF()

    val isEmpty get() = strokes.isEmpty() && images.isEmpty()

    fun set(fromNotebook: String?, strokes: List<NoteStroke>, images: List<PageImage>, bounds: RectF) {
        notebookId = fromNotebook
        this.strokes = strokes
        this.images = images
        this.bounds.set(bounds)
    }
}
