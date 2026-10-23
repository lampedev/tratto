package com.caro.tratto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import android.graphics.Color as AColor

/** I colori più comuni, in testa a ogni scelta di colore. */
internal val CommonColors = listOf(
    0xFF000000, 0xFF424242, 0xFF9E9E9E, 0xFFFFFFFF, 0xFF0D2C6B, 0xFF1E63D6, 0xFF03A9F4, 0xFF00897B,
    0xFF2E7D32, 0xFF7CB342, 0xFFFBC02D, 0xFFEF6C00, 0xFFD32F2F, 0xFFE91E63, 0xFF7B1FA2, 0xFF6D4C41,
).map { it.toInt() }

/**
 * Tabella delle sfumature: una colonna per tinta (12), dal chiaro allo scuro, più una riga di grigi
 * dal bianco al nero.
 */
internal val ColorTableRows: List<List<Int>> = run {
    val hues = listOf(0f, 22f, 40f, 54f, 85f, 125f, 168f, 192f, 212f, 232f, 268f, 310f)
    val greys = (0 until 12).map { i ->
        val v = (255 * (1f - i / 11f)).toInt()
        AColor.rgb(v, v, v)
    }
    val tones = listOf(0.92f to 0.85f, 0.82f to 0.9f, 0.68f to 0.9f, 0.54f to 0.85f, 0.44f to 0.8f, 0.34f to 0.75f, 0.24f to 0.7f, 0.15f to 0.65f)
    listOf(greys) + tones.map { (l, s) -> hues.map { h -> ColorUtils.HSLToColor(floatArrayOf(h, s, l)) } }
}

/** Selettore colore completo in finestra: tavolozza (comuni + tabella) o spettro, con codice esadecimale. */
@Composable
fun ColorPickerDialog(
    initial: Int,
    withAlpha: Boolean,
    onDismiss: () -> Unit,
    onPick: (Int) -> Unit,
) {
    var color by remember { mutableIntStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scegli colore") },
        text = {
            FullColorChooser(color, withAlpha, onChange = { color = it })
        },
        confirmButton = { TextButton(onClick = { onPick(color) }) { Text("Usa colore") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/**
 * Colori comuni + "Tavolozza" (tabella delle sfumature) oppure "Spettro" (quadrato HSV, tonalità, codice).
 * [color] cambia mentre si sceglie: chi lo usa lo applica subito.
 */
@Composable
internal fun FullColorChooser(
    color: Int,
    withAlpha: Boolean,
    onChange: (Int) -> Unit,
    extra: List<Int> = emptyList(),
    onSpectrumUsed: () -> Unit = {},
) {
    var spectrum by remember { mutableStateOf(false) }
    // la tavolozza è opaca: l'evidenziatore tiene la sua trasparenza
    fun pick(c: Int) = onChange(if (withAlpha) (c and 0xFFFFFF) or (color and 0xFF000000.toInt()) else c)
    fun same(a: Int, b: Int) = (a and 0xFFFFFF) == (b and 0xFFFFFF)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(width = 44.dp, height = 30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(1.dp, Color(0xFFD0D6DE), RoundedCornerShape(8.dp))
                    .background(Color(color)),
            )
            FilterChip(selected = !spectrum, onClick = { spectrum = false }, label = { Text("Tavolozza") })
            FilterChip(selected = spectrum, onClick = { spectrum = true }, label = { Text("Spettro") })
        }
        CommonColors.chunked(8).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c -> ColorDot(c, same(color, c), size = 32) { pick(c) } }
            }
        }
        if (extra.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                extra.take(8).forEach { c -> ColorDot(c, same(color, c), size = 32) { pick(c) } }
            }
        }
        if (spectrum) {
            HsvPicker(color, withAlpha, onChange = { onSpectrumUsed(); onChange(it) })
            HexField(color) { onSpectrumUsed(); pick(it) }
        } else {
            ColorTable(selected = color, onPick = ::pick)
        }
    }
}

/** La tabella delle sfumature: tocca una cella per scegliere quel colore. */
@Composable
internal fun ColorTable(selected: Int, onPick: (Int) -> Unit, cell: Dp = 27.dp) {
    Column(Modifier.clip(RoundedCornerShape(8.dp))) {
        ColorTableRows.forEach { row ->
            Row {
                row.forEach { c ->
                    val on = (selected and 0xFFFFFF) == (c and 0xFFFFFF)
                    Box(
                        Modifier
                            .size(cell)
                            .background(Color(c))
                            .then(if (on) Modifier.border(2.5.dp, if (ColorUtils.calculateLuminance(c) > 0.5) Color.Black else Color.White) else Modifier)
                            .clickable { onPick(c) },
                    )
                }
            }
        }
    }
}

/**
 * Quadrato saturazione/luminosità, barra della tonalità e (con [withAlpha]) dell'opacità.
 * Il colore cambia mentre si trascina; se [color] arriva da fuori (tavolozza, codice) si riallinea.
 */
@Composable
internal fun HsvPicker(color: Int, withAlpha: Boolean, onChange: (Int) -> Unit, squareHeight: Dp = 190.dp) {
    val start = remember { FloatArray(3).also { AColor.colorToHSV(color, it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    var alpha by remember { mutableFloatStateOf(if (withAlpha) AColor.alpha(color) / 255f else 1f) }
    val emitted = remember { intArrayOf(color) }

    LaunchedEffect(color) {
        if (color != emitted[0]) {
            val out = FloatArray(3)
            AColor.colorToHSV(color, out)
            // grigi: la tonalità non conta, si tiene quella che c'era
            if (out[1] > 0.01f && out[2] > 0.01f) hue = out[0]
            sat = out[1]; value = out[2]
            if (withAlpha) alpha = AColor.alpha(color) / 255f
            emitted[0] = color
        }
    }

    fun emit() {
        val c = AColor.HSVToColor((alpha * 255).toInt(), floatArrayOf(hue, sat, value))
        emitted[0] = c
        onChange(c)
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(squareHeight)
                .clip(RoundedCornerShape(12.dp))
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun update(p: Offset) {
                            sat = (p.x / size.width).coerceIn(0f, 1f)
                            value = 1f - (p.y / size.height).coerceIn(0f, 1f)
                            emit()
                        }
                        update(down.position)
                        drag(down.id) { change ->
                            update(change.position)
                            change.consume()
                        }
                    }
                },
        ) {
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color.hsv(hue, 1f, 1f))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
            val c = Offset(sat * size.width, (1f - value) * size.height)
            drawCircle(Color.White, radius = 11.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
            drawCircle(Color.Black.copy(alpha = 0.4f), radius = 12.5.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
        }

        GradientSlider(
            brush = Brush.horizontalGradient(
                listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it % 360f, 1f, 1f) },
            ),
            position = hue / 360f,
            onChange = { hue = it * 359.9f; emit() },
        )

        if (withAlpha) {
            GradientSlider(
                brush = Brush.horizontalGradient(
                    listOf(Color.hsv(hue, sat, value, 0f), Color.hsv(hue, sat, value, 1f)),
                ),
                position = alpha,
                onChange = { alpha = it.coerceIn(0.1f, 1f); emit() },
            )
        }
    }
}

/** Codice #RRGGBB: si aggiorna col colore e, quando è valido, lo imposta. */
@Composable
internal fun HexField(color: Int, onChange: (Int) -> Unit) {
    var text by remember { mutableStateOf(toHex(color)) }
    LaunchedEffect(color) {
        if (parseHex(text)?.let { it and 0xFFFFFF } != color and 0xFFFFFF) text = toHex(color)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { t ->
            text = t
            parseHex(t)?.let(onChange)
        },
        label = { Text("Codice #RRGGBB") },
        singleLine = true,
        modifier = Modifier.width(200.dp),
    )
}

@Composable
private fun GradientSlider(brush: Brush, position: Float, onChange: (Float) -> Unit) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    onChange((down.position.x / size.width).coerceIn(0f, 1f))
                    drag(down.id) { change ->
                        onChange((change.position.x / size.width).coerceIn(0f, 1f))
                        change.consume()
                    }
                }
            },
    ) {
        val r = size.height / 2
        drawRoundRect(brush, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r), size = Size(size.width, size.height))
        val x = (position * size.width).coerceIn(r, size.width - r)
        drawCircle(Color.White, radius = r - 2.dp.toPx(), center = Offset(x, r), style = Stroke(3.dp.toPx()))
    }
}

private fun toHex(c: Int) = "#%06X".format(c and 0xFFFFFF)

private fun parseHex(text: String): Int? {
    val t = text.trim().removePrefix("#")
    if (t.length != 6) return null
    return t.toLongOrNull(16)?.let { (0xFF000000 or it).toInt() }
}
