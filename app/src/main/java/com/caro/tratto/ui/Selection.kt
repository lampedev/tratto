package com.caro.tratto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.caro.tratto.model.Folder
import com.caro.tratto.model.NotebookMeta

/** Elementi scelti con la selezione multipla della libreria. */
internal class Picked(val notebooks: List<NotebookMeta>, val folders: List<Folder>) {
    val size get() = notebooks.size + folders.size
}

internal fun <T> Set<T>.toggle(id: T) = if (id in this) this - id else this + id

/** Al posto del titolo, mentre si cerca: indietro e il campo di ricerca (la tastiera si apre da sola). */
@Composable
internal fun SearchRow(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // tornando dall'editor con una ricerca già scritta la tastiera resta chiusa
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 14.dp, end = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(TrattoIcons.Back, contentDescription = "Chiudi la ricerca") }
        Spacer(Modifier.width(8.dp))
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Cerca per titolo o cartella") },
            leadingIcon = { Icon(TrattoIcons.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) { Icon(TrattoIcons.Close, contentDescription = "Cancella") }
                }
            },
            shape = RoundedCornerShape(28.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
    }
}

/** Al posto del titolo, mentre si seleziona: chiudi, quanti, seleziona tutto. */
@Composable
internal fun SelectionHeader(count: Int, allSelected: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 12.dp, top = 14.dp, end = 16.dp).height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(TrattoIcons.Close, contentDescription = "Fine selezione") }
        Spacer(Modifier.width(8.dp))
        Text(
            if (count == 1) "1 selezionato" else "$count selezionati",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onSelectAll) { Text(if (allSelected) "Deseleziona tutto" else "Seleziona tutto") }
    }
}

/** Barra in basso con le azioni sulla selezione (quelle che non valgono restano grigie). */
@Composable
internal fun SelectionBar(
    picked: Picked,
    onFavorite: () -> Unit,
    onTags: () -> Unit,
    onRename: () -> Unit,
    onColor: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    val nb = picked.notebooks
    val allFav = nb.isNotEmpty() && nb.all { it.favorite }
    Surface(shadowElevation = 10.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BarAction(
                if (allFav) TrattoIcons.StarFilled else TrattoIcons.Star,
                if (allFav) "Togli dai preferiti" else "Preferiti",
                enabled = nb.isNotEmpty(),
                tint = StarGold,
                onClick = onFavorite,
            )
            BarAction(TrattoIcons.Tag, "Tag", enabled = nb.isNotEmpty(), onClick = onTags)
            BarAction(TrattoIcons.Rename, "Rinomina", enabled = picked.size == 1, onClick = onRename)
            BarAction(TrattoIcons.Palette, "Colore", enabled = picked.folders.isNotEmpty(), onClick = onColor)
            BarAction(TrattoIcons.MoveTo, "Sposta", enabled = picked.size > 0, onClick = onMove)
            BarAction(
                TrattoIcons.Trash,
                "Elimina",
                enabled = picked.size > 0,
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, enabled: Boolean, tint: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    }
}

/** Cerchietto nell'angolo della scheda: vuoto, o pieno con la spunta se scelta. */
@Composable
internal fun SelectBadge(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(10.dp)
            .size(28.dp)
            .clip(CircleShape)
            .background(if (selected) InkBlue else Color.White.copy(alpha = 0.92f))
            .border(2.dp, if (selected) InkBlue else Color(0xFF9AA4AE), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(TrattoIcons.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
    }
}
