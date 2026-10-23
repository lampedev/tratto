package com.caro.tratto.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.caro.tratto.data.PdfPages
import com.caro.tratto.export.PageRenderer
import com.caro.tratto.model.NotebookDocument
import com.caro.tratto.model.PageModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PageOps(
    val onOpen: (Int) -> Unit,
    val onInsertAfter: (Int) -> Unit,
    val onDuplicate: (Int) -> Unit,
    val onMove: (Int, Int) -> Unit,
    val onDelete: (Int) -> Unit,
)

/**
 * Tutte le pagine in griglia: tocca per andarci, ⋮ per inserire, duplicare, spostare, eliminare.
 * "Esporta pagine…" (o [startChoosing]) passa alla scelta delle pagine da esportare in un PDF.
 */
@Composable
internal fun PagesOverview(
    doc: NotebookDocument,
    current: Int,
    version: Int,
    ops: PageOps,
    onDismiss: () -> Unit,
    pdfPages: PdfPages? = null,
    startChoosing: Boolean = false,
    onExport: (List<Int>) -> Unit = {},
) {
    // copia della lista sul main thread: le miniature si disegnano in background
    val pages = remember(version) { doc.pages.toList() }
    var confirmDelete by remember { mutableStateOf<Int?>(null) }
    var choosing by remember { mutableStateOf(startChoosing) }
    var chosen by remember { mutableStateOf(emptySet<Int>()) }
    var range by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(28.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column {
                if (!choosing) {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Pagine · ${pages.size}", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { choosing = true }) { Text("Esporta pagine…") }
                        TextButton(onClick = onDismiss) { Text("Chiudi") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (chosen.isEmpty()) "Scegli le pagine da esportare" else "Pagine scelte: ${chosen.size}",
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { if (startChoosing) onDismiss() else { choosing = false; chosen = emptySet() } }) { Text("Annulla") }
                        Button(onClick = { onExport(chosen.sorted()) }, enabled = chosen.isNotEmpty()) { Text("Esporta PDF") }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = range,
                            onValueChange = { range = it },
                            singleLine = true,
                            placeholder = { Text("es. 1-5, 8") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            modifier = Modifier.width(180.dp),
                        )
                        TextButton(onClick = { chosen = chosen + parsePageRange(range, pages.size) }, enabled = range.isNotBlank()) { Text("Aggiungi") }
                        FilterChip(selected = false, onClick = { chosen = pages.indices.toSet() }, label = { Text("Tutte") })
                        FilterChip(
                            selected = false,
                            onClick = { chosen = chosen + pages.indices.filter { pages[it].strokes.isNotEmpty() || pages[it].images.isNotEmpty() } },
                            label = { Text("Con appunti") },
                        )
                        if (pages.any { it.bookmark != null }) {
                            FilterChip(
                                selected = false,
                                onClick = { chosen = chosen + pages.indices.filter { pages[it].bookmark != null } },
                                label = { Text("Con segnalibro") },
                            )
                        }
                        FilterChip(selected = false, onClick = { chosen = emptySet() }, label = { Text("Nessuna") })
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(20.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    itemsIndexed(pages, key = { _, p -> System.identityHashCode(p) }) { i, page ->
                        PageThumb(
                            doc, page, i, pages.size, i == current, version, ops, pdfPages,
                            chosen = if (choosing) i in chosen else null,
                            onChoose = { chosen = chosen.toggle(i) },
                        ) { confirmDelete = it }
                    }
                }
            }
        }
    }
    confirmDelete?.let { i ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Eliminare la pagina ${i + 1}?") },
            text = { Text("Potrai recuperarla con Annulla.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = null; ops.onDelete(i) }) {
                    Text("Elimina", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun PageThumb(
    doc: NotebookDocument,
    page: PageModel,
    index: Int,
    count: Int,
    current: Boolean,
    version: Int,
    ops: PageOps,
    pdfPages: PdfPages?,
    /** null = non si stanno scegliendo pagine; true/false = scelta o no. */
    chosen: Boolean?,
    onChoose: () -> Unit,
    onAskDelete: (Int) -> Unit,
) {
    // istantanea della sola pagina: immutabile mentre la miniatura si disegna su un altro thread
    val single = remember(page, version) { NotebookDocument(doc.meta, mutableListOf(PageModel(ArrayList(page.strokes), ArrayList(page.images), page.pdfPage, page.bookmark))) }
    val bitmap by produceState<ImageBitmap?>(null, single) {
        value = withContext(Dispatchers.Default) {
            PageRenderer.thumbnail(single, 0, 300, pdf = pdfPages?.let { p -> { i: Int -> p.renderBlocking(i, 300) } }).asImageBitmap()
        }
    }
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { if (chosen != null) onChoose() else ops.onOpen(index) }) {
        val marked = chosen ?: current
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(doc.format.widthPt.toFloat() / doc.format.heightPt)
                .background(Color.White)
                .border(if (marked) 3.dp else 0.5.dp, if (marked) InkBlue else Color(0xFFD0D6DE)),
        ) {
            bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            if (chosen != null) SelectBadge(chosen, Modifier.align(Alignment.TopStart))
            // nastrino dei segnalibri nell'angolo
            if (page.bookmark != null) {
                Icon(
                    TrattoIcons.BookmarkFilled,
                    contentDescription = bookmarkLabel(page.bookmark, index),
                    tint = InkBlue,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp).size(22.dp),
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = if (current) InkBlue else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 4.dp),
            )
            Spacer(Modifier.weight(1f))
            if (chosen == null) Box {
                IconButton(onClick = { menu = true }) { Icon(TrattoIcons.More, "Opzioni pagina") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Inserisci pagina dopo") }, onClick = { menu = false; ops.onInsertAfter(index) })
                    DropdownMenuItem(text = { Text("Duplica") }, onClick = { menu = false; ops.onDuplicate(index) })
                    DropdownMenuItem(
                        text = { Text("Sposta prima") },
                        enabled = index > 0,
                        onClick = { menu = false; ops.onMove(index, index - 1) },
                    )
                    DropdownMenuItem(
                        text = { Text("Sposta dopo") },
                        enabled = index < count - 1,
                        onClick = { menu = false; ops.onMove(index, index + 1) },
                    )
                    DropdownMenuItem(
                        text = { Text("Elimina", color = MaterialTheme.colorScheme.error) },
                        enabled = count > 1,
                        onClick = { menu = false; onAskDelete(index) },
                    )
                }
            }
        }
    }
}

/** "1-5, 8, 10-12" → indici delle pagine (da 0); numeri fuori dal quaderno e pezzi non validi si saltano. */
internal fun parsePageRange(text: String, count: Int): Set<Int> {
    val out = HashSet<Int>()
    for (part in text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }) {
        val bounds = part.split('-', '–').map { it.trim().toIntOrNull() }
        val (a, b) = when {
            bounds.size == 1 && bounds[0] != null -> bounds[0]!! to bounds[0]!!
            bounds.size == 2 && bounds[0] != null && bounds[1] != null -> bounds[0]!! to bounds[1]!!
            else -> continue
        }
        for (p in minOf(a, b)..maxOf(a, b)) if (p in 1..count) out += p - 1
    }
    return out
}
