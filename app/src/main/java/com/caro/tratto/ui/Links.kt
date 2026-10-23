package com.caro.tratto.ui

import android.graphics.PointF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.caro.tratto.data.NotebookRepository
import com.caro.tratto.data.PdfPages
import com.caro.tratto.export.PageRenderer
import com.caro.tratto.model.NotebookDocument
import com.caro.tratto.model.NotebookMeta
import com.caro.tratto.model.PageModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Dove porta un collegamento appena scelto: quaderno (null = questo), pagina, punto (null = pagina intera). */
internal class LinkChoice(val notebookId: String?, val pageId: String, val pageIndex: Int, val point: PointF?)

/**
 * Scelta della destinazione di un collegamento: si sfoglia l'albero delle cartelle (o si cerca),
 * si tocca un quaderno e lo si collega tutto; solo se serve si sceglie anche una pagina.
 */
@Composable
internal fun LinkTargetDialog(
    repository: NotebookRepository,
    current: NotebookDocument,
    currentPdf: PdfPages?,
    onDismiss: () -> Unit,
    onChoose: (LinkChoice) -> Unit,
) {
    var notebook by remember { mutableStateOf<NotebookMeta?>(null) }
    var choosingPage by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<NotebookDocument?>(null) }
    val isCurrent = notebook?.id == current.meta.id

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            notebook == null -> "Collega a…"
                            !choosingPage -> "Collega a “${notebook?.title}”"
                            else -> "Collega a una pagina di “${notebook?.title}”"
                        },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (notebook != null) {
                        TextButton(onClick = { if (choosingPage) choosingPage = false else notebook = null }) { Text("Indietro") }
                    }
                    TextButton(onClick = onDismiss) { Text("Annulla") }
                }
                val nb = notebook
                when {
                    nb == null -> NotebookBrowser(repository, current.meta) { notebook = it }
                    !choosingPage -> Column(
                        Modifier.fillMaxWidth().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            if (nb.id == current.meta.id) "È il quaderno che hai aperto." else if (nb.pageCount == 1) "1 pagina" else "${nb.pageCount} pagine",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { onChoose(LinkChoice(if (nb.id == current.meta.id) null else nb.id, "", 0, null)) },
                            modifier = Modifier.width(360.dp),
                        ) { Text("Collega al quaderno") }
                        OutlinedButton(onClick = { choosingPage = true }, modifier = Modifier.width(360.dp)) { Text("Scegli una pagina…") }
                        Text(
                            "Il collegamento al quaderno lo apre dalla prima pagina; scegliendo una pagina, si apre proprio lì.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        // il quaderno di arrivo: quello aperto c'è già, un altro si carica per le miniature
                        LaunchedEffect(nb.id) {
                            target = if (nb.id == current.meta.id) current else withContext(Dispatchers.Default) {
                                runCatching { repository.build(repository.readRaw(nb.id)).doc }.getOrNull()
                            }
                        }
                        val t = target
                        val pdf = remember(nb.id) {
                            if (nb.id == current.meta.id) currentPdf
                            else repository.pdfFile(nb.id).takeIf { it.exists() }?.let { PdfPages(it) }
                        }
                        DisposableEffect(pdf) { onDispose { if (!isCurrent && pdf !== currentPdf) pdf?.close() } }
                        if (t == null || t.meta.id != nb.id) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        } else {
                            PagePicker(t, pdf) { i ->
                                onChoose(LinkChoice(if (nb.id == current.meta.id) null else nb.id, t.pages[i].id, i, null))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Le cartelle come in libreria: si entra e si esce (percorso in alto), oppure si cerca fra tutti
 * i quaderni. In cima, sempre, il quaderno aperto (per i collegamenti a una sua pagina).
 */
@Composable
private fun NotebookBrowser(repository: NotebookRepository, current: NotebookMeta, onPick: (NotebookMeta) -> Unit) {
    var query by remember { mutableStateOf("") }
    var folderId by remember { mutableStateOf<String?>(null) }
    val data by produceState<Pair<List<NotebookMeta>, List<com.caro.tratto.model.Folder>>?>(null) {
        value = withContext(AppIo.serial) { repository.list() to repository.folders() }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Cerca un quaderno") },
            leadingIcon = { Icon(TrattoIcons.Search, null) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        )
        val (list, folders) = data ?: return@Column
        val byId = folders.associateBy { it.id }
        val places = folders.associate { f -> f.id to generateSequence(f) { byId[it.parentId ?: ""] }.toList().asReversed().joinToString(" › ") { it.name } }
        val order = naturalTitleOrder()
        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
            if (query.isNotBlank()) {
                val found = list.filter { matchesSearch(query, it.title, it.folderId?.let(places::get)) }.sortedWith(compareBy(order) { it.title })
                if (found.isEmpty()) item { BrowserHint("Nessun quaderno contiene “${query.trim()}”.") }
                items(found, key = { it.id }) { nb -> NotebookRow(nb, nb.folderId?.let(places::get) ?: "I tuoi quaderni", nb.id == current.id) { onPick(nb) } }
            } else {
                if (folderId == null) {
                    item(key = "current") { NotebookRow(current, "Il quaderno aperto", true) { onPick(current) } }
                }
                item(key = "path") {
                    // percorso: "I tuoi quaderni › Fisica", ogni voce si tocca per risalire
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        val path = generateSequence(folderId?.let(byId::get)) { byId[it.parentId ?: ""] }.toList().asReversed()
                        Text(
                            "I tuoi quaderni",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (path.isEmpty()) MaterialTheme.colorScheme.onSurface else InkBlue,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { folderId = null }.padding(4.dp),
                        )
                        path.forEachIndexed { i, f ->
                            Text(" › ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                f.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (i == path.lastIndex) MaterialTheme.colorScheme.onSurface else InkBlue,
                                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { folderId = f.id }.padding(4.dp),
                            )
                        }
                    }
                }
                val subFolders = folders.filter { it.parentId == folderId }.sortedWith(compareBy(order) { it.name })
                val here = list.filter { it.folderId == folderId }.sortedWith(compareBy(order) { it.title })
                if (subFolders.isEmpty() && here.isEmpty()) item { BrowserHint("Cartella vuota.") }
                items(subFolders, key = { "f_" + it.id }) { f ->
                    val count = folders.count { it.parentId == f.id } + list.count { it.folderId == f.id }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { folderId = f.id }.padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Canvas(Modifier.width(26.dp).height(20.dp)) {
                            val t = folderTints(f.color)
                            val r = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
                            drawRoundRect(t.tab, Offset.Zero, androidx.compose.ui.geometry.Size(size.width * 0.45f, size.height * 0.35f), r)
                            drawRoundRect(t.back, Offset(0f, size.height * 0.2f), androidx.compose.ui.geometry.Size(size.width, size.height * 0.8f), r)
                        }
                        Text(f.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 14.dp))
                        Text(if (count == 1) "1 elemento ›" else "$count elementi ›", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(here, key = { it.id }) { nb -> NotebookRow(nb, "${nb.pageCount} pag.", nb.id == current.id) { onPick(nb) } }
            }
        }
    }
}

@Composable
private fun NotebookRow(nb: NotebookMeta, subtitle: String, isCurrent: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TrattoIcons.Page, null, tint = if (isCurrent) InkBlue else MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(nb.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BrowserHint(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp))
}

/** Ordine per titolo come in libreria ("Lezione 2" prima di "Lezione 10"). */
private fun naturalTitleOrder(): Comparator<String> {
    val c = java.text.Collator.getInstance(java.util.Locale.ITALIAN).apply { strength = java.text.Collator.SECONDARY }
    return Comparator { a, b -> c.compare(a, b) }
}

@Composable
private fun PagePicker(doc: NotebookDocument, pdf: PdfPages?, onPick: (Int) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(140.dp),
        contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        itemsIndexed(doc.pages, key = { _, p -> p.id }) { i, page ->
            Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { onPick(i) }) {
                PageImageBox(doc, page, 280, pdf, Modifier.fillMaxWidth())
                Text(
                    listOfNotNull("${i + 1}", page.bookmark?.let { bookmarkLabel(it, i) }).joinToString(" · "),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }
        }
    }
}

/** Miniatura di una pagina larga [px] pixel (disegnata in background da una copia della pagina). */
@Composable
private fun PageImageBox(doc: NotebookDocument, page: PageModel, px: Int, pdf: PdfPages?, modifier: Modifier) {
    val single = remember(page) { NotebookDocument(doc.meta, mutableListOf(PageModel(ArrayList(page.strokes), ArrayList(page.images), page.pdfPage))) }
    val bitmap by produceState<ImageBitmap?>(null, single, px) {
        value = withContext(Dispatchers.Default) {
            runCatching { PageRenderer.thumbnail(single, 0, px, pdf = pdf?.let { p -> { i: Int -> p.renderBlocking(i, px) } }).asImageBitmap() }.getOrNull()
        }
    }
    Box(
        modifier
            .aspectRatio(doc.format.widthPt.toFloat() / doc.format.heightPt)
            .background(Color.White)
            .border(0.5.dp, Color(0xFFD0D6DE)),
    ) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
    }
}

/** Fumetto sopra un collegamento toccato: dove porta, "Apri" e "Togli". */
@Composable
internal fun LinkBubble(label: String, onOpen: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(14.dp), shadowElevation = 8.dp, modifier = modifier) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp)) {
            Text("Collegamento a", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn280())
            Row {
                TextButton(onClick = onOpen) { Text("Apri") }
                TextButton(onClick = onRemove) { Text("Togli", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

private fun Modifier.widthIn280() = this.then(Modifier.width(280.dp))

/** "← Torna a …" dopo aver seguito un collegamento. */
@Composable
internal fun BackChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(20.dp), shadowElevation = 6.dp, color = InkBlue, modifier = modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Row(Modifier.padding(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(TrattoIcons.Back, null, tint = Color.White)
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 6.dp))
        }
    }
}
