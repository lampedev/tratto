package com.caro.tratto.editor

import android.graphics.Path
import android.graphics.RectF
import com.caro.tratto.model.NoteStroke
import com.caro.tratto.model.NotebookDocument
import com.caro.tratto.model.PageImage
import androidx.ink.geometry.Intersection.intersects
import kotlin.math.hypot
import kotlin.math.max

/**
 * Strumento lasso: disegna un contorno → seleziona i tratti racchiusi; poi trascinando dentro
 * il riquadro si sposta, dalla maniglia in basso a destra si ridimensiona.
 * Durante il gesto i tratti non vengono ricostruiti: si aggiorna solo la matrice di disegno.
 */
private const val TAP_PX = 18f

internal class SelectionController(
    private val vp: Viewport,
    private val pages: PagesView,
    private val overlay: HoverView,
    private val undoStack: UndoStack,
    private val doc: () -> NotebookDocument?,
    /** Il documento è cambiato su queste pagine (ridisegno + autosave). */
    private val onEdited: (Set<Int>) -> Unit,
    /** Riquadro della selezione in coordinate della vista, null se non c'è selezione. */
    private val onSelectionChanged: (RectF?) -> Unit,
) {
    private enum class Mode { NONE, LASSO, MOVE, RESIZE }

    private var mode = Mode.NONE
    private var page = -1
    private var selected: List<NoteStroke> = emptyList()
    private var selectedImages: List<PageImage> = emptyList()
    private val bounds = RectF()            // punti pagina, prima della trasformazione live
    private val t = SelectionTransform()

    private val lassoPage get() = page
    private var lassoPts = FloatArray(256)
    private var lassoCount = 0
    private val lassoPath = Path()
    private var startX = 0f
    private var startY = 0f
    private val screen = RectF()

    val hasSelection get() = selected.isNotEmpty() || selectedImages.isNotEmpty()

    /** Pagina e riquadro della selezione in punti della pagina (con lo spostamento in corso), null se vuota. */
    fun pageBounds(): Pair<Int, RectF>? {
        if (!hasSelection) return null
        return page to RectF(t.mapX(bounds.left), t.mapY(bounds.top), t.mapX(bounds.right), t.mapY(bounds.bottom))
    }

    // ---------------- gesto della penna ----------------

    fun down(sx: Float, sy: Float) {
        if (hasSelection) {
            val r = screenRect(screen)
            if (hypot(sx - r.right, sy - r.bottom) < overlay.handleRadius * 2.6f) {
                mode = Mode.RESIZE
                t.reset()
                t.ax = bounds.left
                t.ay = bounds.top
                return
            }
            val pad = overlay.handleRadius
            if (sx >= r.left - pad && sx <= r.right + pad && sy >= r.top - pad && sy <= r.bottom + pad) {
                mode = Mode.MOVE
                t.reset()
                startX = sx
                startY = sy
                return
            }
            clear()
        }
        val d = doc() ?: return
        page = vp.pageAt(sx, sy).let { if (it < 0) vp.nearestPage(sy) else it }.coerceIn(0, d.pages.size - 1)
        mode = Mode.LASSO
        lassoCount = 0
        lassoPath.reset()
        lassoPath.moveTo(sx, sy)
        addLassoPoint(sx, sy)
    }

    fun move(sx: Float, sy: Float) {
        when (mode) {
            Mode.LASSO -> {
                lassoPath.lineTo(sx, sy)
                addLassoPoint(sx, sy)
                overlay.setLasso(lassoPath)
            }
            Mode.MOVE -> {
                t.dx = (sx - startX) / vp.scale
                t.dy = (sy - startY) / vp.scale
                applyLive()
            }
            Mode.RESIZE -> {
                // nuovo angolo in basso a destra, in punti pagina; scala uniforme dall'angolo opposto
                val px = vp.toDocX(sx)
                val py = vp.toDocY(sy) - vp.pageTop(page)
                val w = max(bounds.width(), 1f)
                val h = max(bounds.height(), 1f)
                t.k = (((px - bounds.left) / w + (py - bounds.top) / h) / 2f).coerceIn(0.1f, 10f)
                applyLive()
            }
            Mode.NONE -> Unit
        }
    }

    fun up() {
        when (mode) {
            Mode.LASSO -> finishLasso()
            Mode.MOVE, Mode.RESIZE -> commitTransform()
            Mode.NONE -> Unit
        }
        mode = Mode.NONE
    }

    fun cancel() {
        if (mode == Mode.LASSO) overlay.setLasso(null)
        if (mode == Mode.MOVE || mode == Mode.RESIZE) {
            t.reset()
            pages.setSelectionTransform(t)
            updateOverlay()
        }
        mode = Mode.NONE
    }

    private fun addLassoPoint(sx: Float, sy: Float) {
        if (lassoCount + 2 > lassoPts.size) lassoPts = lassoPts.copyOf(lassoPts.size * 2)
        lassoPts[lassoCount++] = vp.toDocX(sx)
        lassoPts[lassoCount++] = vp.toDocY(sy) - vp.pageTop(lassoPage)
    }

    private fun finishLasso() {
        overlay.setLasso(null)
        val d = doc() ?: return
        // tocco (lasso quasi nullo): seleziona l'immagine o il tratto sotto la penna
        var pathLen = 0f
        for (i in 1 until lassoCount / 2) {
            pathLen += hypot(lassoPts[2 * i] - lassoPts[2 * i - 2], lassoPts[2 * i + 1] - lassoPts[2 * i - 1])
        }
        if (pathLen * vp.scale < TAP_PX) {
            if (!selectAt(page, lassoPts[0], lassoPts[1])) clear()
            return
        }
        val pts = lassoPts.copyOf(lassoCount)
        val found = selectInLasso(d.pages[page].strokes, pts)
        val images = d.pages[page].images.filter { insidePolygon(it.x + it.w / 2, it.y + it.h / 2, pts) }
        if (found.isEmpty() && images.isEmpty()) {
            clear()
            return
        }
        setSelected(page, found, images)
    }

    private fun applyLive() {
        pages.setSelectionTransform(t)
        updateOverlay()
    }

    private fun commitTransform() {
        val d = doc() ?: return
        if (t.isIdentity) return
        val before = selected
        val after = before.map { transformStroke(it, t) }
        val list = d.pages[page].strokes
        for (i in before.indices) {
            val at = list.indexOfFirst { it === before[i] }
            if (at >= 0) list[at] = after[i]
        }
        val edits = arrayListOf<Edit>(Edit.ReplaceStrokes(page, before, after))
        val movedImages = selectedImages.map { it.moved({ x, y -> t.mapX(x) to t.mapY(y) }, t.k) }
        if (selectedImages.isNotEmpty()) edits.add(replaceImages(d, selectedImages, movedImages))
        undoStack.push(if (edits.size == 1) edits[0] else Edit.Compound(edits))
        t.reset()
        setSelected(page, after, movedImages)
        onEdited(setOf(page))
    }

    // ---------------- azioni sulla selezione ----------------

    fun delete() {
        val d = doc() ?: return
        if (!hasSelection) return
        val list = d.pages[page].strokes
        val set = selected.toIdentitySet()
        val removals = ArrayList<Removal>()
        for (i in list.indices.reversed()) {
            if (list[i] in set) {
                removals.add(Removal(page, i, list[i]))
                list.removeAt(i)
            }
        }
        val edits = arrayListOf<Edit>(Edit.RemoveStrokes(removals))
        if (selectedImages.isNotEmpty()) edits.add(replaceImages(d, selectedImages, emptyList()))
        undoStack.push(if (edits.size == 1) edits[0] else Edit.Compound(edits))
        val p = page
        clear()
        onEdited(setOf(p))
    }

    fun duplicate() {
        val d = doc() ?: return
        if (!hasSelection) return
        val offset = SelectionTransform().apply { dx = 14f; dy = 14f }
        val copies = selected.map { transformStroke(it, offset) }
        d.pages[page].strokes.addAll(copies)
        val edits = arrayListOf<Edit>(Edit.AddStrokes(page, copies))
        val imageCopies = selectedImages.map { it.moved({ x, y -> x + offset.dx to y + offset.dy }, 1f) }
        if (imageCopies.isNotEmpty()) {
            val before = ArrayList(d.pages[page].images)
            d.pages[page].images.addAll(imageCopies)
            edits.add(Edit.PageImages(page, before, ArrayList(d.pages[page].images)))
        }
        undoStack.push(if (edits.size == 1) edits[0] else Edit.Compound(edits))
        setSelected(page, copies, imageCopies)
        onEdited(setOf(page))
    }

    fun recolor(color: Int) {
        val d = doc() ?: return
        if (!hasSelection) return
        val before = selected
        val after = before.map { recolorStroke(it, color) }
        val list = d.pages[page].strokes
        for (i in before.indices) {
            val at = list.indexOfFirst { it === before[i] }
            if (at >= 0) list[at] = after[i]
        }
        undoStack.push(Edit.ReplaceStrokes(page, before, after))
        setSelected(page, after, selectedImages)
        onEdited(setOf(page))
    }

    /** Seleziona l'immagine (la più in alto) o il tratto sotto il punto pagina (px, py). */
    fun selectAt(p: Int, px: Float, py: Float): Boolean {
        val d = doc() ?: return false
        if (p !in d.pages.indices) return false
        d.pages[p].images.lastOrNull { px >= it.x && px <= it.x + it.w && py >= it.y && py <= it.y + it.h }?.let {
            select(p, emptyList(), listOf(it))
            return true
        }
        val tol = (TAP_PX / vp.scale).coerceAtLeast(2f)
        val box = androidx.ink.geometry.ImmutableBox.fromCenterAndDimensions(androidx.ink.geometry.ImmutableVec(px, py), tol, tol)
        d.pages[p].strokes.lastOrNull { ns ->
            val b = ns.stroke.shape.computeBoundingBox()
            b != null && px >= b.xMin - tol && px <= b.xMax + tol && py >= b.yMin - tol && py <= b.yMax + tol &&
                ns.stroke.shape.intersects(box, androidx.ink.geometry.AffineTransform.IDENTITY)
        }?.let {
            select(p, listOf(it), emptyList())
            return true
        }
        return false
    }

    /** Copia la selezione negli appunti interni (tratti + immagini). */
    fun copy(notebookId: String?): Boolean {
        if (!hasSelection) return false
        NoteClipboard.set(notebookId, selected, selectedImages, bounds)
        return true
    }

    /** Seleziona direttamente degli elementi (es. l'immagine appena inserita). */
    fun select(p: Int, strokes: List<NoteStroke>, images: List<PageImage>) {
        t.reset()
        setSelected(p, strokes, images)
    }

    /** Sostituisce nella pagina le immagini [from] con [to] (stesse posizioni) e ritorna l'edit. */
    private fun replaceImages(d: NotebookDocument, from: List<PageImage>, to: List<PageImage>): Edit {
        val imgs = d.pages[page].images
        val before = ArrayList(imgs)
        val set = from.toIdentitySet()
        val kept = imgs.filter { it !in set }
        imgs.clear()
        imgs.addAll(kept)
        imgs.addAll(to)
        return Edit.PageImages(page, before, ArrayList(imgs))
    }

    fun clear() {
        if (!hasSelection && page < 0) return
        selected = emptyList()
        selectedImages = emptyList()
        page = -1
        t.reset()
        pages.setSelection(-1, emptyList())
        overlay.setSelection(null)
        onSelectionChanged(null)
    }

    // ---------------- stato e overlay ----------------

    private fun setSelected(p: Int, strokes: List<NoteStroke>, images: List<PageImage> = emptyList()) {
        page = p
        selected = strokes
        selectedImages = images
        bounds.setEmpty()
        var first = true
        fun add(l: Float, t: Float, r: Float, b: Float) {
            if (first) {
                bounds.set(l, t, r, b)
                first = false
            } else {
                bounds.union(l, t, r, b)
            }
        }
        for (ns in strokes) {
            val b = ns.stroke.shape.computeBoundingBox() ?: continue
            add(b.xMin, b.yMin, b.xMax, b.yMax)
        }
        for (img in images) add(img.x, img.y, img.x + img.w, img.y + img.h)
        pages.setSelection(p, strokes, images)
        updateOverlay()
    }

    /** Da chiamare quando cambia la vista (pan/zoom) o la selezione. */
    fun updateOverlay() {
        if (!hasSelection) return
        val r = screenRect(screen)
        overlay.setSelection(r)
        onSelectionChanged(RectF(r))
    }

    private fun screenRect(out: RectF): RectF {
        val top = vp.pageTop(page)
        val pad = 4f
        out.set(
            vp.offsetX + (t.mapX(bounds.left) - pad) * vp.scale,
            vp.offsetY + (top + t.mapY(bounds.top) - pad) * vp.scale,
            vp.offsetX + (t.mapX(bounds.right) + pad) * vp.scale,
            vp.offsetY + (top + t.mapY(bounds.bottom) + pad) * vp.scale,
        )
        return out
    }
}

/** Punto dentro il poligono (x0, y0, x1, y1, ...), regola pari-dispari. */
internal fun insidePolygon(px: Float, py: Float, pts: FloatArray): Boolean {
    val n = pts.size / 2
    var inside = false
    var j = n - 1
    for (i in 0 until n) {
        val xi = pts[2 * i]; val yi = pts[2 * i + 1]
        val xj = pts[2 * j]; val yj = pts[2 * j + 1]
        if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}
