package com.caro.tratto.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.OverScroller
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesFinishedListener
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableParallelogram
import androidx.ink.geometry.ImmutableSegment
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.strokes.Stroke
import androidx.input.motionprediction.MotionEventPredictor
import com.caro.tratto.ink.Brushes
import com.caro.tratto.model.NoteStroke
import com.caro.tratto.model.NotebookDocument
import com.caro.tratto.model.PageImage
import com.caro.tratto.model.PageLink
import com.caro.tratto.model.PageModel
import com.caro.tratto.model.ToolKind
import com.caro.tratto.model.ToolSettings
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

interface EditorListener {
    fun onDocumentChanged()
    fun onUndoRedoChanged(canUndo: Boolean, canRedo: Boolean)
    fun onPageChanged(currentIndex: Int, pageCount: Int)
    /** Lo zoom (in %) è cambiato. */
    fun onZoomChanged(percent: Int) {}
    /** Riquadro della selezione del lasso (coordinate della vista), null se non c'è. */
    fun onSelectionChanged(bounds: RectF?) {}
    /** L'editor chiede alla UI di attivare uno strumento (es. lasso dopo una pressione lunga su un'immagine). */
    fun onRequestTool(kind: ToolKind) {}
    /** Tocco col dito su un collegamento ([x], [y] = punto toccato, coordinate della vista). */
    fun onLinkTapped(page: Int, link: PageLink, x: Float, y: Float) {}
}

/**
 * Editor delle pagine. La S Pen scrive (tratto a bassa latenza su InProgressStrokesView),
 * le dita scorrono/zoomano, il palmo viene ignorato. Tratti in coordinate pagina (punti PDF).
 */
@SuppressLint("ViewConstructor")
class EditorView(context: Context) : FrameLayout(context) {

    var listener: EditorListener? = null
    /** false (default): scrive solo la S Pen, le dita scorrono e zoomano. */
    var fingerDrawing = false
    /** Disegna e tieni premuto: linea, cerchio, rettangolo... diventano forme pulite. */
    var shapeRecognition = true
    /** Modalità Forme: ogni tratto, appena sollevi la penna, diventa la forma riconosciuta. */
    var shapeMode = false
    /** Modalità Linea dritta: dal punto d'appoggio a quello di sollevamento (aggancio 0/45/90°). */
    var lineMode = false
    /** Spessore delle penne proporzionale allo zoom (il livello scelto vale al 100%). */
    var sizeFollowsZoom = false

    /** Zoom bloccato: con due dita si scorre soltanto. */
    var zoomLocked = false

    /** Gomma parziale (cancella solo la parte toccata) invece che a tratto intero. */
    var eraserPartial = false
    /** Bordi extra-morbidi: tratti finiti disegnati come path (vedi PagesView.pathRendering). */
    var smoothEdges: Boolean
        get() = pagesView.pathRendering
        set(value) { pagesView.pathRendering = value }

    /** Sensazione della penna (PenFeel): pressione e stabilizzazione per i nuovi tratti. */
    var penVariant = 0
        set(value) {
            field = value
            setTool(tool)
        }

    private val vp = Viewport()
    private val pagesView = PagesView(context, vp)
    private val inProgress = InProgressStrokesView(context)
    private val hoverView = HoverView(context)
    private val predictor = MotionEventPredictor.newInstance(this)

    private var doc: NotebookDocument? = null
    private val undoStack = UndoStack()
    private val selection = SelectionController(
        vp, pagesView, hoverView, undoStack,
        doc = { doc },
        onEdited = { pages ->
            pages.forEach(pagesView::invalidatePage)
            listener?.onDocumentChanged()
            notifyUndo()
        },
        onSelectionChanged = { listener?.onSelectionChanged(it) },
    )

    private var tool: ToolSettings = Brushes.defaultSettings(ToolKind.PEN)
    private var brush: Brush = Brushes.create(tool.kind, tool.color, tool.sizePt)
    private var eraserSizePt = Brushes.defaultSettings(ToolKind.ERASER).sizePt

    private val density = resources.displayMetrics.density
    private val marginPx = 12f * density
    private val palmMajorPx = resources.displayMetrics.xdpi / 25.4f * 22f   // contatto > 22 mm = palmo
    private var needsFit = true

    /** Pagina da mostrare alla prima apertura (dalla cronologia delle pagine recenti). */
    var startPage = 0

    /** Punto (punti della pagina [startPage]) da mettere al centro alla prima apertura: arrivo da un collegamento. */
    var startPoint: PointF? = null
    private var lastReportedPage = -1

    // --- penna (o dito in modalità "scrivi col dito") ---
    private var penPointerId = INVALID
    private var penStrokeId: InProgressStrokeId? = null
    private var penErasing = false
    private var penLasso = false
    private var penIsFinger = false
    private var penStartTime = 0L
    private var lastStylusTime = Long.MIN_VALUE / 2
    private val pending = HashMap<InProgressStrokeId, Pending>()

    private class Pending(val page: Int, val tool: ToolKind, val variant: Int)

    // --- forme ("disegna e tieni premuto") ---
    private var shapePts = FloatArray(512)
    private var shapeCount = 0
    private var shapePage = -1
    private var holdX = 0f
    private var holdY = 0f
    private var shapeSnapped = false

    // --- linea dritta ---
    private var lineActive = false
    private var strokeBrush: Brush = brush
    private var lastZoomReported = -1
    private var linePage = -1
    private var lineSx = 0f
    private var lineSy = 0f
    private val holdSlop = 4f * resources.displayMetrics.density
    private val holdRunnable = Runnable { tryRecognizeShape() }

    // --- gomma ---
    private val eraseRemovals = ArrayList<Removal>()
    private val eraseSnapshots = HashMap<Int, List<NoteStroke>>()
    private var eraseLastX = 0f
    private var eraseLastY = 0f
    private val boundsCache = HashMap<NoteStroke, RectF>()

    // --- dita: pan / pinch / fling ---
    private val fingerIds = intArrayOf(INVALID, INVALID)
    private var fingerCount = 0
    private var lastFx = 0f
    private var lastFy = 0f
    private var pinchStartSpan = 1f
    private var pinchStartScale = 1f
    private var pinchDocX = 0f
    private var pinchDocY = 0f
    private var velocityTracker: VelocityTracker? = null
    private val scroller = OverScroller(context)

    // pressione lunga col dito su un'immagine = la seleziona (passando al lasso)
    private var longPressX = 0f
    private var longPressY = 0f
    private val longPressRunnable = Runnable { onFingerLongPress() }

    // tocco breve con un dito (per aprire i collegamenti)
    private var fingerTapPossible = false

    // tocco con due dita = annulla (come GoodNotes/Notability)
    private var firstFingerTime = 0L
    private var tapCandidate = false
    private var tapStartTime = 0L
    private var tapStartFx = 0f
    private var tapStartFy = 0f
    private var tapStartSpan = 0f
    private val tapSlop = 14f * resources.displayMetrics.density
    private val tapRestore = FloatArray(3)
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity.toFloat()

    private val flingRunnable = object : Runnable {
        override fun run() {
            if (!scroller.computeScrollOffset()) return
            vp.offsetX = scroller.currX.toFloat()
            vp.offsetY = scroller.currY.toFloat()
            viewportChanged()
            postOnAnimation(this)
        }
    }

    init {
        addView(pagesView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(inProgress, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(hoverView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        inProgress.eagerInit()
        inProgress.addFinishedStrokesListener(object : InProgressStrokesFinishedListener {
            override fun onStrokesFinished(strokes: Map<InProgressStrokeId, Stroke>) = handOff(strokes)
        })
    }

    // ================= API pubblica =================

    fun setDocument(doc: NotebookDocument) {
        if (doc.pages.isEmpty()) doc.pages.add(PageModel())
        this.doc = doc
        hoverView.darkPaper = doc.style.darkPaper
        vp.format = doc.format
        vp.pageCount = doc.pages.size
        pagesView.doc = doc
        selection.clear()
        undoStack.clear()
        boundsCache.clear()
        pending.clear()
        inProgress.cancelUnfinishedStrokes()
        needsFit = true
        if (width > 0) fit(width, height)
        pagesView.invalidateAllPages()
        lastReportedPage = -1
        notifyUndo()
        reportPage()
    }

    fun setTool(settings: ToolSettings) {
        if (settings.kind != ToolKind.LASSO) selection.clear()
        tool = settings
        if (settings.kind == ToolKind.ERASER) {
            eraserSizePt = settings.sizePt
        } else {
            brush = Brushes.create(settings.kind, settings.color, settings.sizePt, penVariant)
        }
    }

    fun undo() {
        val d = doc ?: return
        selection.clear()
        val pages = undoStack.undo(d) ?: return
        afterEdit(pages)
    }

    fun redo() {
        val d = doc ?: return
        selection.clear()
        val pages = undoStack.redo(d) ?: return
        afterEdit(pages)
    }

    fun addPage() {
        val d = doc ?: return
        insertPage(d.pages.size, PageModel())
    }

    fun insertPageAfterCurrent() = insertPageAfter(currentPageIndex())

    fun insertPageAfter(index: Int) {
        val d = doc ?: return
        insertPage((index + 1).coerceIn(0, d.pages.size), PageModel())
    }

    fun duplicateCurrentPage() = duplicatePage(currentPageIndex())

    fun duplicatePage(index: Int) {
        val d = doc ?: return
        val src = d.pages.getOrNull(index) ?: return
        insertPage(
            index + 1,
            PageModel(src.strokes.mapTo(ArrayList()) { NoteStroke(it.tool, it.stroke, it.variant) }, ArrayList(src.images), src.pdfPage, links = ArrayList(src.links)),
        )
    }

    fun deleteCurrentPage(): Boolean = deletePage(currentPageIndex())

    // ---------- collegamenti ----------

    /** Pagina e riquadro (punti) della selezione del lasso, per farne un collegamento. */
    fun selectionArea(): Pair<Int, RectF>? = selection.pageBounds()

    fun addLink(page: Int, link: PageLink) {
        val p = doc?.pages?.getOrNull(page) ?: return
        p.links.add(link)
        pagesView.invalidate()
        listener?.onDocumentChanged()
    }

    fun removeLink(page: Int, link: PageLink) {
        val p = doc?.pages?.getOrNull(page) ?: return
        if (p.links.remove(link)) {
            pagesView.invalidate()
            listener?.onDocumentChanged()
        }
    }

    /** Porta il punto ([x], [y] in punti della pagina [page]) al centro dello schermo, senza cambiare zoom. */
    fun scrollToPoint(page: Int, x: Float, y: Float) {
        scroller.forceFinished(true)
        vp.offsetY = height / 2f - (vp.pageTop(page) + y) * vp.scale
        if (vp.scale > vp.fitScale * 1.01f) vp.offsetX = width / 2f - x * vp.scale
        viewportChanged()
    }

    /** Dove si sta guardando (per tornarci dopo aver seguito un collegamento). */
    fun viewState(): FloatArray = floatArrayOf(vp.offsetX, vp.offsetY, vp.scale)

    fun restoreViewState(s: FloatArray) {
        if (s.size < 3) return
        scroller.forceFinished(true)
        vp.scale = s[2].coerceIn(vp.fitScale * 0.5f, vp.fitScale * 12f)
        vp.offsetX = s[0]
        vp.offsetY = s[1]
        pagesView.zooming = false
        viewportChanged()
    }

    /** Tocco breve col dito: se cade su un collegamento lo si segnala alla UI. */
    private fun fingerTap(x: Float, y: Float) {
        val d = doc ?: return
        val page = vp.pageAt(x, y)
        if (page < 0) return
        val px = vp.toDocX(x)
        val py = vp.toDocY(y) - vp.pageTop(page)
        val link = d.pages[page].links.lastOrNull { it.contains(px, py) } ?: return
        listener?.onLinkTapped(page, link, x, y)
    }

    /** Mette (nome, anche "") o toglie (null) il segnalibro della pagina [index]. */
    fun setBookmark(index: Int, name: String?) {
        val p = doc?.pages?.getOrNull(index) ?: return
        if (p.bookmark == name) return
        p.bookmark = name
        listener?.onDocumentChanged()
    }

    /** Elimina una pagina (mai l'ultima rimasta). Annullabile. */
    fun deletePage(index: Int): Boolean {
        val d = doc ?: return false
        if (d.pages.size <= 1 || index !in d.pages.indices) return false
        selection.clear()
        val removed = d.pages.removeAt(index)
        undoStack.push(Edit.RemovePage(index, removed))
        pageStructureChanged(index.coerceAtMost(d.pages.size - 1))
        return true
    }

    /** Sposta la pagina [from] in posizione [to]. Annullabile. */
    fun movePage(from: Int, to: Int) {
        val d = doc ?: return
        if (from !in d.pages.indices || to !in d.pages.indices || from == to) return
        selection.clear()
        d.pages.add(to, d.pages.removeAt(from))
        undoStack.push(Edit.MovePage(from, to))
        pageStructureChanged(to)
    }

    val pageCount: Int get() = doc?.pages?.size ?: 0

    private fun insertPage(index: Int, page: PageModel) {
        val d = doc ?: return
        selection.clear()
        d.pages.add(index, page)
        undoStack.push(Edit.AddPage(index, page))
        pageStructureChanged(index)
    }

    private fun pageStructureChanged(scrollTo: Int) {
        val d = doc ?: return
        vp.pageCount = d.pages.size
        pagesView.invalidateAllPages()
        lastReportedPage = -1
        notifyChanged()
        scrollToPage(scrollTo)
    }

    // ---- immagini e appunti ----

    /** Bitmap delle immagini del quaderno (fornite dalla UI, con cache). */
    var imageProvider: ((String) -> android.graphics.Bitmap?)?
        get() = pagesView.imageProvider
        set(value) {
            pagesView.imageProvider = value
            pagesView.invalidateAllPages()
        }

    /** Pagine del PDF importato, rese in background (null = quaderno senza PDF). */
    var pdfPages: com.caro.tratto.data.PdfPages?
        get() = pagesView.pdfPages
        set(value) {
            pagesView.pdfPages = value
            pagesView.invalidateAllPages()
        }

    /** Un'immagine è appena stata decodificata: ridisegna le pagine (le immagini arrivano in background). */
    fun onImagesReady() = pagesView.invalidateAllPages()

    /** Id del quaderno aperto (serve agli appunti per sapere da dove vengono le immagini). */
    var notebookId: String? = null

    /** Inserisce un'immagine già copiata nel quaderno, centrata nella parte visibile della pagina, e la seleziona. */
    fun insertImage(name: String, widthPx: Int, heightPx: Int) {
        val d = doc ?: return
        val page = currentPageIndex()
        var w = vp.pageW * 0.6f
        var h = w * heightPx / widthPx.coerceAtLeast(1)
        val maxH = vp.pageH * 0.6f
        if (h > maxH) {
            w *= maxH / h
            h = maxH
        }
        val cy = (vp.toDocY(height / 2f) - vp.pageTop(page)).coerceIn(h / 2, vp.pageH - h / 2)
        val img = PageImage(name, (vp.pageW - w) / 2, cy - h / 2, w, h)
        addElements(page, emptyList(), listOf(img))
    }

    fun copySelection(): Boolean = selection.copy(notebookId)

    /**
     * Incolla gli appunti interni sulla pagina corrente, centrati nella vista.
     * [renamed]: nomi nuovi dei file immagine se vengono da un altro quaderno.
     */
    fun paste(renamed: Map<String, String> = emptyMap()): Boolean {
        if (NoteClipboard.isEmpty) return false
        val page = currentPageIndex()
        val b = NoteClipboard.bounds
        val cx = vp.toDocX(width / 2f)
        val cy = vp.toDocY(height / 2f) - vp.pageTop(page)
        val t = SelectionTransform().apply { dx = cx - b.centerX(); dy = cy - b.centerY() }
        val strokes = NoteClipboard.strokes.map { transformStroke(it, t) }
        val images = NoteClipboard.images.map { img ->
            PageImage(renamed[img.file] ?: img.file, img.x + t.dx, img.y + t.dy, img.w, img.h)
        }
        addElements(page, strokes, images)
        return true
    }

    /** Aggiunge tratti e immagini alla pagina come un solo passo annullabile, e li seleziona. */
    private fun addElements(page: Int, strokes: List<NoteStroke>, images: List<PageImage>) {
        val d = doc ?: return
        selection.clear()
        val edits = ArrayList<Edit>()
        if (strokes.isNotEmpty()) {
            d.pages[page].strokes.addAll(strokes)
            edits.add(Edit.AddStrokes(page, strokes))
        }
        if (images.isNotEmpty()) {
            val before = ArrayList(d.pages[page].images)
            d.pages[page].images.addAll(images)
            edits.add(Edit.PageImages(page, before, ArrayList(d.pages[page].images)))
        }
        if (edits.isEmpty()) return
        undoStack.push(if (edits.size == 1) edits[0] else Edit.Compound(edits))
        pagesView.invalidatePage(page)
        listener?.onDocumentChanged()
        notifyUndo()
        selection.select(page, strokes, images)
    }

    // ---- selezione (lasso) ----
    fun deleteSelection() = selection.delete()
    fun duplicateSelection() = selection.duplicate()
    fun recolorSelection(color: Int) = selection.recolor(color)
    fun clearSelection() = selection.clear()

    fun currentPageIndex(): Int = if (height > 0) vp.nearestPage(height / 2f) else 0

    /** Da chiamare dopo aver cambiato doc.meta (formato o stile del foglio). */
    fun onPageSetupChanged() {
        val d = doc ?: return
        hoverView.darkPaper = d.style.darkPaper
        if (vp.format != d.format) {
            val page = currentPageIndex()
            vp.format = d.format
            needsFit = true
            if (width > 0) fit(width, height)
            scrollToPage(page)
        }
        pagesView.invalidateAllPages()
    }

    fun scrollToPage(index: Int) {
        scroller.forceFinished(true)
        vp.offsetY = marginPx - vp.pageTop(index) * vp.scale
        viewportChanged()
    }

    // ================= layout e vista =================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0 && h > 0) fit(w, h)
    }

    private fun fit(w: Int, h: Int) {
        val zoom = if (needsFit) 1f else vp.scale / vp.fitScale
        val anchorDocY = vp.toDocY(0f)
        vp.fitScale = (w - 2 * marginPx) / vp.pageW
        vp.scale = vp.fitScale * zoom
        if (needsFit) {
            val p = startPage.coerceIn(0, maxOf(0, vp.pageCount - 1))
            val point = startPoint
            vp.offsetY = if (point != null) h / 2f - (vp.pageTop(p) + point.y) * vp.scale else marginPx - vp.pageTop(p) * vp.scale
            startPage = 0
            startPoint = null
            needsFit = false
        } else {
            vp.offsetY = -anchorDocY * vp.scale
        }
        vp.clamp(w, h, marginPx)
        pagesView.invalidate()
        selection.updateOverlay()
        reportPage()   // all'apertura su una pagina precisa il numero in alto deve essere giusto da subito
    }

    private fun viewportChanged() {
        vp.clamp(width, height, marginPx)
        pagesView.invalidate()
        selection.updateOverlay()
        reportPage()
        val z = zoomPercent()
        if (z != lastZoomReported) {
            lastZoomReported = z
            listener?.onZoomChanged(z)
        }
    }

    private fun reportPage() {
        val count = doc?.pages?.size ?: return
        val current = currentPageIndex()
        if (current != lastReportedPage) {
            lastReportedPage = current
            listener?.onPageChanged(current, count)
        }
    }

    private fun afterEdit(pages: Set<Int>) {
        val d = doc ?: return
        if (vp.pageCount != d.pages.size) {
            vp.pageCount = d.pages.size
            pagesView.invalidateAllPages()
            lastReportedPage = -1
        } else {
            pages.forEach(pagesView::invalidatePage)
        }
        viewportChanged()
        notifyChanged()
    }

    private fun notifyChanged() {
        listener?.onDocumentChanged()
        notifyUndo()
        val count = doc?.pages?.size ?: 0
        listener?.onPageChanged(currentPageIndex(), count)
    }

    private fun notifyUndo() = listener?.onUndoRedoChanged(undoStack.canUndo, undoStack.canRedo)

    /** Consegna dei tratti finiti: entrano nel modello e nella pagina, poi escono dal front buffer. */
    private fun handOff(strokes: Map<InProgressStrokeId, Stroke>) {
        val d = doc
        var changed = false
        for ((id, stroke) in strokes) {
            val p = pending.remove(id) ?: continue
            if (d == null || p.page >= d.pages.size) continue
            // curve lisce anche al 600%: input infittiti con una spline prima di salvare il tratto
            val dense = densifyInputs(stroke.inputs)
            val ns = NoteStroke(p.tool, if (dense === stroke.inputs) stroke else Stroke(stroke.brush, dense), p.variant)
            d.pages[p.page].strokes.add(ns)
            undoStack.push(Edit.AddStroke(p.page, ns))
            pagesView.invalidatePage(p.page)
            changed = true
        }
        inProgress.removeFinishedStrokes(strokes.keys)
        if (changed) {
            listener?.onDocumentChanged()
            notifyUndo()
        }
    }

    // ================= input =================

    override fun onInterceptTouchEvent(ev: MotionEvent) = true

    override fun onInterceptHoverEvent(event: MotionEvent) = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (doc == null) return true
        when (ev.actionMasked) {
            // Samsung: con il tasto della S Pen premuto arrivano azioni proprietarie.
            // Il tasto è disattivato (richiesta di Caro): la penna continua a fare lo strumento attivo.
            SPEN_ACTION_DOWN -> onPenDown(ev, ev.actionIndex, forceEraser = false)
            SPEN_ACTION_MOVE -> onPenMove(ev)
            SPEN_ACTION_UP -> onPenUp(ev, canceled = false)

            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                    // Nuova sequenza: nessun gesto precedente può essere ancora vivo.
                    cancelPen(null)
                    cancelFingers()
                }
                val idx = ev.actionIndex
                if (isPen(ev, idx)) {
                    // il tasto laterale non fa nulla: gomma solo con la punta-gomma (se esiste)
                    val eraser = ev.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER
                    onPenDown(ev, idx, eraser)
                } else {
                    onFingerDown(ev, idx)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (penPointerId != INVALID) onPenMove(ev)
                if (fingerCount > 0) onFingerMove(ev)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = ev.actionIndex
                val canceled = (ev.flags and MotionEvent.FLAG_CANCELED) != 0
                if (ev.getPointerId(idx) == penPointerId) onPenUp(ev, canceled) else onFingerUp(ev, idx, canceled)
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPen(ev)
                cancelFingers()
            }
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (!isPen(event, 0)) return super.onHoverEvent(event)
        lastStylusTime = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                val erasing = tool.kind == ToolKind.ERASER
                hoverView.show(event.x, event.y, cursorRadiusPx(erasing))
            }
            MotionEvent.ACTION_HOVER_EXIT -> hoverView.hide()
        }
        return true
    }

    private fun cursorRadiusPx(erasing: Boolean): Float =
        if (erasing) eraserSizePt / 2f * vp.scale else effectiveSizePt() / 2f * vp.scale

    /** Spessore che avrà il prossimo tratto (tiene conto dello zoom se richiesto). */
    private fun effectiveSizePt(): Float {
        if (!sizeFollowsZoom || !tool.kind.isPen) return tool.sizePt
        val base = Brushes.levelForSize(tool.kind, tool.sizePt)
        return Brushes.sizeForLevel(tool.kind, Brushes.levelForZoom(base, zoomPercent()))
    }

    private fun effectiveBrush(): Brush {
        val size = effectiveSizePt()
        return if (size == tool.sizePt) brush else Brushes.create(tool.kind, tool.color, size, penVariant)
    }

    private fun isPen(ev: MotionEvent, idx: Int): Boolean {
        val t = ev.getToolType(idx)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    // ---------- penna ----------

    private fun onPenDown(ev: MotionEvent, idx: Int, forceEraser: Boolean, asFinger: Boolean = false) {
        if (penPointerId != INVALID) return
        if (!asFinger) {
            lastStylusTime = ev.eventTime
            cancelFingers()   // la penna ha sempre la precedenza sulle dita
        }
        scroller.forceFinished(true)
        val pid = ev.getPointerId(idx)
        penPointerId = pid
        penIsFinger = asFinger
        penStartTime = ev.eventTime
        requestUnbufferedDispatch(ev)
        hoverView.hide()

        val x = ev.getX(idx)
        val y = ev.getY(idx)
        if (!forceEraser && tool.kind == ToolKind.LASSO) {
            penLasso = true
            selection.down(x, y)
            return
        }
        penErasing = forceEraser || tool.kind == ToolKind.ERASER
        if (penErasing) {
            eraseRemovals.clear()
            eraseLastX = x
            eraseLastY = y
            eraseSegment(x, y, x, y)
            return
        }
        val page = vp.pageAt(x, y)
        if (page < 0) return   // fuori dal foglio: ignora fino al sollevamento
        strokeBrush = effectiveBrush()
        if (lineMode) {
            lineActive = true
            linePage = page
            lineSx = x
            lineSy = y
            return
        }
        predictor.record(ev)
        val toPage = vp.screenToPage(page, Matrix())
        val id = inProgress.startStroke(ev, pid, strokeBrush, toPage)
        penStrokeId = id
        pending[id] = Pending(page, tool.kind, penVariant)
        shapePage = page
        shapeCount = 0
        shapeSnapped = false
        addShapePoint(x, y)
        holdX = x
        holdY = y
        removeCallbacks(holdRunnable)
        if (shapeRecognition) postDelayed(holdRunnable, HOLD_MS)
    }

    private fun onPenMove(ev: MotionEvent) {
        val idx = ev.findPointerIndex(penPointerId)
        if (idx < 0) return
        if (!penIsFinger) lastStylusTime = ev.eventTime
        if (penLasso) {
            for (h in 0 until ev.historySize) selection.move(ev.getHistoricalX(idx, h), ev.getHistoricalY(idx, h))
            selection.move(ev.getX(idx), ev.getY(idx))
            return
        }
        if (penErasing) {
            for (h in 0 until ev.historySize) {
                val hx = ev.getHistoricalX(idx, h)
                val hy = ev.getHistoricalY(idx, h)
                eraseSegment(eraseLastX, eraseLastY, hx, hy)
                eraseLastX = hx
                eraseLastY = hy
            }
            val x = ev.getX(idx)
            val y = ev.getY(idx)
            eraseSegment(eraseLastX, eraseLastY, x, y)
            eraseLastX = x
            eraseLastY = y
            hoverView.show(x, y, cursorRadiusPx(true))
            return
        }
        if (lineActive) {
            val (ex, ey) = lineEnd(ev.getX(idx), ev.getY(idx))
            hoverView.showLine(lineSx, lineSy, ex, ey, strokeBrush.size * vp.scale, tool.color)
            return
        }
        val id = penStrokeId ?: return
        trackShape(ev, idx)
        predictor.record(ev)
        val predicted = predictor.predict()
        try {
            inProgress.addToStroke(ev, penPointerId, id, predicted)
        } finally {
            predicted?.recycle()
        }
    }

    private fun onPenUp(ev: MotionEvent, canceled: Boolean) {
        removeCallbacks(holdRunnable)
        if (!penIsFinger) lastStylusTime = ev.eventTime
        if (penLasso) {
            if (canceled) selection.cancel() else selection.up()
        } else if (penErasing) {
            finishErase()
            hoverView.hide()
        } else if (lineActive) {
            hoverView.hideLine()
            val idx = ev.findPointerIndex(penPointerId)
            if (!canceled && idx >= 0) commitLine(ev.getX(idx), ev.getY(idx))
        } else if (shapeMode && !canceled && penStrokeId != null && snapToShape()) {
            // forma riconosciuta: il tratto a mano è già stato sostituito
        } else {
            penStrokeId?.let { id ->
                if (canceled) {
                    inProgress.cancelStroke(id, ev)
                    pending.remove(id)
                } else {
                    predictor.record(ev)
                    inProgress.finishStroke(ev, penPointerId, id)
                }
            }
        }
        resetPen()
    }

    private fun cancelPen(ev: MotionEvent?) {
        removeCallbacks(holdRunnable)
        if (penPointerId == INVALID) return
        if (penLasso) {
            selection.cancel()
        } else if (lineActive) {
            hoverView.hideLine()
        } else if (penErasing) {
            finishErase()
        } else {
            penStrokeId?.let { id ->
                inProgress.cancelStroke(id, ev)
                pending.remove(id)
            }
        }
        hoverView.hide()
        resetPen()
    }

    private fun resetPen() {
        penPointerId = INVALID
        penStrokeId = null
        penErasing = false
        penLasso = false
        lineActive = false
        penIsFinger = false
    }

    // ---------- forme: disegna e tieni premuto ----------

    private fun addShapePoint(sx: Float, sy: Float) {
        if (shapeCount + 2 > shapePts.size) shapePts = shapePts.copyOf(shapePts.size * 2)
        shapePts[shapeCount++] = vp.toDocX(sx)
        shapePts[shapeCount++] = vp.toDocY(sy) - vp.pageTop(shapePage)
    }

    private fun trackShape(ev: MotionEvent, idx: Int) {
        for (h in 0 until ev.historySize) addShapePoint(ev.getHistoricalX(idx, h), ev.getHistoricalY(idx, h))
        val x = ev.getX(idx)
        val y = ev.getY(idx)
        addShapePoint(x, y)
        // la penna si è mossa: il conto alla rovescia del "tieni premuto" riparte
        if (shapeRecognition && hypot(x - holdX, y - holdY) > holdSlop) {
            holdX = x
            holdY = y
            removeCallbacks(holdRunnable)
            postDelayed(holdRunnable, HOLD_MS)
        }
    }

    /** Penna ferma da HOLD_MS: se il tratto somiglia a una forma, lo sostituisce con quella pulita. */
    private fun tryRecognizeShape() {
        snapToShape()
    }

    /** Sostituisce il tratto in corso con la forma riconosciuta; false se non somiglia a nessuna forma. */
    private fun snapToShape(): Boolean {
        val d = doc ?: return false
        val id = penStrokeId ?: return false
        if (shapeSnapped || penErasing || penLasso || shapePage !in d.pages.indices) return false
        val shape = ShapeRecognizer.recognize(shapePts.copyOf(shapeCount)) ?: return false
        val p = pending.remove(id) ?: return false
        inProgress.cancelStroke(id, null)
        penStrokeId = null
        shapeSnapped = true
        val ns = NoteStroke(p.tool, ShapeRecognizer.toStroke(shape, strokeBrush), p.variant)
        d.pages[shapePage].strokes.add(ns)
        undoStack.push(Edit.AddStroke(shapePage, ns))
        pagesView.invalidatePage(shapePage)
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        listener?.onDocumentChanged()
        notifyUndo()
        return true
    }

    /** Fine della linea dritta, agganciata a 0/45/90° (coordinate schermo). */
    private fun lineEnd(x: Float, y: Float): Pair<Float, Float> {
        val l = ShapeRecognizer.snapLine(lineSx, lineSy, x, y, snap45 = true)
        return l.x1 to l.y1
    }

    private fun commitLine(x: Float, y: Float) {
        val d = doc ?: return
        if (linePage !in d.pages.indices) return
        val (ex, ey) = lineEnd(x, y)
        if (kotlin.math.hypot(ex - lineSx, ey - lineSy) < 2f) return
        val top = vp.pageTop(linePage)
        val shape = Shape.Line(vp.toDocX(lineSx), vp.toDocY(lineSy) - top, vp.toDocX(ex), vp.toDocY(ey) - top)
        val ns = NoteStroke(tool.kind, ShapeRecognizer.toStroke(shape, strokeBrush), penVariant)
        d.pages[linePage].strokes.add(ns)
        undoStack.push(Edit.AddStroke(linePage, ns))
        pagesView.invalidatePage(linePage)
        listener?.onDocumentChanged()
        notifyUndo()
    }

    // ---------- gomma (cancella tratti interi) ----------

    private fun eraseSegment(x0: Float, y0: Float, x1: Float, y1: Float) {
        val d = doc ?: return
        val page = vp.pageAt(x1, y1).let { if (it < 0) vp.nearestPage(y1) else it }
        val top = vp.pageTop(page)
        val ax = vp.toDocX(x0)
        val ay = vp.toDocY(y0) - top
        val bx = vp.toDocX(x1)
        val by = vp.toDocY(y1) - top
        val r = eraserSizePt / 2f
        val minX = min(ax, bx) - r
        val minY = min(ay, by) - r
        val maxX = max(ax, bx) + r
        val maxY = max(ay, by) + r

        val strokes = d.pages[page].strokes
        var degenerateBox: ImmutableBox? = null
        var parallelogram: ImmutableParallelogram? = null
        val degenerate = abs(bx - ax) + abs(by - ay) < 0.01f
        var i = strokes.size - 1
        while (i >= 0) {
            val ns = strokes[i]
            val b = boundsOf(ns)
            if (b != null && b.right >= minX && b.left <= maxX && b.bottom >= minY && b.top <= maxY) {
                val hit = if (degenerate) {
                    val box = degenerateBox
                        ?: ImmutableBox.fromCenterAndDimensions(ImmutableVec(bx, by), 2 * r, 2 * r)
                            .also { degenerateBox = it }
                    box.intersects(ns.stroke.shape, AffineTransform.IDENTITY)
                } else {
                    val pg = parallelogram
                        ?: ImmutableParallelogram.fromSegmentAndPadding(
                            ImmutableSegment(ImmutableVec(ax, ay), ImmutableVec(bx, by)), r,
                        ).also { parallelogram = it }
                    pg.intersects(ns.stroke.shape, AffineTransform.IDENTITY)
                }
                if (hit && eraserPartial) {
                    val pieces = splitByEraser(ns, ax, ay, bx, by, r)
                    if (pieces != null) {
                        eraseSnapshots.getOrPut(page) { ArrayList(strokes) }
                        strokes.removeAt(i)
                        strokes.addAll(i, pieces)
                        pagesView.invalidatePage(page)
                    }
                } else if (hit) {
                    strokes.removeAt(i)
                    eraseRemovals.add(Removal(page, i, ns))
                    pagesView.invalidatePage(page)
                }
            }
            i--
        }
    }

    private fun boundsOf(ns: NoteStroke): RectF? {
        boundsCache[ns]?.let { return it }
        val box = ns.stroke.shape.computeBoundingBox() ?: return null
        return RectF(box.xMin, box.yMin, box.xMax, box.yMax).also { boundsCache[ns] = it }
    }

    private fun finishErase() {
        val d = doc
        if (eraseSnapshots.isNotEmpty() && d != null) {
            val changes = eraseSnapshots.mapValues { (page, before) -> before to ArrayList(d.pages[page].strokes) }
            eraseSnapshots.clear()
            undoStack.push(Edit.PageStrokes(changes))
            listener?.onDocumentChanged()
            notifyUndo()
        }
        if (eraseRemovals.isNotEmpty()) {
            undoStack.push(Edit.RemoveStrokes(ArrayList(eraseRemovals)))
            eraseRemovals.clear()
            listener?.onDocumentChanged()
            notifyUndo()
        }
    }

    // ---------- dita ----------

    private fun onFingerDown(ev: MotionEvent, idx: Int) {
        val pid = ev.getPointerId(idx)
        if (fingerDrawing) {
            if (penPointerId == INVALID && fingerCount == 0) {
                onPenDown(ev, idx, forceEraser = false, asFinger = true)
                return
            }
            if (penIsFinger) {
                // Secondo dito subito dopo il primo: era un pinch, non un tratto.
                if (ev.eventTime - penStartTime < FINGER_TO_PINCH_MS) {
                    val first = penPointerId
                    firstFingerTime = penStartTime
                    cancelPen(ev)
                    addFinger(first)
                    addFinger(pid)
                    beginFingerGesture(ev)
                }
                return
            }
        }
        // Palm rejection: penna attiva o appena usata, palmo segnalato o contatto enorme.
        if (penPointerId != INVALID && !penIsFinger) return
        if (ev.eventTime - lastStylusTime < PALM_GUARD_MS) return
        if (ev.getTouchMajor(idx) > palmMajorPx) return
        if (fingerCount >= 2) return
        if (fingerCount == 0) {
            firstFingerTime = ev.eventTime
            longPressX = ev.getX(idx)
            longPressY = ev.getY(idx)
            fingerTapPossible = true
            removeCallbacks(longPressRunnable)
            postDelayed(longPressRunnable, LONG_PRESS_MS)
        } else {
            removeCallbacks(longPressRunnable)
            fingerTapPossible = false
        }
        addFinger(pid)
        beginFingerGesture(ev)
    }

    private fun addFinger(pid: Int) {
        if (fingerCount < 2 && fingerIds[0] != pid) fingerIds[fingerCount++] = pid
    }

    private fun beginFingerGesture(ev: MotionEvent) {
        scroller.forceFinished(true)
        if (fingerCount == 1) {
            val i = ev.findPointerIndex(fingerIds[0])
            if (i < 0) return
            lastFx = ev.getX(i)
            lastFy = ev.getY(i)
            velocityTracker = (velocityTracker ?: VelocityTracker.obtain()).also {
                it.clear()
                it.addMovement(ev)
            }
            pagesView.zooming = false
        } else if (fingerCount == 2) {
            val i0 = ev.findPointerIndex(fingerIds[0])
            val i1 = ev.findPointerIndex(fingerIds[1])
            if (i0 < 0 || i1 < 0) return
            val fx = (ev.getX(i0) + ev.getX(i1)) / 2f
            val fy = (ev.getY(i0) + ev.getY(i1)) / 2f
            pinchStartSpan = hypot(ev.getX(i0) - ev.getX(i1), ev.getY(i0) - ev.getY(i1)).coerceAtLeast(1f)
            pinchStartScale = vp.scale
            pinchDocX = vp.toDocX(fx)
            pinchDocY = vp.toDocY(fy)
            pagesView.zooming = true
            // due dita appoggiate quasi insieme: potrebbe essere il tocco "annulla"
            tapCandidate = ev.eventTime - firstFingerTime < 200
            tapStartTime = ev.eventTime
            tapStartFx = fx
            tapStartFy = fy
            tapStartSpan = pinchStartSpan
            tapRestore[0] = vp.scale; tapRestore[1] = vp.offsetX; tapRestore[2] = vp.offsetY
        }
    }

    private fun onFingerMove(ev: MotionEvent) {
        if (fingerCount == 1) {
            val i = ev.findPointerIndex(fingerIds[0])
            if (i < 0) return
            val x = ev.getX(i)
            val y = ev.getY(i)
            if (hypot(x - longPressX, y - longPressY) > tapSlop) {
                removeCallbacks(longPressRunnable)
                fingerTapPossible = false
            }
            vp.offsetX += x - lastFx
            vp.offsetY += y - lastFy
            lastFx = x
            lastFy = y
            if (tapCandidate && ev.eventTime - tapStartTime > TWO_FINGER_TAP_MS) tapCandidate = false
            velocityTracker?.addMovement(ev)
            viewportChanged()
        } else if (fingerCount == 2) {
            val i0 = ev.findPointerIndex(fingerIds[0])
            val i1 = ev.findPointerIndex(fingerIds[1])
            if (i0 < 0 || i1 < 0) return
            val fx = (ev.getX(i0) + ev.getX(i1)) / 2f
            val fy = (ev.getY(i0) + ev.getY(i1)) / 2f
            val span = hypot(ev.getX(i0) - ev.getX(i1), ev.getY(i0) - ev.getY(i1))
            if (tapCandidate && (abs(span - tapStartSpan) > tapSlop || hypot(fx - tapStartFx, fy - tapStartFy) > tapSlop)) {
                tapCandidate = false
            }
            val s = if (zoomLocked) vp.scale else (pinchStartScale * span / pinchStartSpan)
                .coerceIn(vp.fitScale * MIN_ZOOM, vp.fitScale * MAX_ZOOM)
            vp.scale = s
            vp.offsetX = fx - pinchDocX * s
            vp.offsetY = fy - pinchDocY * s
            viewportChanged()
            if (!zoomLocked) hoverView.showZoom(zoomPercent())
        }
    }

    private fun onFingerLongPress() {
        fingerTapPossible = false
        if (fingerCount != 1) return
        val page = vp.pageAt(longPressX, longPressY)
        if (page < 0) return
        val px = vp.toDocX(longPressX)
        val py = vp.toDocY(longPressY) - vp.pageTop(page)
        val d = doc ?: return
        if (d.pages[page].images.none { px >= it.x && px <= it.x + it.w && py >= it.y && py <= it.y + it.h }) return
        cancelFingers()
        listener?.onRequestTool(ToolKind.LASSO)   // la UI passa al lasso (questo azzera la selezione)…
        selection.selectAt(page, px, py)            // …poi si seleziona l'immagine
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun onFingerUp(ev: MotionEvent, idx: Int, canceled: Boolean) {
        removeCallbacks(longPressRunnable)
        val pid = ev.getPointerId(idx)
        val slot = fingerIds.indexOf(pid)
        if (slot < 0 || slot >= fingerCount) return
        if (canceled) {
            cancelFingers()
            return
        }
        val wasPinch = fingerCount == 2
        // rimuove il dito sollevato
        if (slot == 0) fingerIds[0] = fingerIds[1]
        fingerIds[1] = INVALID
        fingerCount--
        if (fingerCount == 1) {
            if (wasPinch) hoverView.hideZoomSoon()
            beginFingerGesture(ev)   // da pinch a pan con il dito rimasto
        } else if (fingerCount == 0) {
            if (tapCandidate && ev.eventTime - tapStartTime < TWO_FINGER_TAP_MS) {
                tapCandidate = false
                // annulla l'eventuale micro-zoom del tocco, poi "annulla"
                vp.scale = tapRestore[0]; vp.offsetX = tapRestore[1]; vp.offsetY = tapRestore[2]
                pagesView.zooming = false
                viewportChanged()
                hoverView.hideZoomSoon()
                undo()
                return
            }
            tapCandidate = false
            if (!wasPinch && fingerTapPossible && ev.eventTime - firstFingerTime < FINGER_TAP_MS) {
                fingerTapPossible = false
                pagesView.zooming = false
                fingerTap(ev.getX(idx), ev.getY(idx))
                return
            }
            fingerTapPossible = false
            val vt = velocityTracker
            if (!wasPinch && vt != null) {
                vt.addMovement(ev)
                vt.computeCurrentVelocity(1000, maxFling)
                val vx = vt.getXVelocity(pid)
                val vy = vt.getYVelocity(pid)
                if (hypot(vx, vy) > minFling) fling(vx, vy)
            }
            pagesView.zooming = false
            pagesView.invalidate()
            hoverView.hideZoomSoon()
        }
    }

    /** 100% = pagina adattata alla larghezza dello schermo. */
    fun zoomPercent(): Int = (vp.scale / vp.fitScale * 100f).roundToInt()

    /** Zoom rapido mantenendo fermo il centro dello schermo. */
    fun setZoomPercent(percent: Int) {
        if (width == 0) return
        scroller.forceFinished(true)
        val cx = width / 2f
        val cy = height / 2f
        val dx = vp.toDocX(cx)
        val dy = vp.toDocY(cy)
        vp.scale = vp.fitScale * (percent / 100f).coerceIn(MIN_ZOOM, MAX_ZOOM)
        vp.offsetX = cx - dx * vp.scale
        vp.offsetY = cy - dy * vp.scale
        pagesView.zooming = false
        viewportChanged()
        hoverView.showZoom(zoomPercent())
        hoverView.hideZoomSoon()
    }

    private fun cancelFingers() {
        tapCandidate = false
        removeCallbacks(longPressRunnable)
        if (fingerCount == 0) return
        fingerIds[0] = INVALID
        fingerIds[1] = INVALID
        fingerCount = 0
        pagesView.zooming = false
        pagesView.invalidate()
    }

    private fun fling(vx: Float, vy: Float) {
        val contentW = vp.pageW * vp.scale
        val contentH = vp.docHeight * vp.scale
        val minX = min(width - contentW - marginPx, vp.offsetX).toInt()
        val maxX = max(marginPx, vp.offsetX).toInt()
        val minY = min(height - contentH - marginPx * 4, marginPx).toInt()
        val maxY = marginPx.toInt()
        scroller.fling(vp.offsetX.toInt(), vp.offsetY.toInt(), vx.toInt(), vy.toInt(), minX, maxX, minY, maxY)
        postOnAnimation(flingRunnable)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(flingRunnable)
        velocityTracker?.recycle()
        velocityTracker = null
    }

    private companion object {
        const val INVALID = -1
        const val FINGER_TAP_MS = 350L
        const val SPEN_ACTION_DOWN = 211
        const val SPEN_ACTION_UP = 212
        const val SPEN_ACTION_MOVE = 213
        const val PALM_GUARD_MS = 400L
        const val FINGER_TO_PINCH_MS = 250L
        const val TWO_FINGER_TAP_MS = 320L
        const val HOLD_MS = 650L
        const val LONG_PRESS_MS = 450L
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 10f   // 1000%
    }
}
