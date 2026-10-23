package com.caro.tratto.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caro.tratto.ink.Brushes
import com.caro.tratto.model.ToolKind
import com.caro.tratto.model.ToolSettings

/**
 * Barra verticale delle penne preferite sul bordo destro (stile Samsung Notes).
 * Tocco = usa la penna; pressione lunga = rimuovi; "+" = aggiungi la penna attiva.
 */
@Composable
internal fun FavoritesSideBar(
    favorites: List<ToolSettings>,
    active: ToolSettings?,
    canAdd: Boolean,
    onApply: (ToolSettings) -> Unit,
    onRemove: (ToolSettings) -> Unit,
    onAddCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.width(52.dp).verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            favorites.forEach { fav -> SideFavorite(fav, selected = fav == active, onApply = { onApply(fav) }, onRemove = { onRemove(fav) }) }
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(1.5.dp, if (canAdd) Color(0xFF9AA4B2) else Color(0xFFD7DDE5), CircleShape)
                    .clickable(enabled = canAdd, onClick = onAddCurrent),
                contentAlignment = Alignment.Center,
            ) {
                Icon(TrattoIcons.Plus, contentDescription = "Aggiungi la penna attiva", tint = if (canAdd) InkBlue else Color(0xFFB0B8C4))
            }
        }
    }
}

@Composable
private fun SideFavorite(fav: ToolSettings, selected: Boolean, onApply: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .padding(vertical = 3.dp)
                .size(44.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .combinedClickable(onClick = onApply, onLongClick = { menu = true }),
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (fav.kind == ToolKind.HIGHLIGHTER) Color(fav.color or 0xFF000000.toInt()) else Color(fav.color)
            Icon(iconFor(fav.kind), contentDescription = fav.kind.label, tint = tint)
            Text(
                "${Brushes.levelForSize(fav.kind, fav.sizePt)}",
                fontSize = 9.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 3.dp),
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text("Rimuovi dai preferiti") },
                leadingIcon = { Icon(TrattoIcons.Trash, null) },
                onClick = { menu = false; onRemove() },
            )
        }
    }
}
