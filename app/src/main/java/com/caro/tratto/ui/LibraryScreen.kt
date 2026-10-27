package com.caro.tratto.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.caro.tratto.data.NotebookRepository
import com.caro.tratto.data.PlanFile
import com.caro.tratto.data.RecentNotebooks
import com.caro.tratto.export.Exporter
import com.caro.tratto.export.FolderSync
import com.caro.tratto.model.Folder
import com.caro.tratto.model.NotebookMeta
import com.caro.tratto.model.PageStyle
import com.caro.tratto.model.PageFormat
import com.caro.tratto.model.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(
    repository: NotebookRepository,
    refreshKey: Int,
    currentFolder: String?,
    onFolderChange: (String?) -> Unit,
    onOpen: (String) -> Unit,
    favoritesView: Boolean = false,
    onFavoritesView: (Boolean) -> Unit = {},
    /** Ricerca aperta (anche vuota) o null. */
    search: String? = null,
    onSearch: (String?) -> Unit = {},
    /** Vista di un tag (id): i quaderni con quel tag, da tutte le cartelle. */
    tagView: String? = null,
    onTagView: (String?) -> Unit = {},
    /** Vista "Pagine recenti" e apertura di un quaderno su una pagina precisa. */
    recentView: Boolean = false,
    onRecentView: (Boolean) -> Unit = {},
    onOpenPage: (String, Int) -> Unit = { id, _ -> onOpen(id) },
    /** File arrivati da altre app (PDF, note di Samsung Notes) da importare. */
    incoming: List<android.net.Uri> = emptyList(),
    onIncomingHandled: () -> Unit = {},
    /** Apre il grafo dei collegamenti. */
    onGraph: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var notebooks by remember { mutableStateOf<List<NotebookMeta>?>(null) }
    var folders by remember { mutableStateOf<List<Folder>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var showNew by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf<ImportProgress?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }
    var importDone by remember { mutableStateOf<ImportSummary?>(null) }
    var planPreview by remember { mutableStateOf<PlanPreview?>(null) }
    var planDone by remember { mutableStateOf<NotebookRepository.PlanResult?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    var renaming by remember { mutableStateOf<NotebookMeta?>(null) }
    var renamingFolder by remember { mutableStateOf<Folder?>(null) }
    // selezione multipla (pressione lunga): id dei quaderni e delle cartelle scelti
    var selNb by remember { mutableStateOf(emptySet<String>()) }
    var selF by remember { mutableStateOf(emptySet<String>()) }
    var toMove by remember { mutableStateOf<Picked?>(null) }
    var toDelete by remember { mutableStateOf<Picked?>(null) }
    var toColor by remember { mutableStateOf<List<Folder>?>(null) }
    // esportazione di più quaderni: avanzamento, esito se qualcosa non è riuscito
    var exportProgress by remember { mutableStateOf<SyncProgress?>(null) }
    var exportOutcome by remember { mutableStateOf<Exporter.Batch?>(null) }
    val exportStop = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var showSettings by remember { mutableStateOf(false) }
    val prefs = remember { ToolPrefs(context) }
    var sort by remember { mutableStateOf(prefs.librarySort) }
    var sortReversed by remember { mutableStateOf(prefs.librarySortReversed) }
    val folderSync = remember { FolderSync(context) }
    var syncTarget by remember { mutableStateOf(folderSync.target()) }
    var syncIntro by remember { mutableStateOf(false) }
    var syncProgress by remember { mutableStateOf<SyncProgress?>(null) }
    var syncOutcome by remember { mutableStateOf<SyncOutcome?>(null) }
    val syncStop = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    var tags by remember { mutableStateOf<List<Tag>>(emptyList()) }
    // dialog dei tag: id dei quaderni da etichettare (vuoto = solo gestione); tag da modificare
    var tagging by remember { mutableStateOf<List<String>?>(null) }
    var editingTag by remember { mutableStateOf<Tag?>(null) }
    var tagMenu by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey, reload) {
        val (n, f) = withContext(AppIo.serial) { repository.list() to repository.folders() }
        notebooks = n
        folders = f
        tags = withContext(AppIo.serial) { repository.tags() }
    }
    val recentNotebooks = remember { RecentNotebooks(context) }
    var recentEntries by remember { mutableStateOf<List<RecentNotebooks.Entry>>(emptyList()) }
    LaunchedEffect(refreshKey, reload, recentView) {
        if (!recentView) return@LaunchedEffect
        recentEntries = withContext(Dispatchers.IO) {
            recentNotebooks.prune(repository.list().mapTo(HashSet()) { it.id })
            recentNotebooks.list()
        }
    }
    val recentById = recentEntries.associateBy { it.notebookId }

    // cartella corrente sparita (eliminata altrove): torna alla radice
    val current = currentFolder?.takeIf { id -> folders.isEmpty() || folders.any { it.id == id } }
    val path = remember(folders, current) {
        val out = ArrayList<Folder>()
        var id = current
        while (id != null) {
            val f = folders.firstOrNull { it.id == id } ?: break
            out.add(0, f)
            id = f.parentId
        }
        out
    }
    BackHandler(enabled = current != null) { onFolderChange(path.dropLast(1).lastOrNull()?.id) }
    BackHandler(enabled = favoritesView) { onFavoritesView(false) }
    // tag sparito (eliminato): si torna alle cartelle
    val tagShown = tagView?.let { id -> tags.firstOrNull { it.id == id } }
    BackHandler(enabled = tagView != null) { onTagView(null) }
    BackHandler(enabled = recentView) { onRecentView(false) }
    val searching = search != null
    BackHandler(enabled = searching) { onSearch(null) }
    val selecting = selNb.isNotEmpty() || selF.isNotEmpty()
    fun clearSelection() {
        selNb = emptySet()
        selF = emptySet()
    }
    // indietro chiude prima la selezione; cambiando cartella o vista si riparte da zero
    BackHandler(enabled = selecting) { clearSelection() }
    LaunchedEffect(current, favoritesView, searching, tagView, recentView) { clearSelection() }
    // dalla vista "Preferiti" i quaderni nuovi nascono nella radice, già preferiti
    val target = if (favoritesView || tagView != null) null else current
    // nati dalla vista Preferiti o da quella di un tag: già preferiti / già con il tag
    fun markNew(id: String) {
        if (favoritesView) repository.setFavorite(id, true)
        tagShown?.let { repository.setTag(id, it.id, true) }
    }

    fun toggleFavorite(meta: NotebookMeta) {
        val fav = !meta.favorite
        notebooks = notebooks?.map { if (it.id == meta.id) it.copy(favorite = fav) else it }
        scope.launch { withContext(AppIo.serial) { repository.setFavorite(meta.id, fav) } }
    }

    // copia dei quaderni (PDF, stesse cartelle) nella cartella scelta, anche su OneDrive
    fun startSync() {
        if (syncProgress != null) return
        syncStop.set(false)
        syncProgress = SyncProgress(0, 0, "")
        scope.launch {
            syncOutcome = try {
                val r = withContext(AppIo.serial) {
                    folderSync.sync(repository, cancelled = syncStop::get) { done, total, title ->
                        syncProgress = SyncProgress(done, total, title, stopping = syncStop.get())
                    }
                }
                SyncOutcome(r, null, folderSync.target()?.label)
            } catch (e: Exception) {
                SyncOutcome(null, e.message ?: e.javaClass.simpleName, null)
            } finally {
                syncProgress = null
                syncTarget = folderSync.target()
            }
        }
    }

    val pickSyncFolder = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { folderSync.setTarget(uri) }.isSuccess }
            syncTarget = folderSync.target()
            if (ok && syncTarget != null) startSync()
            else syncOutcome = SyncOutcome(null, "Tratto non riesce a tenere il permesso su questa cartella: provane un'altra", null)
        }
    }

    // PDF (anche un libro scansionato) e note di Samsung Notes → quaderni nuovi nella cartella
    // corrente. Un file solo si apre; tanti insieme, dalla radice, finiscono in una cartella apposta.
    // un piano di riordino (JSON) arrivato insieme ai file si propone dopo averli importati
    fun importFiles(all: List<android.net.Uri>) {
        if (importing != null || all.isEmpty()) return
        importing = ImportProgress(0, all.size, "")
        scope.launch {
            val plans = ArrayList<PlanFile.Plan>()
            val uris = withContext(Dispatchers.IO) { all.filter { u -> repository.readPlan(context, u)?.let { plans += it } == null } }
            plans.firstOrNull()?.let { p -> planPreview = PlanPreview(p, withContext(AppIo.serial) { repository.resolvePlan(p).size }) }
            if (uris.isEmpty()) {
                importing = null
                return@launch
            }
            val names = withContext(Dispatchers.IO) { uris.map { repository.displayName(context, it) ?: "File" } }
            val dest = if (uris.size > 1 && target == null) {
                val name = if (names.all { it.endsWith(".sdocx", ignoreCase = true) }) "Samsung Notes" else "Importati"
                withContext(AppIo.serial) {
                    repository.folders().firstOrNull { it.parentId == null && it.name == name } ?: repository.createFolder(name, null)
                }
            } else {
                null
            }
            val done = ArrayList<NotebookRepository.Imported>()
            val failed = ArrayList<Pair<String, String>>()
            uris.forEachIndexed { i, uri ->
                importing = ImportProgress(i, uris.size, names[i])
                try {
                    done += withContext(Dispatchers.IO) {
                        repository.importFile(context, uri, dest?.id ?: target) { importing = ImportProgress(i, uris.size, names[i], it) }
                            .also { if (!it.duplicate) markNew(it.meta.id) }
                    }
                } catch (e: Exception) {
                    failed += names[i] to (e.message ?: e.javaClass.simpleName)
                }
            }
            importing = null
            reload++
            val summary = ImportSummary(done, failed, dest?.name)
            // il piano si propone dopo: ora i quaderni importati ci sono
            planPreview?.let { pp -> planPreview = pp.copy(found = withContext(AppIo.serial) { repository.resolvePlan(pp.plan).size }) }
            when {
                uris.size == 1 && failed.isNotEmpty() -> importError = failed[0].second
                uris.size == 1 && !summary.hasNotes && planPreview == null -> onOpen(done[0].meta.id)
                else -> {
                    dest?.let { onFolderChange(it.id) }
                    importDone = summary
                }
            }
        }
    }

    val pickFiles = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> importFiles(uris) }

    LaunchedEffect(incoming) {
        if (incoming.isNotEmpty()) { importFiles(incoming); onIncomingHandled() }
    }

    val all = notebooks
    // "Fisica › Unità 2" per ogni cartella: dove sta un risultato della ricerca o un preferito
    val places = remember(folders) {
        val byId = folders.associateBy { it.id }
        folders.associate { f ->
            f.id to generateSequence(f) { byId[it.parentId ?: ""] }.toList().asReversed().joinToString(" › ") { it.name }
        }
    }
    val q = search.orEmpty()
    val subFolders = when {
        searching -> if (q.isBlank()) emptyList() else folders.filter { matchesSearch(q, it.name, it.parentId?.let(places::get)) }
        favoritesView || tagView != null || recentView -> emptyList()
        else -> folders.filter { it.parentId == current }
    }.sortedForLibrary(sort, sortReversed)
    // ricerca e Preferiti: da tutte le cartelle; altrove i preferiti stanno in cima
    val here = when {
        searching -> if (q.isBlank()) emptyList() else all?.filter { nb ->
            // si cerca anche fra i nomi dei tag del quaderno
            val tagNames = nb.tags.mapNotNull { id -> tags.firstOrNull { it.id == id }?.name }
            matchesSearch(q, nb.title, (listOfNotNull(nb.folderId?.let(places::get)) + tagNames).joinToString(" "))
        }
        // recenti: nell'ordine in cui sono stati aperti, non in quello della libreria
        recentView -> all?.let { list -> val byId = list.associateBy { it.id }; recentEntries.mapNotNull { byId[it.notebookId] } }
        favoritesView -> all?.filter { it.favorite }
        tagView != null -> all?.filter { tagView in it.tags }
        else -> all?.filter { it.folderId == current }
    }.orEmpty().let { if (recentView && !searching) it else it.sortedForLibrary(sort, sortReversed) }
    val picked = Picked(here.filter { it.id in selNb }, subFolders.filter { it.id in selF })
    // da esportare: i quaderni scelti, poi quelli dentro le cartelle scelte (anche nelle sottocartelle)
    val toExport = run {
        val inside = HashSet<String>()
        var level = picked.folders.map { it.id }
        while (level.isNotEmpty()) {
            inside += level
            level = folders.filter { it.parentId in level && it.id !in inside }.map { it.id }
        }
        picked.notebooks + all.orEmpty().filter { it.folderId in inside && it.id !in selNb }.sortedForLibrary(sort, sortReversed)
    }

    // un PDF per quaderno, condivisi tutti insieme (Drive, Telegram, email…)
    fun startExport(list: List<NotebookMeta>) {
        if (exportProgress != null || list.isEmpty()) return
        exportStop.set(false)
        exportProgress = SyncProgress(0, list.size, "")
        scope.launch {
            val r = try {
                withContext(AppIo.serial) {
                    Exporter.exportPdfs(context, list, cancelled = exportStop::get) { done, total, title ->
                        exportProgress = SyncProgress(done, total, title, stopping = exportStop.get())
                    }
                }
            } catch (e: Exception) {
                Exporter.Batch(emptyList(), list.map { it.title }, cancelled = false)
            } finally {
                exportProgress = null
            }
            if (r.cancelled) return@launch
            if (r.files.isNotEmpty()) Exporter.share(context, r.files, "application/pdf")
            if (r.failed.isNotEmpty()) exportOutcome = r
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (selecting) {
                SelectionBar(
                    picked,
                    onTags = { tagging = picked.notebooks.map { it.id } },
                    onFavorite = {
                        val fav = picked.notebooks.any { !it.favorite }
                        val ids = picked.notebooks.mapTo(HashSet()) { it.id }
                        notebooks = notebooks?.map { if (it.id in ids) it.copy(favorite = fav) else it }
                        clearSelection()
                        scope.launch { withContext(AppIo.serial) { ids.forEach { repository.setFavorite(it, fav) } } }
                    },
                    onRename = {
                        picked.notebooks.firstOrNull()?.let { renaming = it } ?: picked.folders.firstOrNull()?.let { renamingFolder = it }
                        clearSelection()
                    },
                    onColor = { toColor = picked.folders },
                    onMove = { toMove = picked },
                    onDelete = { toDelete = picked },
                    canExport = toExport.isNotEmpty(),
                    onExport = {
                        startExport(toExport)
                        clearSelection()
                    },
                )
            }
        },
        floatingActionButton = {
            if (!selecting && !searching && !recentView) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    onClick = {
                        try {
                            // le note di Samsung Notes non hanno un tipo registrato: si mostra tutto application/*
                            pickFiles.launch(arrayOf("application/*"))
                        } catch (e: android.content.ActivityNotFoundException) {
                            importError = "su questo dispositivo non c'è un'app per scegliere i file"
                        }
                    },
                    icon = { Icon(TrattoIcons.Import, contentDescription = null) },
                    text = { Text("Importa") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = InkBlue,
                )
                if (!favoritesView && tagView == null) ExtendedFloatingActionButton(
                    onClick = { showNewFolder = true },
                    icon = { Icon(TrattoIcons.Plus, contentDescription = null) },
                    text = { Text("Nuova cartella") },
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = InkBlue,
                )
                ExtendedFloatingActionButton(
                    onClick = { showNew = true },
                    icon = { Icon(TrattoIcons.Plus, contentDescription = null) },
                    text = { Text("Nuovo quaderno") },
                    containerColor = InkBlue,
                    contentColor = Color.White,
                )
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
            if (selecting) {
                val allSelected = picked.size == subFolders.size + here.size
                SelectionHeader(picked.size, allSelected, onClose = ::clearSelection) {
                    if (allSelected) {
                        clearSelection()
                    } else {
                        selNb = here.mapTo(HashSet()) { it.id }
                        selF = subFolders.mapTo(HashSet()) { it.id }
                    }
                }
            } else if (searching) {
                SearchRow(q, onQuery = { onSearch(it) }, onClose = { onSearch(null) })
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (recentView) {
                        Breadcrumb(emptyList(), onGo = { onRecentView(false); onFolderChange(null) }, tail = "Recenti")
                    } else if (favoritesView) {
                        Breadcrumb(emptyList(), onGo = { onFavoritesView(false); onFolderChange(null) }, tail = "Preferiti")
                    } else if (tagShown != null) {
                        Breadcrumb(emptyList(), onGo = { onTagView(null); onFolderChange(null) }, tail = "# " + tagShown.name)
                    } else {
                        Breadcrumb(path, onFolderChange)
                    }
                }
                FilterChip(
                    selected = favoritesView,
                    onClick = { onTagView(null); onRecentView(false); onFavoritesView(!favoritesView) },
                    label = { Text("Preferiti" + (all?.count { it.favorite }?.takeIf { it > 0 }?.let { " · $it" } ?: "")) },
                    leadingIcon = {
                        Icon(
                            if (favoritesView) TrattoIcons.StarFilled else TrattoIcons.Star,
                            contentDescription = null,
                            tint = StarGold,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    modifier = Modifier.padding(top = 12.dp, start = 8.dp),
                )
                Box {
                    FilterChip(
                        selected = tagShown != null,
                        onClick = { tagMenu = true },
                        label = { Text(tagShown?.name ?: "Tag", maxLines = 1) },
                        leadingIcon = {
                            if (tagShown != null) TagDot(tagShown.color) else Icon(TrattoIcons.Tag, null, modifier = Modifier.size(18.dp))
                        },
                        modifier = Modifier.padding(top = 12.dp, start = 8.dp).widthIn(max = 170.dp),
                    )
                    DropdownMenu(expanded = tagMenu, onDismissRequest = { tagMenu = false }) {
                        tags.forEach { t ->
                            val n = all?.count { t.id in it.tags } ?: 0
                            DropdownMenuItem(
                                text = { Text("${t.name} · $n") },
                                leadingIcon = { TagDot(t.color) },
                                onClick = { tagMenu = false; onFavoritesView(false); onRecentView(false); onTagView(t.id) },
                            )
                        }
                        if (tagShown != null) {
                            DropdownMenuItem(text = { Text("Torna alle cartelle") }, onClick = { tagMenu = false; onTagView(null) })
                        }
                        DropdownMenuItem(
                            text = { Text(if (tags.isEmpty()) "Crea un tag…" else "Gestisci i tag…") },
                            leadingIcon = { Icon(TrattoIcons.Rename, null) },
                            onClick = { tagMenu = false; tagging = emptyList() },
                        )
                    }
                }
                IconButton(onClick = { onSearch("") }, modifier = Modifier.padding(top = 12.dp, start = 4.dp)) {
                    Icon(TrattoIcons.Search, contentDescription = "Cerca")
                }
                IconButton(
                    onClick = {
                        onFavoritesView(false)
                        onTagView(null)
                        onRecentView(!recentView)
                    },
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                ) {
                    Icon(TrattoIcons.Recent, contentDescription = "Quaderni recenti", tint = if (recentView) InkBlue else LocalContentColor.current)
                }
                IconButton(onClick = onGraph, modifier = Modifier.padding(top = 12.dp, start = 4.dp)) {
                    Icon(TrattoIcons.Graph, contentDescription = "Grafo dei collegamenti")
                }
                IconButton(
                    onClick = { if (syncTarget == null) syncIntro = true else startSync() },
                    modifier = Modifier.padding(top = 12.dp, start = 4.dp),
                ) {
                    Icon(TrattoIcons.CloudSync, contentDescription = "Sincronizza")
                }
                IconButton(onClick = { showSettings = true }, modifier = Modifier.padding(top = 12.dp, end = 12.dp)) {
                    Icon(TrattoIcons.Settings, contentDescription = "Impostazioni generali")
                }
            }
            when {
                all == null -> Unit
                subFolders.isEmpty() && here.isEmpty() -> EmptyLibrary(inFolder = current != null, favorites = favoritesView, search = search, tag = tagShown?.name, recent = recentView)
                else -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(190.dp),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 180.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (recentView && !searching) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "I quaderni che hai aperto di recente: si riaprono dove li avevi lasciati",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = {
                                    recentEntries = emptyList()
                                    scope.launch(Dispatchers.IO) { recentNotebooks.clear() }
                                }) { Text("Svuota la cronologia") }
                            }
                        }
                    }
                    items(subFolders, key = { "f_" + it.id }) { f ->
                        val count = folders.count { it.parentId == f.id } + all.count { it.folderId == f.id }
                        FolderCard(
                            folder = f,
                            count = count,
                            selected = if (selecting) f.id in selF else null,
                            onClick = {
                                if (selecting) {
                                    selF = selF.toggle(f.id)
                                } else {
                                    if (searching) onSearch(null)
                                    onFolderChange(f.id)
                                }
                            },
                            onLongClick = { selF = selF.toggle(f.id) },
                            place = if (searching) f.parentId?.let(places::get) ?: "I tuoi quaderni" else null,
                        )
                    }
                    items(here, key = { it.id }) { meta ->
                        NotebookCard(
                            meta = meta,
                            cover = repository.thumbnail(meta.id),
                            folderName = if (favoritesView || searching || tagView != null || recentView) meta.folderId?.let(places::get) else null,
                            whenText = if (recentView && !searching) recentById[meta.id]?.let { "aperto ${whenText(it.time)}" } else null,
                            tags = meta.tags.mapNotNull { id -> tags.firstOrNull { it.id == id } },
                            selected = if (selecting) meta.id in selNb else null,
                            onToggleFavorite = { toggleFavorite(meta) },
                            onClick = {
                                when {
                                    selecting -> selNb = selNb.toggle(meta.id)
                                    // dai recenti si riapre sull'ultima pagina guardata
                                    recentView && !searching -> onOpenPage(meta.id, recentById[meta.id]?.page ?: 0)
                                    else -> onOpen(meta.id)
                                }
                            },
                            onLongClick = { selNb = selNb.toggle(meta.id) },
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        AppSettingsDialog(
            onDismiss = { showSettings = false },
            onSortChanged = { s, r -> sort = s; sortReversed = r },
            onSyncTargetChanged = { syncTarget = folderSync.target() },
        )
    }

    fun pickFolder() {
        try {
            pickSyncFolder.launch(null)
        } catch (e: android.content.ActivityNotFoundException) {
            syncOutcome = SyncOutcome(null, "su questo dispositivo non c'è il selettore di cartelle di Android", null)
        }
    }

    if (syncIntro) {
        SyncIntroDialog(
            onPick = { syncIntro = false; pickFolder() },
            onDismiss = { syncIntro = false },
        )
    }
    syncProgress?.let { p ->
        SyncProgressDialog(p, syncTarget?.label, onStop = {
            syncStop.set(true)
            syncProgress = SyncProgress(p.done, p.total, p.title, stopping = true)
        })
    }
    syncOutcome?.let { o ->
        SyncOutcomeDialog(
            o,
            onPick = { syncOutcome = null; pickFolder() },
            onDismiss = { syncOutcome = null },
        )
    }

    exportProgress?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (p.total > 1) "Esporto ${minOf(p.done + 1, p.total)} di ${p.total}…" else "Esportazione…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    androidx.compose.material3.LinearProgressIndicator(progress = { p.done / p.total.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text(if (p.stopping) "Mi fermo dopo questo quaderno…" else p.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    exportStop.set(true)
                    exportProgress = SyncProgress(p.done, p.total, p.title, stopping = true)
                }, enabled = !p.stopping) { Text("Interrompi") }
            },
        )
    }

    exportOutcome?.let { r ->
        AlertDialog(
            onDismissRequest = { exportOutcome = null },
            title = { Text(if (r.files.isEmpty()) "Esportazione non riuscita" else "Esportazione fatta in parte") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (r.files.isNotEmpty()) Text(if (r.files.size == 1) "1 quaderno esportato." else "${r.files.size} quaderni esportati.")
                    Text("Non riusciti (${r.failed.size}):", style = MaterialTheme.typography.titleSmall)
                    r.failed.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { exportOutcome = null }) { Text("OK") } },
        )
    }

    if (showNew) {
        NewNotebookDialog(
            onDismiss = { showNew = false },
            onCreate = { title, format, style ->
                showNew = false
                scope.launch {
                    val meta = withContext(AppIo.serial) { repository.create(title, format, style, target).also { markNew(it.id) } }
                    onOpen(meta.id)
                }
            },
        )
    }

    importing?.let { p ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (p.total > 1) "Importo ${p.done + 1} di ${p.total}…" else "Importazione…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    val f = p.fraction
                    val overall = if (p.total > 1) (p.done + (f ?: 0f)) / p.total else f
                    if (overall != null) {
                        androidx.compose.material3.LinearProgressIndicator(progress = { overall }, modifier = Modifier.fillMaxWidth())
                    } else {
                        androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    if (p.name.isNotEmpty()) Text(p.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {},
        )
    }

    importDone?.let { r ->
        val fresh = r.done.filter { !it.duplicate }
        AlertDialog(
            onDismissRequest = { importDone = null },
            title = {
                Text(
                    when (fresh.size) {
                        0 -> if (r.failed.isEmpty()) "Già importate" else "Nessun quaderno importato"
                        1 -> "Quaderno importato"
                        else -> "${fresh.size} quaderni importati"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                    if (fresh.isNotEmpty()) r.folder?.let { Text("Li trovi nella cartella «$it».") }
                    val again = r.done.size - fresh.size
                    if (again > 0) Text(if (again == 1) "Una nota era già in Tratto: non l'ho importata di nuovo." else "$again note erano già in Tratto: non le ho importate di nuovo.")
                    val typed = fresh.count { it.typedText }
                    val why = "non è stato importato, perché Tratto non ha ancora le caselle di testo."
                    if (typed > 0) Text(
                        when {
                            fresh.size == 1 -> "Nella nota c'era testo scritto con la tastiera: $why"
                            typed == 1 -> "In una nota c'era testo scritto con la tastiera: $why"
                            else -> "In $typed note c'era testo scritto con la tastiera: $why"
                        },
                    )
                    val skipped = fresh.sumOf { it.skipped }
                    if (skipped > 0) Text(
                        if (skipped == 1) "Saltato un elemento che Tratto non sa mostrare (per esempio una registrazione audio o una tabella)."
                        else "Saltati $skipped elementi che Tratto non sa mostrare (per esempio registrazioni audio o tabelle).",
                    )
                    if (r.failed.isNotEmpty()) {
                        Text("Non importati (${r.failed.size}):", style = MaterialTheme.typography.titleSmall)
                        r.failed.forEach { (name, why) -> Text("• $name: $why", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            },
            confirmButton = {
                if (r.done.size == 1 && r.folder == null) {
                    TextButton(onClick = { importDone = null; onOpen(r.done[0].meta.id) }) { Text("Apri") }
                } else {
                    TextButton(onClick = { importDone = null }) { Text("OK") }
                }
            },
        )
    }

    planPreview?.takeIf { importDone == null && importing == null }?.let { pp ->
        val p = pp.plan
        AlertDialog(
            onDismissRequest = { planPreview = null },
            title = { Text(p.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // le materie, non tutte le sottocartelle (con le unità sarebbero decine)
                    val roots = p.folders.map { it.path.first() }.distinct()
                    if (roots.isNotEmpty()) Text("Cartelle: ${roots.joinToString(", ")}" + if (p.folders.size > roots.size) " (${p.folders.size} in tutto)" else "")
                    Text("Quaderni da sistemare: ${pp.found} di ${p.notebooks.size}")
                    Text("Collegamenti da aggiungere: ${p.links.size}")
                    if (pp.found < p.notebooks.size) Text(
                        "Alcune note non sono ancora in Tratto: importale prima, oppure applica il piano lo stesso e poi di nuovo quando ci sono.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = pp.found > 0, onClick = {
                    planPreview = null
                    scope.launch {
                        planDone = withContext(AppIo.serial) { repository.applyPlan(p) }
                        onFolderChange(null)
                        reload++
                    }
                }) { Text("Applica") }
            },
            dismissButton = { TextButton(onClick = { planPreview = null }) { Text("Annulla") } },
        )
    }

    planDone?.let { r ->
        AlertDialog(
            onDismissRequest = { planDone = null },
            title = { Text("Riordino fatto") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("${r.placed} quaderni messi nelle loro cartelle, ${r.links} collegamenti aggiunti.")
                    if (r.missing.isNotEmpty()) Text("Non trovati in Tratto: ${r.missing.joinToString(", ")}.")
                }
            },
            confirmButton = { TextButton(onClick = { planDone = null }) { Text("OK") } },
        )
    }

    importError?.let { msg ->
        AlertDialog(
            onDismissRequest = { importError = null },
            title = { Text("Impossibile importare il file") },
            text = { Text(msg.replaceFirstChar { it.uppercase() }) },
            confirmButton = { TextButton(onClick = { importError = null }) { Text("OK") } },
        )
    }

    if (showNewFolder) {
        FolderNameDialog("Nuova cartella", "", null, onDismiss = { showNewFolder = false }) { name, color ->
            showNewFolder = false
            scope.launch {
                withContext(AppIo.serial) { repository.createFolder(name, current, color) }
                reload++
            }
        }
    }

    renamingFolder?.let { f ->
        FolderNameDialog("Rinomina cartella", f.name, f.color, onDismiss = { renamingFolder = null }) { name, color ->
            renamingFolder = null
            scope.launch {
                withContext(AppIo.serial) {
                    repository.renameFolder(f.id, name)
                    repository.setFolderColor(f.id, color)
                }
                reload++
            }
        }
    }

    tagging?.let { ids ->
        val chosen = all.orEmpty().filter { it.id in ids }
        TagsDialog(
            tags,
            counts = tags.associate { t -> t.id to chosen.count { t.id in it.tags } },
            total = chosen.size,
            onToggle = { t, on ->
                notebooks = notebooks?.map { nb ->
                    if (nb.id !in ids) nb else nb.copy(tags = if (on) (nb.tags + t.id).distinct() else nb.tags - t.id)
                }
                scope.launch { withContext(AppIo.serial) { ids.forEach { repository.setTag(it, t.id, on) } } }
            },
            onCreate = { name, color ->
                scope.launch {
                    val t = withContext(AppIo.serial) { repository.createTag(name, color).also { t -> ids.forEach { repository.setTag(it, t.id, true) } } }
                    tags = tags + t
                    notebooks = notebooks?.map { nb -> if (nb.id in ids) nb.copy(tags = nb.tags + t.id) else nb }
                }
            },
            onEdit = { editingTag = it },
            onDismiss = {
                tagging = null
                clearSelection()
            },
        )
    }

    editingTag?.let { t ->
        TagEditDialog(
            t,
            usedBy = all.orEmpty().count { t.id in it.tags },
            onSave = { name, color ->
                editingTag = null
                tags = tags.map { if (it.id == t.id) it.copy(name = name, color = color) else it }
                scope.launch { withContext(AppIo.serial) { repository.updateTag(t.id, name, color) } }
            },
            onDelete = {
                editingTag = null
                tags = tags.filter { it.id != t.id }
                notebooks = notebooks?.map { it.copy(tags = it.tags - t.id) }
                if (tagView == t.id) onTagView(null)
                scope.launch { withContext(AppIo.serial) { repository.deleteTag(t.id) } }
            },
            onDismiss = { editingTag = null },
        )
    }

    toColor?.let { fs ->
        val first = fs.first().color
        FolderColorDialog(
            if (fs.size == 1) "Colore di “${fs[0].name}”" else "Colore di ${fs.size} cartelle",
            first,
            mixed = fs.any { it.color != first },
            onDismiss = { toColor = null },
        ) { color ->
            toColor = null
            clearSelection()
            val ids = fs.mapTo(HashSet()) { it.id }
            folders = folders.map { if (it.id in ids) it.copy(color = color) else it }
            scope.launch { withContext(AppIo.serial) { ids.forEach { repository.setFolderColor(it, color) } } }
        }
    }

    toMove?.let { p ->
        MoveToFolderDialog(
            folders,
            current = current,
            onDismiss = { toMove = null },
            exclude = p.folders.mapTo(HashSet()) { it.id },
            markCurrent = !favoritesView,
            title = if (p.size == 1) "Sposta in…" else "Sposta ${p.size} elementi in…",
        ) { dest ->
            toMove = null
            clearSelection()
            scope.launch {
                withContext(AppIo.serial) {
                    p.notebooks.forEach { repository.moveNotebook(it.id, dest) }
                    p.folders.forEach { repository.moveFolder(it.id, dest) }
                }
                reload++
            }
        }
    }

    toDelete?.let { p ->
        val n = p.notebooks.size
        val f = p.folders.size
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = {
                Text(
                    when {
                        n == 1 && f == 0 -> "Eliminare “${p.notebooks[0].title}”?"
                        f == 1 && n == 0 -> "Eliminare la cartella “${p.folders[0].name}”?"
                        else -> "Eliminare " + listOfNotNull(
                            n.takeIf { it > 0 }?.let { if (it == 1) "1 quaderno" else "$it quaderni" },
                            f.takeIf { it > 0 }?.let { if (it == 1) "1 cartella" else "$it cartelle" },
                        ).joinToString(" e ") + "?"
                    },
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (n > 0) {
                        Text(
                            if (n == 1) "Il quaderno e tutte le sue pagine verranno eliminati definitivamente."
                            else "I quaderni e tutte le loro pagine verranno eliminati definitivamente.",
                        )
                    }
                    if (f > 0) {
                        Text(
                            if (f == 1) "Quello che c'è nella cartella non viene eliminato: torna nella cartella superiore."
                            else "Quello che c'è nelle cartelle non viene eliminato: torna nella cartella superiore.",
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    toDelete = null
                    clearSelection()
                    val depth = p.folders.associate { f0 ->
                        f0.id to generateSequence(f0.id) { x -> folders.firstOrNull { it.id == x }?.parentId }.count()
                    }
                    scope.launch {
                        withContext(AppIo.serial) {
                            p.notebooks.forEach { repository.delete(it.id) }
                            // prima le sottocartelle: il contenuto risale una cartella alla volta
                            p.folders.sortedByDescending { depth[it.id] }.forEach { repository.deleteFolder(it.id) }
                        }
                        reload++
                    }
                }) { Text("Elimina", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } },
        )
    }

    renaming?.let { meta ->
        var title by remember(meta.id) { mutableStateOf(meta.title) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rinomina") },
            text = { OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    renaming = null
                    scope.launch {
                        withContext(AppIo.serial) { repository.rename(meta.id, title) }
                        reload++
                    }
                }) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Annulla") } },
        )
    }
}

internal val StarGold = Color(0xFFF0A500)

@Composable
private fun EmptyLibrary(inFolder: Boolean, favorites: Boolean, search: String?, tag: String?, recent: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                when {
                    search != null && search.isBlank() -> "Cerca fra tutti i quaderni"
                    search != null -> "Nessun risultato"
                    recent -> "Nessun quaderno recente"
                    tag != null -> "Nessun quaderno con il tag “$tag”"
                    favorites -> "Nessun preferito"
                    inFolder -> "Cartella vuota"
                    else -> "Nessun quaderno"
                },
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    search != null && search.isBlank() -> "Scrivi una parte del titolo, o il nome della cartella."
                    search != null -> "Nessun quaderno o cartella contiene “${search.trim()}”."
                    recent -> "I quaderni che apri compaiono qui, il più recente per primo."
                    tag != null -> "Tieni premuto un quaderno e tocca “Tag” in basso."
                    favorites -> "Tocca la stellina sotto un quaderno: lo ritrovi qui, da qualunque cartella."
                    else -> "Tocca “Nuovo quaderno” per iniziare a scrivere."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NotebookCard(
    meta: NotebookMeta,
    cover: File,
    /** Nella vista "Preferiti": la cartella del quaderno (null = radice). */
    folderName: String?,
    /** null = non si sta selezionando; true/false = scelto o no. */
    selected: Boolean?,
    tags: List<Tag>,
    /** Al posto della data di modifica (nei recenti: "aperto oggi, 10:32"). */
    whenText: String? = null,
    onToggleFavorite: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = if (selected == true) androidx.compose.foundation.BorderStroke(3.dp, InkBlue) else null,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .background(Color(0xFFEFF2F6)),
        ) {
            NotebookCover(cover, meta, Modifier.fillMaxSize().padding(14.dp))
            // tag in basso sulla copertina: la scheda resta alta uguale alle altre
            if (tags.isNotEmpty()) {
                Row(
                    Modifier.align(Alignment.BottomStart).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    tags.take(3).forEach { TagPill(it.name, it.color) }
                    if (tags.size > 3) TagPill("+${tags.size - 3}", 0xFF6B7480.toInt())
                }
            }
            if (selected != null) {
                SelectBadge(selected, Modifier.align(Alignment.TopStart))
                // durante la selezione la stellina si vede ma non si tocca
                if (meta.favorite) {
                    Icon(
                        TrattoIcons.StarFilled,
                        contentDescription = "Preferito",
                        tint = StarGold,
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    )
                }
            } else {
                // stellina nell'angolo della copertina: un tocco aggiunge/toglie dai preferiti
                IconButton(onClick = onToggleFavorite, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(
                        if (meta.favorite) TrattoIcons.StarFilled else TrattoIcons.Star,
                        contentDescription = if (meta.favorite) "Togli dai preferiti" else "Aggiungi ai preferiti",
                        tint = if (meta.favorite) StarGold else MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
                    )
                }
            }
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                meta.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(folderName, whenText ?: formatDate(meta.updatedAt), "${meta.pageCount} pag.").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun NewNotebookDialog(
    onDismiss: () -> Unit,
    onCreate: (String, PageFormat, PageStyle) -> Unit,
) {
    val defaultTitle = remember { "Appunti " + SimpleDateFormat("dd/MM/yyyy", Locale.ITALY).format(Date()) }
    var title by remember { mutableStateOf(defaultTitle) }
    var format by remember { mutableStateOf(PageFormat.A4_PORTRAIT) }
    var style by remember { mutableStateOf(PageStyle()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(680.dp),
        title = { Text("Nuovo quaderno") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Titolo") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row {
                    Box(Modifier.weight(1f)) { PageStyleEditor(format, style, onFormat = { format = it }, onStyle = { style = it }) }
                    Spacer(Modifier.width(20.dp))
                    PagePreview(format, style, Modifier.width(150.dp).height(210.dp))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title.trim(), format, style) }) { Text("Crea") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/** Copertina salvata (prima pagina con i tratti); se manca, anteprima dello sfondo. */
@Composable
private fun NotebookCover(file: File, meta: NotebookMeta, modifier: Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, file, meta.updatedAt) {
        value = withContext(Dispatchers.IO) {
            if (file.exists()) BitmapFactory.decodeFile(file.path)?.asImageBitmap() else null
        }
    }
    val b = bitmap
    if (b == null) {
        PagePreview(meta.format, meta.style, modifier)
        return
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        Box(Modifier.aspectRatio(meta.format.widthPt.toFloat() / meta.format.heightPt).border(0.5.dp, Color(0xFFD0D6DE))) {
            Image(b, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
    }
}

/** "22 ott, 10:26" quest'anno, "22 ott 2025" gli anni passati. */
internal fun formatDate(millis: Long): String {
    val year = SimpleDateFormat("yyyy", Locale.ITALY)
    val thisYear = year.format(Date()) == year.format(Date(millis))
    return SimpleDateFormat(if (thisYear) "d MMM, HH:mm" else "d MMM yyyy", Locale.ITALY).format(Date(millis))
}

/** Avanzamento dell'import: file [done] di [total], [fraction] del file corrente se nota. */
private class ImportProgress(val done: Int, val total: Int, val name: String, val fraction: Float? = null)

/** Riepilogo dopo un import: quaderni creati, file non riusciti (nome, motivo), cartella di arrivo. */
private class ImportSummary(val done: List<NotebookRepository.Imported>, val failed: List<Pair<String, String>>, val folder: String?) {
    /** C'è qualcosa da dire oltre a "fatto". */
    val hasNotes: Boolean get() = failed.isNotEmpty() || done.any { it.typedText || it.skipped > 0 }
}

/** Piano di riordino in attesa di conferma; [found] = quaderni del piano già presenti in Tratto. */
private data class PlanPreview(val plan: PlanFile.Plan, val found: Int)
