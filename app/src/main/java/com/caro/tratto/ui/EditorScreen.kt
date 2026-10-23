package com.caro.tratto.ui

import android.content.ClipboardManager
import android.graphics.RectF
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.os.SystemClock
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.caro.tratto.data.ImageLoader
import com.caro.tratto.data.NotebookRepository
import com.caro.tratto.data.PdfPages
import com.caro.tratto.data.RecentNotebooks
import com.caro.tratto.editor.EditorListener
import com.caro.tratto.editor.EditorView
import com.caro.tratto.editor.NoteClipboard
import com.caro.tratto.export.Exporter
import com.caro.tratto.ink.Brushes
import com.caro.tratto.ink.PenFeel
import com.caro.tratto.export.PageRenderer
import com.caro.tratto.model.NotebookDocument
import com.caro.tratto.model.PageModel
import com.caro.tratto.model.ToolKind
import com.caro.tratto.model.ToolSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.graphics.PointF
import com.caro.tratto.model.PageLink
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.layout.offset
import kotlin.math.roundToInt

internal enum class ExportKind(val label: String) {
    PDF_ALL("PDF – tutto il quaderno"),
    PDF_PAGE("PDF – pagina corrente"),
    PDF_CHOSEN("PDF – pagine scelte…"),
    PNG_PAGE("PNG – pagina corrente"),
    PNG_ALL("PNG – tutte le pagine"),
}

@Composable
fun EditorScreen(
    repository: NotebookRepository,
    notebookId: String,
    onBack: () -> Unit,
    startPage: Int = 0,
    /** Arrivo da un collegamento: pagina (per id, se c'è ancora) e punto da mettere al centro. */
    startPageId: String? = null,
    startPoint: PointF? = null,
    /** Quaderno da cui si è arrivati seguendo un collegamento: mostra "← Torna". */
    backTo: String? = null,
    onLinkBack: () -> Unit = {},
    /** Segue un collegamento verso un altro quaderno (dalla pagina [fromPage] di questo). */
    onOpenLink: (fromPage: Int, notebookId: String, pageId: String, pageIndex: Int, point: PointF?) -> Unit = { _, _, _, _, _ -> },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ToolPrefs(context) }
    val snackbar = remember { SnackbarHostState() }

    var doc by remember { mutableStateOf<NotebookDocument?>(null) }
    var editor by remember { mutableStateOf<EditorView?>(null) }
    var canUndo by remember { mutableStateOf(false) }
    var canRedo by remember { mutableStateOf(false) }
    var page by remember { mutableIntStateOf(0) }
    var pageCount by remember { mutableIntStateOf(1) }
    var exporting by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(false) }
    var fingerDrawing by remember { mutableStateOf(prefs.fingerDrawing) }
    // schermo intero: via la barra di stato (ora, batteria) e quella in basso mentre si scrive; si rivedono scorrendo dal bordo
    var fullscreen by remember { mutableStateOf(prefs.fullscreen) }
    val activity = context as? android.app.Activity
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(fullscreen, lifecycle) {
        val window = activity?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        val types = androidx.core.view.WindowInsetsCompat.Type.systemBars()
        fun apply() {
            if (bars == null) return
            if (fullscreen) {
                bars.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                bars.hide(types)
            } else {
                bars.show(types)
            }
        }
        apply()
        // tornando nell'app (multitasking, schermo spento) le barre possono riapparire: si nascondono di nuovo
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) apply()
        }
        lifecycle.addObserver(observer)
        // uscendo dal foglio le barre tornano sempre
        onDispose {
            lifecycle.removeObserver(observer)
            bars?.show(types)
        }
    }
    var eraserPartial by remember { mutableStateOf(prefs.eraserPartial) }
    var penPressure by remember { mutableIntStateOf(prefs.penPressure) }
    var penStabilization by remember { mutableIntStateOf(prefs.penStabilization) }
    var shapeRecognition by remember { mutableStateOf(prefs.shapeRecognition) }
    var smoothEdges by remember { mutableStateOf(prefs.smoothEdges) }
    var shapeMode by remember { mutableStateOf(prefs.shapeMode) }
    var lineMode by remember { mutableStateOf(prefs.lineMode) }
    var zoomLocked by remember { mutableStateOf(prefs.zoomLocked) }
    var activeTool by remember { mutableStateOf(prefs.lastTool) }
    var penKind by remember { mutableStateOf(prefs.lastPen) }
    var pickerFor by remember { mutableStateOf<ToolKind?>(null) }
    var selRect by remember { mutableStateOf<RectF?>(null) }
    var area by remember { mutableStateOf(IntSize.Zero) }
    // collegamenti: zona del lasso da collegare, collegamento toccato (fumetto), ritorni dentro il quaderno
    var linkSource by remember { mutableStateOf<Pair<Int, RectF>?>(null) }
    var tappedLink by remember { mutableStateOf<Triple<Int, PageLink, Offset>?>(null) }
    val localBack = remember { mutableStateListOf<Pair<Int, FloatArray>>() }
    var titles by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var confirmDeletePage by remember { mutableStateOf(false) }
    var showPages by remember { mutableStateOf(false) }
    var choosePages by remember { mutableStateOf(false) }
    var pagesVersion by remember { mutableIntStateOf(0) }
    // cronologia delle pagine recenti: cresce a ogni modifica, per rifare la miniatura
    var editVersion by remember { mutableIntStateOf(0) }
    // segnalibri: pannello aperto e indice del PDF (letto una volta, alla prima apertura del pannello)
    var showBookmarks by remember { mutableStateOf(false) }
    var pdfOutline by remember { mutableStateOf<List<com.caro.tratto.data.PdfOutline.Item>?>(null) }
    var outlineLoading by remember { mutableStateOf(false) }
    val tools = remember { mutableStateMapOf<ToolKind, ToolSettings>().apply { ToolKind.entries.forEach { put(it, prefs.load(it)) } } }
    val customColors = remember { mutableStateListOf<Int>().apply { addAll(prefs.customColors) } }
    val favorites = remember { mutableStateListOf<ToolSettings>().apply { addAll(prefs.favorites) } }
    val quickColors = remember { mutableStateListOf<Int>().apply { addAll(prefs.quickColors) } }
    val quickSizes = remember { mutableStateListOf<Int>().apply { addAll(prefs.quickSizes) } }
    var quickColorCount by remember { mutableIntStateOf(prefs.quickColorCount) }
    var quickSizeCount by remember { mutableIntStateOf(prefs.quickSizeCount) }
    var showAppSettings by remember { mutableStateOf(false) }
    // tag del quaderno (dal menu ⋮): elenco dei tag e tag da modificare
    var tagList by remember { mutableStateOf<List<com.caro.tratto.model.Tag>?>(null) }
    var noteTags by remember { mutableStateOf<List<String>>(emptyList()) }
    var editingTag by remember { mutableStateOf<com.caro.tratto.model.Tag?>(null) }
    var favorite by remember { mutableStateOf(false) }
    var showFavoritesBar by remember { mutableStateOf(prefs.showFavoritesBar) }
    var sizeFollowsZoom by remember { mutableStateOf(prefs.sizeFollowsZoom) }
    var zoomPct by remember { mutableIntStateOf(100) }
    val recentInk = remember { mutableStateListOf(0xFF111111.toInt(), 0xFF1E63D6.toInt(), 0xFFD32F2F.toInt()) }
    val imageLoader = remember(notebookId) { ImageLoader({ repository.imageFile(notebookId, it) }) }
    // quaderno nato da un PDF: le sue pagine si rendono in background mentre si scorre
    val pdfPages = remember(notebookId) { repository.pdfFile(notebookId).takeIf { it.exists() }?.let { PdfPages(it) } }
    DisposableEffect(pdfPages) { onDispose { pdfPages?.close() } }

    // ---------- salvataggio automatico ----------
    // 0,8 s dopo l'ultima modifica, al più ogni 5 s se si scrive senza pause, e subito quando l'app
    // esce dallo schermo (Home, app recenti, "Chiudi tutto"): al massimo si perde l'ultimo istante.
    var dirty by remember { mutableStateOf(false) }
    var dirtySince by remember { mutableLongStateOf(0L) }
    var saveJob by remember { mutableStateOf<Job?>(null) }

    fun saveNow() {
        val d = doc ?: return
        if (!dirty) return
        dirty = false
        saveJob?.cancel()
        val snap = d.snapshot()   // copia sul main thread: l'editor continua a scrivere sull'originale
        AppIo.scope.launch {
            runCatching { repository.save(snap) }.onFailure { dirty = true }
        }
    }

    var selectToolRef: (ToolKind) -> Unit = {}
    val listener = remember {
        object : EditorListener {
            override fun onDocumentChanged() {
                editVersion++
                val now = SystemClock.uptimeMillis()
                if (!dirty) dirtySince = now
                dirty = true
                saveJob?.cancel()
                if (now - dirtySince > 5_000) {
                    saveNow()
                    return
                }
                saveJob = scope.launch {
                    delay(800)
                    saveNow()
                }
            }

            override fun onUndoRedoChanged(undo: Boolean, redo: Boolean) {
                canUndo = undo
                canRedo = redo
            }

            override fun onPageChanged(currentIndex: Int, count: Int) {
                page = currentIndex
                pageCount = count
            }

            override fun onSelectionChanged(bounds: RectF?) {
                selRect = bounds
            }

            override fun onZoomChanged(percent: Int) {
                zoomPct = percent
            }

            override fun onLinkTapped(page: Int, link: PageLink, x: Float, y: Float) {
                tappedLink = Triple(page, link, Offset(x, y))
                if (link.notebookId != null && titles.isEmpty()) {
                    scope.launch { titles = withContext(AppIo.serial) { repository.list().associate { it.id to it.title } } }
                }
            }

            override fun onRequestTool(kind: ToolKind) {
                selectToolRef(kind)
            }
        }
    }

    var loadProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(notebookId) {
        try {
            // il file si legge sulla coda IO (dopo l'eventuale ultimo salvataggio); la geometria dei
            // tratti, la parte lenta, su tutti i core: la libreria non resta bloccata e tornare
            // indietro durante l'apertura la annulla e basta (prima usciva "Impossibile aprire")
            val t0 = SystemClock.uptimeMillis()
            val raw = withContext(AppIo.serial) { repository.readRaw(notebookId) }
            val job = coroutineContext[Job]
            val loaded = withContext(Dispatchers.Default) {
                repository.build(raw, cancelled = { job?.isActive == false }) { loadProgress = it }
            }
            android.util.Log.i("Tratto", "quaderno aperto: ${raw.raw.strokeCount} tratti in ${SystemClock.uptimeMillis() - t0} ms")
            doc = loaded.doc
            favorite = loaded.doc.meta.favorite
            pageCount = loaded.doc.pages.size
            loaded.notice?.let { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Toast.makeText(context, "Impossibile aprire il quaderno (${e.javaClass.simpleName}: ${e.message})", Toast.LENGTH_LONG).show()
            onBack()
        }
    }

    // quaderni recenti: il quaderno va in cima con la pagina che si sta guardando (dopo un attimo,
    // così le pagine solo attraversate scorrendo non contano)
    val recent = remember { RecentNotebooks(context) }
    LaunchedEffect(doc, page, editVersion) {
        if (doc == null) return@LaunchedEffect
        delay(1500)
        val p = page
        AppIo.scope.launch { recent.record(notebookId, p) }
    }

    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { saveNow() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { saveNow() }
    DisposableEffect(Unit) { onDispose { saveNow() } }

    fun selectTool(kind: ToolKind) {
        activeTool = kind
        prefs.lastTool = kind
        if (kind.isPen) {
            penKind = kind
            prefs.lastPen = kind
        }
        editor?.setTool(tools.getValue(kind))
    }

    selectToolRef = ::selectTool

    fun updateTool(settings: ToolSettings) {
        tools[settings.kind] = settings
        prefs.save(settings)
        if (settings.kind == activeTool) editor?.setTool(settings)
        if (settings.kind.isPen) {
            recentInk.remove(settings.color)
            recentInk.add(0, settings.color)
            while (recentInk.size > 3) recentInk.removeAt(recentInk.lastIndex)
        }
    }

    /** Strumento a cui vanno colori e spessori rapidi: quello attivo se scrive, altrimenti l'ultima penna. */
    fun inkKind() = if (activeTool.isPen || activeTool == ToolKind.HIGHLIGHTER) activeTool else penKind

    fun applyQuickColor(color: Int) {
        // evidenziatore attivo: stesso colore ma trasparente; altrimenti la penna
        val kind = inkKind()
        val c = if (kind == ToolKind.HIGHLIGHTER) (color and 0x00FFFFFF) or 0x66000000 else color
        updateTool(tools.getValue(kind).copy(color = c))
        selectTool(kind)
    }

    fun applyQuickSize(level: Int) {
        val kind = inkKind()
        updateTool(tools.getValue(kind).copy(sizePt = Brushes.sizeForLevel(kind, level)))
        selectTool(kind)
    }

    fun addCustomColor(color: Int) {
        customColors.remove(color)
        customColors.add(0, color)
        while (customColors.size > 16) customColors.removeAt(customColors.lastIndex)
        prefs.customColors = customColors.toList()
    }

    /** Immagine (galleria o appunti di Android) → copiata nel quaderno → inserita e selezionata col lasso. */
    fun importAndInsert(uri: Uri) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { repository.importImage(context, notebookId, uri) }
            if (r == null) {
                snackbar.showSnackbar("Impossibile leggere l'immagine")
                return@launch
            }
            selectTool(ToolKind.LASSO)
            editor?.insertImage(r.first, r.second, r.third)
        }
    }

    fun paste() {
        if (!NoteClipboard.isEmpty) {
            val from = NoteClipboard.notebookId
            scope.launch {
                // immagini da un altro quaderno: si copiano i file in questo
                val renamed = if (from != null && from != notebookId) {
                    withContext(Dispatchers.IO) {
                        NoteClipboard.images.associate { it.file to (repository.copyImage(from, notebookId, it.file) ?: it.file) }
                    }
                } else {
                    emptyMap()
                }
                selectTool(ToolKind.LASSO)
                editor?.paste(renamed)
            }
            return
        }
        // niente negli appunti interni: proviamo un'immagine copiata da un'altra app
        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
        val uri = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
        if (uri != null) importAndInsert(uri) else scope.launch { snackbar.showSnackbar("Niente da incollare") }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importAndInsert(uri)
    }

    // un collegamento si segue dentro il quaderno (ricordando dove si era) o aprendo l'altro quaderno
    fun followLink(link: PageLink) {
        val d = doc ?: return
        val v = editor ?: return
        if (link.notebookId == null) {
            val i = if (link.wholeNotebook) 0
            else d.pages.indexOfFirst { it.id == link.pageId }.takeIf { it >= 0 } ?: link.pageIndex.coerceIn(0, d.pages.lastIndex)
            localBack.add(page to v.viewState())
            if (link.hasPoint) v.scrollToPoint(i, link.pointX, link.pointY) else v.scrollToPage(i)
        } else {
            saveNow()
            onOpenLink(page, link.notebookId, link.pageId, link.pageIndex.coerceAtLeast(0), if (link.hasPoint) PointF(link.pointX, link.pointY) else null)
        }
    }

    fun export(kind: ExportKind, chosen: List<Int> = emptyList()) {
        val d = doc ?: return
        if (kind == ExportKind.PDF_CHOSEN && chosen.isEmpty()) {
            // prima si scelgono le pagine nella griglia, poi si torna qui con l'elenco
            pagesVersion++
            choosePages = true
            showPages = true
            return
        }
        val current = editor?.currentPageIndex() ?: 0
        val snap = d.snapshot()
        exporting = true
        scope.launch {
            try {
                val (files, mime) = withContext(Dispatchers.IO) {
                    when (kind) {
                        ExportKind.PDF_ALL -> listOf(Exporter.exportPdf(context, snap)) to "application/pdf"
                        ExportKind.PDF_PAGE -> listOf(Exporter.exportPdf(context, snap, listOf(current))) to "application/pdf"
                        ExportKind.PDF_CHOSEN -> listOf(Exporter.exportPdf(context, snap, chosen)) to "application/pdf"
                        ExportKind.PNG_PAGE -> listOf(Exporter.exportPng(context, snap, current)) to "image/png"
                        ExportKind.PNG_ALL -> snap.pages.indices.map { Exporter.exportPng(context, snap, it) } to "image/png"
                    }
                }
                Exporter.share(context, files, mime)
            } catch (e: Exception) {
                snackbar.showSnackbar("Esportazione non riuscita: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                exporting = false
            }
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize()) {
            EditorToolbar(
                state = ToolbarState(
                    favorite = favorite,
                    canUndo = canUndo,
                    canRedo = canRedo,
                    activeTool = activeTool,
                    penKind = penKind,
                    tools = tools,
                    recentInk = recentInk,
                    customColors = customColors,
                    favorites = favorites,
                    page = page,
                    pageCount = pageCount,
                    pageBookmarked = editVersion >= 0 && doc?.pages?.getOrNull(page)?.bookmark != null,
                    fingerDrawing = fingerDrawing,
                    fullscreen = fullscreen,
                    eraserPartial = eraserPartial,
                    penPressure = penPressure,
                    penStabilization = penStabilization,
                    shapeRecognition = shapeRecognition,
                    smoothEdges = smoothEdges,
                    shapeMode = shapeMode,
                    lineMode = lineMode,
                    zoomLocked = zoomLocked,
                    quickColors = quickColors.take(quickColorCount),
                    quickSizes = quickSizes.take(quickSizeCount),
                    showFavoritesBar = showFavoritesBar,
                    sizeFollowsZoom = sizeFollowsZoom,
                    zoomPercent = zoomPct,
                ),
                actions = ToolbarActions(
                    onBack = { saveNow(); onBack() },
                    onUndo = { editor?.undo() },
                    onRedo = { editor?.redo() },
                    onSelectTool = ::selectTool,
                    onUpdateTool = ::updateTool,
                    onQuickColor = ::applyQuickColor,
                    onOpenPicker = { pickerFor = it },
                    onAddFavorite = { fav ->
                        if (favorites.none { it == fav } && favorites.size < 10) {
                            favorites.add(fav)
                            prefs.favorites = favorites.toList()
                        }
                    },
                    onApplyFavorite = { fav ->
                        updateTool(fav)
                        selectTool(fav.kind)
                    },
                    onRemoveFavorite = { fav ->
                        favorites.remove(fav)
                        prefs.favorites = favorites.toList()
                    },
                    onShowPages = { pagesVersion++; showPages = true },
                    onBookmarks = {
                        showBookmarks = true
                        val source = repository.pdfFile(notebookId)
                        if (pdfOutline == null && doc?.meta?.fromPdf == true && source.exists() && !outlineLoading) {
                            outlineLoading = true
                            scope.launch {
                                pdfOutline = withContext(Dispatchers.IO) {
                                    runCatching { com.caro.tratto.data.PdfOutline.read(context, source) }.getOrDefault(emptyList())
                                }
                                outlineLoading = false
                            }
                        }
                    },
                    onAddPage = { editor?.addPage() },
                    onPageSetup = { showSetup = true },
                    onInsertImage = {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onPaste = ::paste,
                    onInsertPage = { editor?.insertPageAfterCurrent() },
                    onDuplicatePage = { editor?.duplicateCurrentPage() },
                    onDeletePage = { confirmDeletePage = true },
                    onToggleFullscreen = {
                        fullscreen = !fullscreen
                        prefs.fullscreen = fullscreen
                    },
                    onToggleFinger = {
                        fingerDrawing = !fingerDrawing
                        prefs.fingerDrawing = fingerDrawing
                        editor?.fingerDrawing = fingerDrawing
                    },
                    onEraserPartial = { partial ->
                        eraserPartial = partial
                        prefs.eraserPartial = partial
                        editor?.eraserPartial = partial
                    },
                    onPenFeel = { p, st ->
                        penPressure = p
                        penStabilization = st
                        prefs.penPressure = p
                        prefs.penStabilization = st
                        editor?.penVariant = PenFeel.encode(p, st)
                    },
                    onZoom = { editor?.setZoomPercent(it) },
                    onAppSettings = { showAppSettings = true },
                    onTags = {
                        doc?.let { d ->
                            noteTags = d.meta.tags
                            scope.launch { tagList = withContext(AppIo.serial) { repository.tags() } }
                        }
                    },
                    onToggleFavorite = {
                        doc?.let { d ->
                            val fav = !favorite
                            favorite = fav
                            // anche i salvataggi successivi dell'editor riscrivono meta.json: va aggiornato qui
                            d.meta = d.meta.copy(favorite = fav)
                            AppIo.scope.launch { repository.setFavorite(notebookId, fav) }
                            scope.launch { snackbar.showSnackbar(if (fav) "Aggiunto ai preferiti" else "Tolto dai preferiti") }
                        }
                    },
                    onToggleShapeMode = {
                        shapeMode = !shapeMode
                        if (shapeMode) lineMode = false
                        prefs.shapeMode = shapeMode; prefs.lineMode = lineMode
                        editor?.shapeMode = shapeMode; editor?.lineMode = lineMode
                        if (shapeMode && !activeTool.isPen && activeTool != ToolKind.HIGHLIGHTER) selectTool(penKind)
                    },
                    onToggleLineMode = {
                        lineMode = !lineMode
                        if (lineMode) shapeMode = false
                        prefs.shapeMode = shapeMode; prefs.lineMode = lineMode
                        editor?.shapeMode = shapeMode; editor?.lineMode = lineMode
                        if (lineMode && !activeTool.isPen && activeTool != ToolKind.HIGHLIGHTER) selectTool(penKind)
                    },
                    onToggleSizeFollowsZoom = {
                        sizeFollowsZoom = !sizeFollowsZoom
                        prefs.sizeFollowsZoom = sizeFollowsZoom
                        editor?.sizeFollowsZoom = sizeFollowsZoom
                    },
                    onToggleFavoritesBar = {
                        showFavoritesBar = !showFavoritesBar
                        prefs.showFavoritesBar = showFavoritesBar
                    },
                    onSetQuickColor = { i, color ->
                        quickColors[i] = color or 0xFF000000.toInt()
                        prefs.quickColors = quickColors.toList()
                        applyQuickColor(color)
                    },
                    onCustomColor = ::addCustomColor,
                    onQuickSize = ::applyQuickSize,
                    onSetQuickSize = { i, level ->
                        quickSizes[i] = level
                        prefs.quickSizes = quickSizes.toList()
                        applyQuickSize(level)
                    },
                    onToggleZoomLock = {
                        zoomLocked = !zoomLocked
                        prefs.zoomLocked = zoomLocked
                        editor?.zoomLocked = zoomLocked
                        scope.launch { snackbar.showSnackbar(if (zoomLocked) "Zoom bloccato" else "Zoom sbloccato") }
                    },
                    onToggleSmoothEdges = {
                        smoothEdges = !smoothEdges
                        prefs.smoothEdges = smoothEdges
                        editor?.smoothEdges = smoothEdges
                    },
                    onToggleShapes = {
                        shapeRecognition = !shapeRecognition
                        prefs.shapeRecognition = shapeRecognition
                        editor?.shapeRecognition = shapeRecognition
                    },
                    onExport = ::export,
                ),
                modifier = Modifier.statusBarsPadding(),
            )
            val d = doc
            if (d == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (loadProgress > 0f) CircularProgressIndicator(progress = { loadProgress }) else CircularProgressIndicator()
                        Text("Apertura… ${(loadProgress * 100).toInt()}%", Modifier.padding(top = 12.dp))
                    }
                }
            } else {
                Box(Modifier.fillMaxSize().onSizeChanged { area = it }) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx ->
                            EditorView(ctx).also { v ->
                                v.listener = listener
                                v.notebookId = notebookId
                                v.imageProvider = { name -> imageLoader.getAsync(name) { v.onImagesReady() } }
                                v.pdfPages = pdfPages
                                v.fingerDrawing = fingerDrawing
                                v.eraserPartial = eraserPartial
                                v.penVariant = PenFeel.encode(penPressure, penStabilization)
                                v.shapeRecognition = shapeRecognition
                                v.smoothEdges = smoothEdges
                                v.shapeMode = shapeMode
                                v.lineMode = lineMode
                                v.zoomLocked = zoomLocked
                                v.sizeFollowsZoom = sizeFollowsZoom
                                v.setTool(tools.getValue(activeTool))
                                v.startPage = startPageId?.let { id -> d.pages.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: startPage
                                v.startPoint = startPoint
                                v.setDocument(d)
                                editor = v
                            }
                        },
                    )
                    if (showFavoritesBar) {
                        val inkKind = inkKind()
                        val current = tools.getValue(inkKind)
                        FavoritesSideBar(
                            favorites = favorites,
                            active = if (activeTool == inkKind) current else null,
                            canAdd = favorites.size < 10 && favorites.none { it == current },
                            onApply = { fav -> updateTool(fav); selectTool(fav.kind) },
                            onRemove = { fav -> favorites.remove(fav); prefs.favorites = favorites.toList() },
                            onAddCurrent = {
                                favorites.add(current)
                                prefs.favorites = favorites.toList()
                            },
                            modifier = Modifier.align(Alignment.CenterEnd).heightIn(max = 520.dp),
                        )
                    }
                    selRect?.let { r ->
                        SelectionBar(
                            rect = r,
                            area = area,
                            onRecolor = { editor?.recolorSelection(it) },
                            onDuplicate = { editor?.duplicateSelection() },
                            onCopy = {
                                if (editor?.copySelection() == true) scope.launch { snackbar.showSnackbar("Copiato: usa Incolla dal menu ⋮") }
                            },
                            onDelete = { editor?.deleteSelection() },
                            onDone = { editor?.clearSelection() },
                            onLink = { linkSource = editor?.selectionArea() },
                        )
                    }
                    // collegamento toccato col dito: dove porta, Apri / Togli
                    tappedLink?.let { (pg, link, at) ->
                        val d = doc
                        val label = when {
                            link.notebookId == null && link.wholeNotebook -> "inizio di questo quaderno"
                            link.notebookId == null -> {
                                val i = d?.pages?.indexOfFirst { it.id == link.pageId }?.takeIf { it >= 0 } ?: link.pageIndex
                                "pagina ${i + 1}"
                            }
                            else -> (titles[link.notebookId]?.let { "“$it”" } ?: "un altro quaderno") +
                                if (link.wholeNotebook) "" else " · pag. ${link.pageIndex + 1}"
                        }
                        Box(Modifier.fillMaxSize().clickable(interactionSource = null, indication = null) { tappedLink = null })
                        LinkBubble(
                            label,
                            onOpen = {
                                tappedLink = null
                                followLink(link)
                            },
                            onRemove = {
                                tappedLink = null
                                editor?.removeLink(pg, link)
                            },
                            modifier = Modifier.offset {
                                IntOffset(
                                    (at.x - 150.dp.toPx()).roundToInt().coerceIn(0, (area.width - 320.dp.toPx()).roundToInt().coerceAtLeast(0)),
                                    (at.y + 16.dp.toPx()).roundToInt().coerceIn(0, (area.height - 140.dp.toPx()).roundToInt().coerceAtLeast(0)),
                                )
                            },
                        )
                    }
                    // ritorno dopo aver seguito un collegamento
                    val backLabel = when {
                        localBack.isNotEmpty() -> "Torna a pag. ${localBack.last().first + 1}"
                        backTo != null -> "Torna a “${titles[backTo] ?: "quaderno precedente"}”"
                        else -> null
                    }
                    if (backLabel != null) {
                        LaunchedEffect(backTo) {
                            if (backTo != null && titles.isEmpty()) titles = withContext(AppIo.serial) { repository.list().associate { it.id to it.title } }
                        }
                        BackChip(
                            backLabel,
                            onClick = {
                                if (localBack.isNotEmpty()) {
                                    editor?.restoreViewState(localBack.removeAt(localBack.lastIndex).second)
                                } else {
                                    onLinkBack()
                                }
                            },
                            modifier = Modifier.align(Alignment.TopStart).padding(12.dp),
                        )
                    }
                }
            }
        }
        if (exporting) {
            Box(
                Modifier.fillMaxSize().background(Color(0x33000000)).clickable(enabled = true, onClick = {}),
                contentAlignment = Alignment.Center,
            ) {
                Surface(shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(14.dp))
                        Text("Esportazione in corso…")
                    }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp))
    }

    tagList?.let { list ->
        // come per i preferiti: anche i salvataggi dell'editor riscrivono meta.json, quindi si aggiorna d.meta
        fun setTags(tags: List<String>) {
            noteTags = tags
            doc?.let { d -> d.meta = d.meta.copy(tags = tags) }
        }
        TagsDialog(
            list,
            counts = list.associate { it.id to if (it.id in noteTags) 1 else 0 },
            total = 1,
            onToggle = { t, on ->
                setTags(if (on) (noteTags + t.id).distinct() else noteTags - t.id)
                AppIo.scope.launch { repository.setTag(notebookId, t.id, on) }
            },
            onCreate = { name, color ->
                scope.launch {
                    val t = withContext(AppIo.serial) { repository.createTag(name, color).also { repository.setTag(notebookId, it.id, true) } }
                    tagList = list + t
                    setTags(noteTags + t.id)
                }
            },
            onEdit = { editingTag = it },
            onDismiss = { tagList = null },
        )
    }
    editingTag?.let { t ->
        TagEditDialog(
            t,
            usedBy = -1,
            onSave = { name, color ->
                editingTag = null
                tagList = tagList?.map { if (it.id == t.id) it.copy(name = name, color = color) else it }
                AppIo.scope.launch { repository.updateTag(t.id, name, color) }
            },
            onDelete = {
                editingTag = null
                tagList = tagList?.filter { it.id != t.id }
                doc?.let { d -> d.meta = d.meta.copy(tags = d.meta.tags - t.id) }
                noteTags = noteTags - t.id
                AppIo.scope.launch { repository.deleteTag(t.id) }
            },
            onDismiss = { editingTag = null },
        )
    }

    if (showAppSettings) {
        AppSettingsDialog(
            onDismiss = { showAppSettings = false },
            onBarChanged = { c, s -> quickColorCount = c; quickSizeCount = s },
        )
    }

    val current = doc
    if (showSetup && current != null) {
        PageSetupDialog(
            format = current.format,
            style = current.style,
            onDismiss = { showSetup = false },
            onApply = { format, style ->
                showSetup = false
                if (style.darkPaper && !current.style.darkPaper) {
                    scope.launch { snackbar.showSnackbar("Carta scura: per scrivere scegli un colore chiaro, il nero non si vede") }
                }
                current.meta = current.meta.copy(format = format, style = style)
                editor?.onPageSetupChanged()
                listener.onDocumentChanged()
            },
        )
    }

    linkSource?.let { (srcPage, rect) ->
        val d = doc
        if (d != null) {
            LinkTargetDialog(
                repository = repository,
                current = d,
                currentPdf = pdfPages,
                onDismiss = { linkSource = null },
            ) { choice ->
                linkSource = null
                editor?.addLink(
                    srcPage,
                    PageLink(
                        rect.left, rect.top, rect.width(), rect.height(),
                        choice.notebookId, choice.pageId, choice.pageIndex,
                        choice.point?.x ?: Float.NaN, choice.point?.y ?: Float.NaN,
                    ),
                )
                editor?.clearSelection()
                scope.launch { snackbar.showSnackbar("Collegamento creato: toccalo col dito per aprirlo") }
            }
        }
    }

    if (showBookmarks && current != null) {
        BookmarksDialog(
            pages = current.pages,
            version = editVersion,
            current = page.coerceIn(0, current.pages.lastIndex),
            outline = if (current.meta.fromPdf) pdfOutline.orEmpty() else null,
            outlineLoading = outlineLoading,
            onJump = { showBookmarks = false; editor?.scrollToPage(it) },
            onSet = { i, name -> editor?.setBookmark(i, name) },
            onDismiss = { showBookmarks = false },
        )
    }

    if (showPages && current != null) {
        PagesOverview(
            doc = current,
            current = page,
            version = pagesVersion,
            ops = PageOps(
                onOpen = { showPages = false; choosePages = false; editor?.scrollToPage(it) },
                onInsertAfter = { editor?.insertPageAfter(it); pagesVersion++ },
                onDuplicate = { editor?.duplicatePage(it); pagesVersion++ },
                onMove = { from, to -> editor?.movePage(from, to); pagesVersion++ },
                onDelete = { editor?.deletePage(it); pagesVersion++ },
            ),
            onDismiss = { showPages = false; choosePages = false },
            pdfPages = pdfPages,
            startChoosing = choosePages,
            onExport = { list ->
                showPages = false
                choosePages = false
                export(ExportKind.PDF_CHOSEN, list)
            },
        )
    }

    if (confirmDeletePage) {
        AlertDialog(
            onDismissRequest = { confirmDeletePage = false },
            title = { Text("Eliminare la pagina ${page + 1}?") },
            text = { Text("Potrai recuperarla con Annulla.") },
            confirmButton = {
                TextButton(onClick = { confirmDeletePage = false; editor?.deleteCurrentPage() }) {
                    Text("Elimina", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDeletePage = false }) { Text("Annulla") } },
        )
    }

    pickerFor?.let { kind ->
        ColorPickerDialog(
            initial = tools.getValue(kind).color,
            withAlpha = kind == ToolKind.HIGHLIGHTER,
            onDismiss = { pickerFor = null },
            onPick = { color ->
                pickerFor = null
                addCustomColor(color)
                updateTool(tools.getValue(kind).copy(color = color))
                selectTool(kind)
            },
        )
    }
}
