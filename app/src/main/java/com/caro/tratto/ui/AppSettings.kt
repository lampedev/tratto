package com.caro.tratto.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.caro.tratto.export.FolderSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Impostazioni generali dell'app, uguali per tutti i quaderni: quanti colori e quanti spessori
 * rapidi stanno nella barra degli strumenti, come si ordina la libreria e in che cartella si
 * sincronizza (FolderSync). Si apre dalla libreria e
 * dal menu ⋮ del foglio; ogni modifica è salvata subito e [onBarChanged] / [onSortChanged] la
 * portano nella schermata aperta.
 */
@Composable
internal fun AppSettingsDialog(
    onDismiss: () -> Unit,
    onBarChanged: (colors: Int, sizes: Int) -> Unit = { _, _ -> },
    onSortChanged: (LibrarySort, reversed: Boolean) -> Unit = { _, _ -> },
    onSyncTargetChanged: () -> Unit = {},
) {
    val context = LocalContext.current
    val prefs = remember { ToolPrefs(context) }
    var colors by remember { mutableIntStateOf(prefs.quickColorCount) }
    var sizes by remember { mutableIntStateOf(prefs.quickSizeCount) }
    val quickColors = remember { prefs.quickColors }
    val quickSizes = remember { prefs.quickSizes }
    var sort by remember { mutableStateOf(prefs.librarySort) }
    var reversed by remember { mutableStateOf(prefs.librarySortReversed) }
    val scope = rememberCoroutineScope()
    val folderSync = remember { FolderSync(context) }
    var syncTarget by remember { mutableStateOf(folderSync.target()) }
    val pickSyncFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) scope.launch {
            withContext(Dispatchers.IO) { runCatching { folderSync.setTarget(uri) } }
            syncTarget = folderSync.target()
            onSyncTargetChanged()
        }
    }

    fun change(c: Int, s: Int) {
        colors = c.coerceIn(0, MAX_QUICK_COLORS)
        sizes = s.coerceIn(0, MAX_QUICK_SIZES)
        prefs.quickColorCount = colors
        prefs.quickSizeCount = sizes
        onBarChanged(colors, sizes)
    }

    fun changeSort(s: LibrarySort, r: Boolean) {
        sort = s
        reversed = r
        prefs.librarySort = s
        prefs.librarySortReversed = r
        onSortChanged(s, r)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Impostazioni generali") },
        text = {
            Column(Modifier.width(420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    "Valgono per tutti i quaderni",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Barra degli strumenti",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
                )
                CountRow("Colori rapidi", colors, MAX_QUICK_COLORS, onChange = { change(it, sizes) })
                Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    quickColors.take(colors).forEach { c ->
                        Box(
                            Modifier
                                .padding(end = 4.dp)
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .border(1.dp, Color(0xFFD0D6DE), CircleShape),
                        )
                    }
                    if (colors == 0) NoneLabel("Nessuno in barra: la tavolozza completa resta nel pallino “+”")
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                CountRow("Spessori rapidi", sizes, MAX_QUICK_SIZES, onChange = { change(colors, it) })
                Row(Modifier.height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                    quickSizes.take(sizes).forEach { l ->
                        Box(Modifier.size(width = 38.dp, height = 30.dp), contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .size(width = 24.dp, height = (1f + l * 0.09f).dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(MaterialTheme.colorScheme.onSurface),
                            )
                        }
                    }
                    if (sizes == 0) NoneLabel("Nessuno in barra: lo spessore si sceglie toccando il numero")
                }
                Text(
                    "Nel foglio: un tocco sul pallino lo usa; toccalo di nuovo, o tienilo premuto, per cambiarne il colore o lo spessore. " +
                        "Se non ci stanno tutti in una riga, la barra va su due righe.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 14.dp),
                )
                Text(
                    "Libreria",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 22.dp, bottom = 4.dp),
                )
                Text("Ordina i quaderni per", style = MaterialTheme.typography.bodyLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibrarySort.entries.forEach { s ->
                        FilterChip(selected = sort == s, onClick = { changeSort(s, false) }, label = { Text(s.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false, true).forEach { r ->
                        FilterChip(
                            selected = reversed == r,
                            onClick = { changeSort(sort, r) },
                            label = { Text(sort.directionLabel(r)) },
                        )
                    }
                }
                Text(
                    "I preferiti stanno sempre in cima, ordinati allo stesso modo; le cartelle vengono prima dei quaderni.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
                SyncSettingsSection(
                    syncTarget,
                    onPick = { runCatching { pickSyncFolder.launch(null) } },
                    onUnlink = {
                        folderSync.clearTarget()
                        syncTarget = null
                        onSyncTargetChanged()
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fatto") } },
    )
}

@Composable
private fun CountRow(label: String, value: Int, max: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                "da 0 a $max",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OutlinedStep("−", enabled = value > 0) { onChange(value - 1) }
            Text(
                "$value",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(36.dp),
            )
            OutlinedStep("+", enabled = value < max) { onChange(value + 1) }
        }
    }
}

@Composable
private fun OutlinedStep(text: String, enabled: Boolean, onClick: () -> Unit) {
    OutlinedIconButton(onClick = onClick, enabled = enabled) {
        Text(text, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun NoneLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
