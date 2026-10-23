package com.caro.tratto.ui

import android.graphics.RectF
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Azioni sulla selezione del lasso, posizionate sopra (o sotto) il riquadro selezionato. */
@Composable
internal fun SelectionBar(
    rect: RectF,
    area: IntSize,
    onRecolor: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDone: () -> Unit,
    onLink: () -> Unit = {},
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var colorsOpen by remember { mutableStateOf(false) }
    val gap = with(LocalDensity.current) { 14.dp.roundToPx() }
    Surface(
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 6.dp,
        modifier = Modifier
            .offset {
                val x = (rect.centerX() - size.width / 2f).roundToInt()
                    .coerceIn(0, (area.width - size.width).coerceAtLeast(0))
                val above = rect.top.roundToInt() - size.height - gap
                val y = if (above >= 0) above else (rect.bottom.roundToInt() + gap)
                    .coerceAtMost((area.height - size.height).coerceAtLeast(0))
                IntOffset(x, y)
            }
            .onSizeChanged { size = it },
    ) {
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Box {
                TextButton(onClick = { colorsOpen = true }) { Text("Colore") }
                DropdownMenu(expanded = colorsOpen, onDismissRequest = { colorsOpen = false }) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Palette.ink.chunked(8).forEach { row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                                row.forEach { c -> ColorDot(c, false, size = 30) { colorsOpen = false; onRecolor(c) } }
                            }
                        }
                    }
                }
            }
            TextButton(onClick = onLink) { Text("Collega a…") }
            TextButton(onClick = onCopy) { Text("Copia") }
            TextButton(onClick = onDuplicate) {
                Icon(TrattoIcons.Copy, null, Modifier.padding(end = 6.dp))
                Text("Duplica")
            }
            TextButton(onClick = onDelete) {
                Icon(TrattoIcons.Trash, null, Modifier.padding(end = 6.dp), tint = MaterialTheme.colorScheme.error)
                Text("Elimina", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onDone) { Text("Fine") }
        }
    }
}
