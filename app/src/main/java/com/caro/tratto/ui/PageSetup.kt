package com.caro.tratto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.caro.tratto.model.LineSpacing
import com.caro.tratto.model.PageBackground
import com.caro.tratto.model.PageBackgroundPainter
import com.caro.tratto.model.PageFormat
import com.caro.tratto.model.PageStyle
import com.caro.tratto.model.PaperColor

/**
 * Miniatura del foglio, disegnata con lo stesso PageBackgroundPainter di schermo e PDF.
 * [zoom] > 1 mostra ingrandito l'angolo in alto a sinistra: nelle miniature piccole il motivo
 * resta leggibile invece di diventare una macchia grigia.
 */
@Composable
fun PagePreview(format: PageFormat, style: PageStyle, modifier: Modifier = Modifier, zoom: Float = 1f) {
    val ratio = format.widthPt.toFloat() / format.heightPt
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.aspectRatio(ratio).clipToBounds().border(0.5.dp, Color(0xFFD0D6DE))) {
            val k = size.width / format.widthPt * zoom
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.scale(k, k)
                PageBackgroundPainter.paint(nc, format, style, minLinePt = 1f / k)
                nc.restore()
            }
        }
    }
}

/** Formato, motivo, colore carta, spaziatura e margine. */
@Composable
fun PageStyleEditor(
    format: PageFormat,
    style: PageStyle,
    onFormat: (PageFormat) -> Unit,
    onStyle: (PageStyle) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val pdf = format.name == PageFormat.PDF
        Label("Formato")
        if (pdf) {
            Text(
                "Come le pagine del PDF (${format.widthPt}×${format.heightPt} pt). Motivo e colore valgono per le pagine che aggiungi.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PageFormat.entries.forEach { f ->
                    FilterChip(selected = format == f, onClick = { onFormat(f) }, label = { Text(f.label) })
                }
            }
        }

        Label(if (pdf) "Motivo delle pagine aggiunte" else "Motivo")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageBackground.entries.forEach { b ->
                val selected = style.background == b
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .border(
                            if (selected) 2.dp else 1.dp,
                            if (selected) InkBlue else Color(0xFFD7DDE5),
                            RoundedCornerShape(10.dp),
                        )
                        .clickable { onStyle(style.copy(background = b)) }
                        .padding(6.dp),
                ) {
                    PagePreview(format, style.copy(background = b), Modifier.size(width = 50.dp, height = 58.dp), zoom = 3f)
                    Spacer(Modifier.height(3.dp))
                    Text(b.label, style = MaterialTheme.typography.labelSmall)
                }
            }
        }

        Label("Colore carta · ${style.paper.label}")
        var picking by remember { mutableStateOf(false) }
        // prima riga: i più usati (bianca, nera OLED, blu progetto, grafite) e il colore libero
        listOf(PaperColor.entries.take(4), PaperColor.entries.drop(4).filter { it != PaperColor.CUSTOM }).forEachIndexed { row, papers ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                papers.forEach { p ->
                    PaperDot(Color(p.argb), style.paper == p) { onStyle(style.copy(paper = p)) }
                }
                if (row == 0) {
                    val custom = style.paper == PaperColor.CUSTOM
                    if (custom) PaperDot(Color(style.customPaper), true) { picking = true }
                    else AddColorButton(diameter = 34) { picking = true }
                    Text("Scegli…", style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { picking = true })
                }
            }
        }
        if (picking) {
            ColorPickerDialog(
                initial = style.paperArgb,
                withAlpha = false,
                onDismiss = { picking = false },
                onPick = { c ->
                    picking = false
                    onStyle(style.copy(paper = PaperColor.CUSTOM, customPaper = c))
                },
            )
        }

        val hasPattern = style.background != PageBackground.BLANK
        Label("Spaziatura")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // la spaziatura di Samsung Notes compare solo nei quaderni importati che la usano
            LineSpacing.entries.filter { it.samsungSize == 0 || it == style.spacing }.forEach { s ->
                FilterChip(
                    selected = style.spacing == s,
                    enabled = hasPattern,
                    onClick = { onStyle(style.copy(spacing = s)) },
                    label = { Text(s.label) },
                )
            }
        }

        val marginAllowed = style.background != PageBackground.CORNELL
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Linea di margine", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Linea rossa verticale a sinistra",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = style.marginLine && marginAllowed,
                enabled = marginAllowed,
                onCheckedChange = { onStyle(style.copy(marginLine = it)) },
            )
        }
    }
}

@Composable
private fun PaperDot(color: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(color)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) InkBlue else Color(0xFFC9D0D9), CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
}

/** Impostazioni del foglio dall'editor: valgono per tutte le pagine del quaderno. */
@Composable
fun PageSetupDialog(
    format: PageFormat,
    style: PageStyle,
    onDismiss: () -> Unit,
    onApply: (PageFormat, PageStyle) -> Unit,
) {
    var f by remember { mutableStateOf(format) }
    var s by remember { mutableStateOf(style) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.width(680.dp),
        title = { Text("Impostazioni foglio") },
        text = {
            Row(Modifier.verticalScroll(rememberScrollState())) {
                Box(Modifier.weight(1f)) { PageStyleEditor(f, s, onFormat = { f = it }, onStyle = { s = it }) }
                Spacer(Modifier.width(20.dp))
                PagePreview(f, s, Modifier.width(150.dp).height(210.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onApply(f, s) }) { Text("Applica") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}
