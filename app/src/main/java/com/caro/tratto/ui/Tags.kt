package com.caro.tratto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.caro.tratto.model.Tag

/** Colori dei tag: quelli delle cartelle, più un blu acceso al posto del blu tenue di serie. */
internal val TagColors: List<Int> = listOf(0xFF3F7FD9.toInt()) + FolderColors.filterNotNull()

/** Etichetta colorata (sulle copertine e nei risultati). */
@Composable
internal fun TagPill(name: String, color: Int, modifier: Modifier = Modifier) {
    val bg = Color(color)
    Text(
        name,
        style = MaterialTheme.typography.labelSmall,
        color = if (bg.luminance() > 0.55f) Color(0xFF1B1F24) else Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .widthIn(max = 110.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
internal fun TagDot(color: Int, size: Int = 14) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(Color(color)))
}

/**
 * Tag dei quaderni scelti ([total] quaderni; [counts] = quanti di loro hanno ciascun tag).
 * Con [total] = 0 serve solo a gestire i tag (crea, rinomina, colore, elimina).
 */
@Composable
internal fun TagsDialog(
    tags: List<Tag>,
    counts: Map<String, Int>,
    total: Int,
    onToggle: (Tag, on: Boolean) -> Unit,
    onCreate: (name: String, color: Int) -> Unit,
    onEdit: (Tag) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by remember { mutableStateOf(tags.isEmpty()) }
    var newName by remember { mutableStateOf("") }
    var newColor by remember { mutableStateOf(TagColors[(tags.size) % TagColors.size]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (total == 0) "Tag" else if (total == 1) "Tag del quaderno" else "Tag di $total quaderni") },
        text = {
            Column(Modifier.width(440.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                if (tags.isEmpty()) {
                    Text(
                        "Nessun tag ancora: creane uno qui sotto, per esempio “Esame” o “Da ripassare”.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                tags.forEach { t ->
                    val n = counts[t.id] ?: 0
                    val state = when {
                        total == 0 || n == 0 -> ToggleableState.Off
                        n >= total -> ToggleableState.On
                        else -> ToggleableState.Indeterminate
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(enabled = total > 0) { onToggle(t, state != ToggleableState.On) }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (total > 0) TriStateCheckbox(state = state, onClick = { onToggle(t, state != ToggleableState.On) })
                        TagDot(t.color)
                        Text(
                            t.name,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(start = 12.dp),
                        )
                        IconButton(onClick = { onEdit(t) }) { Icon(TrattoIcons.Rename, contentDescription = "Modifica “${t.name}”") }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                if (!creating) {
                    TextButton(onClick = { creating = true }) { Text("+ Nuovo tag") }
                } else {
                    Text("Nuovo tag", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        singleLine = true,
                        label = { Text("Nome") },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                    TagColorRow(newColor, Modifier.padding(top = 10.dp)) { newColor = it }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        if (tags.isNotEmpty()) TextButton(onClick = { creating = false; newName = "" }) { Text("Annulla") }
                        TextButton(
                            onClick = {
                                onCreate(newName.trim(), newColor)
                                newName = ""
                                newColor = TagColors[(tags.size + 1) % TagColors.size]
                                creating = false
                            },
                            enabled = newName.isNotBlank(),
                        ) { Text(if (total > 0) "Crea e aggiungi" else "Crea") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Fatto") } },
    )
}

/** Rinomina, ricolora o elimina un tag ([usedBy] = su quanti quaderni è messo). */
@Composable
internal fun TagEditDialog(tag: Tag, usedBy: Int, onSave: (name: String, color: Int) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(tag.name) }
    var color by remember { mutableStateOf(tag.color) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Modifica tag") },
        text = {
            Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Nome") }, modifier = Modifier.fillMaxWidth())
                TagColorRow(color) { color = it }
                Text(
                    when (usedBy) {
                        -1 -> "Eliminandolo, i quaderni che ce l'hanno restano: perdono solo il tag."
                        0 -> "Non è messo su nessun quaderno."
                        1 -> "È messo su 1 quaderno. Eliminandolo, il quaderno resta: perde solo il tag."
                        else -> "È messo su $usedBy quaderni. Eliminandolo, i quaderni restano: perdono solo il tag."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onDelete) { Text("Elimina il tag", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), color) }, enabled = name.isNotBlank()) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun TagColorRow(selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TagColors.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { c ->
                    val sel = c == selected
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .border(if (sel) 3.dp else 0.dp, if (sel) InkBlue else Color.Transparent, CircleShape)
                            .padding(if (sel) 5.dp else 2.dp)
                            .clip(CircleShape)
                            .background(Color(c))
                            .clickable { onSelect(c) },
                    )
                }
            }
        }
    }
}
