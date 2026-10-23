package com.caro.tratto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.caro.tratto.data.PdfOutline
import com.caro.tratto.model.PageModel

/** Nome da mostrare per il segnalibro di una pagina. */
internal fun bookmarkLabel(name: String?, index: Int) = name?.takeIf { it.isNotBlank() } ?: "Pagina ${index + 1}"

/**
 * Segnalibri del quaderno: quello della pagina corrente (aggiungi, rinomina, togli), l'elenco dei
 * propri segnalibri e, per un quaderno nato da un PDF, l'indice del PDF. Un tocco porta alla pagina.
 * [outline]: null = non è un PDF; [outlineLoading] mentre si legge l'indice.
 */
@Composable
internal fun BookmarksDialog(
    pages: List<PageModel>,
    /** Cresce a ogni modifica del quaderno: le pagine sono mutabili, così il pannello si ridisegna. */
    version: Int,
    current: Int,
    outline: List<PdfOutline.Item>?,
    outlineLoading: Boolean,
    onJump: (Int) -> Unit,
    onSet: (index: Int, name: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // letti di nuovo a ogni modifica: la lista delle pagine è sempre la stessa, cambiano le pagine dentro
    val here = remember(version, current) { pages.getOrNull(current)?.bookmark }
    val marked = remember(version) { pages.withIndex().filter { it.value.bookmark != null }.map { it.index to it.value.bookmark } }
    var editing by remember(current, here) { mutableStateOf(here == null) }
    var name by remember(current, here) { mutableStateOf(here.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Segnalibri") },
        text = {
            Column(Modifier.width(480.dp).heightIn(max = 600.dp).verticalScroll(rememberScrollState())) {
                Text("Questa pagina (${current + 1})", style = MaterialTheme.typography.titleSmall)
                if (here != null && !editing) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(TrattoIcons.BookmarkFilled, null, tint = InkBlue, modifier = Modifier.size(20.dp))
                        Text(
                            bookmarkLabel(here, current),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        TextButton(onClick = { editing = true }) { Text("Rinomina") }
                        TextButton(onClick = { onSet(current, null) }) { Text("Togli", color = MaterialTheme.colorScheme.error) }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            placeholder = { Text("Nome (facoltativo)") },
                            modifier = Modifier.weight(1f),
                        )
                        Button(onClick = { onSet(current, name.trim()); editing = false }, modifier = Modifier.padding(start = 8.dp)) {
                            Text(if (here == null) "Aggiungi" else "Salva")
                        }
                    }
                }

                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("I tuoi segnalibri", style = MaterialTheme.typography.titleSmall)
                if (marked.isEmpty()) {
                    Hint("Nessuno ancora: aggiungine uno alla pagina che vuoi ritrovare.")
                }
                marked.forEach { (i, label) ->
                    JumpRow(bookmarkLabel(label, i), i, level = 0, selected = i == current, bookmark = true) { onJump(i) }
                }

                if (outline != null) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text("Indice del PDF", style = MaterialTheme.typography.titleSmall)
                    when {
                        outlineLoading -> Hint("Leggo l'indice del PDF…")
                        outline.isEmpty() -> Hint("Questo PDF non ha un indice: usa i tuoi segnalibri.")
                        else -> outline.forEach { item ->
                            // la pagina del PDF dentro il quaderno (le pagine si possono spostare o eliminare)
                            val target = if (item.pdfPage < 0) -1 else pages.indexOfFirst { it.pdfPage == item.pdfPage }
                            JumpRow(item.title, target, item.level, selected = target == current, bookmark = false) {
                                if (target >= 0) onJump(target)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } },
    )
}

@Composable
private fun JumpRow(title: String, page: Int, level: Int, selected: Boolean, bookmark: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = page >= 0, onClick = onClick)
            .padding(start = (8 + 18 * level).dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (bookmark) Icon(TrattoIcons.BookmarkFilled, null, tint = InkBlue, modifier = Modifier.size(16.dp))
        Text(
            title,
            style = if (level == 0) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (page >= 0) "pag. ${page + 1}" else "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}
