package com.caro.tratto.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.caro.tratto.model.Folder

/**
 * Percorso della cartella corrente: "I tuoi quaderni › Fisica › Lab" (ogni voce è toccabile).
 * [tail] = ultima voce che non è una cartella ("Preferiti").
 */
@Composable
internal fun Breadcrumb(path: List<Folder>, onGo: (String?) -> Unit, tail: String? = null) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val atRoot = path.isEmpty() && tail == null
        Text(
            "I tuoi quaderni",
            style = if (atRoot) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            color = if (atRoot) MaterialTheme.colorScheme.onBackground else InkBlue,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onGo(null) }.padding(horizontal = 8.dp, vertical = 4.dp),
        )
        path.forEachIndexed { i, f ->
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val last = i == path.lastIndex && tail == null
            Text(
                f.name,
                style = if (last) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                color = if (last) MaterialTheme.colorScheme.onBackground else InkBlue,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onGo(f.id) }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        if (tail != null) {
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                tail,
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Colori per le cartelle: null = il blu di serie. */
internal val FolderColors: List<Int?> = listOf(
    null, 0xFF5DADE2, 0xFF4DB6AC, 0xFF6DBE7A, 0xFFF2CC4D, 0xFFF0A04B,
    0xFFE06666, 0xFFE88AB4, 0xFF9C7FD1, 0xFFA1887F, 0xFF9AA4AE, 0xFF4A5560,
).map { it?.toInt() }

/** Le tre tinte di una cartella disegnata: linguetta (scura), retro, davanti (chiaro). */
internal class FolderTints(val tab: Color, val back: Color, val front: Color)

internal fun folderTints(color: Int?): FolderTints {
    if (color == null) return FolderTints(Color(0xFF6E8DB5), Color(0xFF8BA7CC), Color(0xFFA9C1E0))
    val base = Color(color)
    return FolderTints(lerp(base, Color.Black, 0.2f), base, lerp(base, Color.White, 0.35f))
}

/**
 * Scheda di una cartella: icona disegnata, nome e quanti elementi contiene.
 * [selected] null = non si sta selezionando; true/false = scelta o no (cerchietto nell'angolo).
 */
@Composable
internal fun FolderCard(
    folder: Folder,
    count: Int,
    selected: Boolean?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    /** Nei risultati della ricerca: dove sta la cartella ("Fisica › Unità 2"). */
    place: String? = null,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = if (selected == true) BorderStroke(3.dp, InkBlue) else null,
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(1.25f).background(Color(0xFFEFF2F6)), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize().padding(36.dp)) {
                val w = size.width
                val h = size.height * 0.78f
                val top = (size.height - h) / 2
                val r = CornerRadius(10.dp.toPx())
                // linguetta + corpo della cartella
                val t = folderTints(folder.color)
                drawRoundRect(t.tab, Offset(0f, top), Size(w * 0.45f, h * 0.3f), r)
                drawRoundRect(t.back, Offset(0f, top + h * 0.12f), Size(w, h * 0.88f), r)
                drawRoundRect(t.front, Offset(0f, top + h * 0.3f), Size(w, h * 0.7f), r)
            }
            if (selected != null) SelectBadge(selected, Modifier.align(Alignment.TopStart))
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(place, if (count == 1) "1 elemento" else "$count elementi").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Nome (e colore) per una cartella nuova o da rinominare. */
@Composable
internal fun FolderNameDialog(
    title: String,
    initial: String,
    initialColor: Int?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, color: Int?) -> Unit,
) {
    var name by remember { mutableStateOf(initial) }
    var color by remember { mutableStateOf(initialColor) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nome") })
                FolderColorGrid(color) { color = it }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name.trim(), color) }, enabled = name.isNotBlank()) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/** Solo il colore, anche per più cartelle insieme ([mixed]: hanno colori diversi, nessuno cerchiato). */
@Composable
internal fun FolderColorDialog(title: String, initial: Int?, mixed: Boolean, onDismiss: () -> Unit, onPick: (Int?) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { FolderColorGrid(initial, showSelected = !mixed, onSelect = onPick) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/** Tavolozza delle cartelle: due righe di cartelline colorate, quella scelta cerchiata. */
@Composable
private fun FolderColorGrid(selected: Int?, showSelected: Boolean = true, onSelect: (Int?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Colore", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FolderColors.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c ->
                    val isSel = showSelected && c == selected
                    Box(
                        Modifier
                            .size(46.dp)
                            .clip(CircleShape)
                            .border(if (isSel) 3.dp else 1.dp, if (isSel) InkBlue else Color(0xFFD0D6DE), CircleShape)
                            .clickable { onSelect(c) },
                        contentAlignment = Alignment.Center,
                    ) {
                        val t = folderTints(c)
                        Canvas(Modifier.width(24.dp).height(19.dp)) {
                            val r = CornerRadius(3.dp.toPx())
                            drawRoundRect(t.tab, Offset.Zero, Size(size.width * 0.45f, size.height * 0.35f), r)
                            drawRoundRect(t.front, Offset(0f, size.height * 0.2f), Size(size.width, size.height * 0.8f), r)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sceglie la cartella di destinazione (albero indentato, "I tuoi quaderni" = radice).
 * [exclude]: cartelle che si stanno spostando: né loro né le loro sottocartelle sono una meta.
 * [markCurrent]: la cartella dove stanno già gli elementi è evidenziata e non si tocca.
 */
@Composable
internal fun MoveToFolderDialog(
    folders: List<Folder>,
    current: String?,
    onDismiss: () -> Unit,
    exclude: Set<String> = emptySet(),
    markCurrent: Boolean = true,
    title: String = "Sposta in…",
    onMove: (String?) -> Unit,
) {
    // albero in ordine: ogni cartella seguita dalle sue sottocartelle
    val ordered = remember(folders, exclude) {
        val out = ArrayList<Pair<Folder, Int>>()
        fun visit(parent: String?, depth: Int) {
            folders.filter { it.parentId == parent && it.id !in exclude }.sortedBy { it.name.lowercase() }.forEach {
                out.add(it to depth)
                visit(it.id, depth + 1)
            }
        }
        visit(null, 0)
        out
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                FolderRow("I tuoi quaderni", 0, null, selected = markCurrent && current == null) { onMove(null) }
                ordered.forEach { (f, depth) -> FolderRow(f.name, depth + 1, f.color, selected = markCurrent && current == f.id) { onMove(f.id) } }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun FolderRow(name: String, depth: Int, color: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = !selected, onClick = onClick)
            .padding(start = (12 + depth * 20).dp, top = 12.dp, bottom = 12.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(20.dp).height(16.dp)) {
            drawRoundRect(folderTints(color).back, cornerRadius = CornerRadius(3.dp.toPx()))
        }
        Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
