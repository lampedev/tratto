package com.caro.tratto.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caro.tratto.ink.Brushes
import com.caro.tratto.model.ToolKind
import com.caro.tratto.model.ToolSettings
import kotlin.math.roundToInt

internal class ToolbarState(
    /** Quaderno fra i preferiti (voce del menu ⋮; il titolo non è più nella barra). */
    val favorite: Boolean,
    val canUndo: Boolean,
    val canRedo: Boolean,
    val activeTool: ToolKind,
    val penKind: ToolKind,
    val tools: Map<ToolKind, ToolSettings>,
    val recentInk: List<Int>,
    val customColors: List<Int>,
    val favorites: List<ToolSettings>,
    val page: Int,
    val pageCount: Int,
    /** La pagina corrente ha un segnalibro (nastrino pieno nella barra). */
    val pageBookmarked: Boolean = false,
    val fingerDrawing: Boolean,
    val fullscreen: Boolean = false,
    val eraserPartial: Boolean,
    val penPressure: Int,
    val penStabilization: Int,
    val shapeRecognition: Boolean,
    val smoothEdges: Boolean,
    val shapeMode: Boolean,
    val lineMode: Boolean,
    val zoomLocked: Boolean,
    /** Solo quelli da mostrare (quanti, lo dicono le impostazioni generali). */
    val quickColors: List<Int>,
    val quickSizes: List<Int>,
    val showFavoritesBar: Boolean,
    val sizeFollowsZoom: Boolean,
    val zoomPercent: Int,
)

internal class ToolbarActions(
    val onBack: () -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onSelectTool: (ToolKind) -> Unit,
    val onUpdateTool: (ToolSettings) -> Unit,
    val onQuickColor: (Int) -> Unit,
    val onOpenPicker: (ToolKind) -> Unit,
    val onAddFavorite: (ToolSettings) -> Unit,
    val onApplyFavorite: (ToolSettings) -> Unit,
    val onRemoveFavorite: (ToolSettings) -> Unit,
    val onShowPages: () -> Unit,
    val onBookmarks: () -> Unit,
    val onAddPage: () -> Unit,
    val onPageSetup: () -> Unit,
    val onInsertImage: () -> Unit,
    val onPaste: () -> Unit,
    val onInsertPage: () -> Unit,
    val onDuplicatePage: () -> Unit,
    val onDeletePage: () -> Unit,
    val onToggleFinger: () -> Unit,
    val onToggleFullscreen: () -> Unit,
    val onEraserPartial: (Boolean) -> Unit,
    val onPenFeel: (pressure: Int, stabilization: Int) -> Unit,
    val onToggleShapes: () -> Unit,
    val onToggleSmoothEdges: () -> Unit,
    val onToggleShapeMode: () -> Unit,
    val onToggleLineMode: () -> Unit,
    val onToggleZoomLock: () -> Unit,
    val onSetQuickColor: (index: Int, color: Int) -> Unit,
    val onCustomColor: (Int) -> Unit,
    val onToggleFavoritesBar: () -> Unit,
    val onToggleSizeFollowsZoom: () -> Unit,
    val onQuickSize: (Int) -> Unit,
    val onSetQuickSize: (index: Int, level: Int) -> Unit,
    val onZoom: (Int) -> Unit,
    val onAppSettings: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onTags: () -> Unit,
    val onExport: (ExportKind) -> Unit,
)

/**
 * Barra in alto stile Samsung Notes, una riga sola e bassa (più spazio per scrivere): indietro,
 * annulla, ripeti; strumenti, colori e spessori rapidi (scorrono se non ci stanno); forme, linea
 * dritta e blocca zoom a un tocco; numero di pagina; ⋮ con segnalibri, pagine, export.
 */
@Composable
internal fun EditorToolbar(state: ToolbarState, actions: ToolbarActions, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 3.dp, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(50.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            NavGroup(state, actions)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { ToolsGroup(state, actions) }
            ModeToggles(state, actions)
            PageIndicator(state.page, state.pageCount, state.pageBookmarked, actions.onShowPages)
            MoreMenu(state, actions)
        }
    }
}

@Composable
private fun NavGroup(state: ToolbarState, actions: ToolbarActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ToolbarIcon(TrattoIcons.Back, "Indietro", onClick = actions.onBack)
        ToolbarIcon(TrattoIcons.Undo, "Annulla", enabled = state.canUndo, onClick = actions.onUndo)
        ToolbarIcon(TrattoIcons.Redo, "Ripeti", enabled = state.canRedo, onClick = actions.onRedo)
    }
}

/** Strumenti, colori e spessori rapidi; se non ci stanno, scorrono. */
@Composable
private fun ToolsGroup(state: ToolbarState, actions: ToolbarActions) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToolSlot(state.penKind, state, actions)
        ToolSlot(ToolKind.HIGHLIGHTER, state, actions)
        ToolSlot(ToolKind.ERASER, state, actions)
        ToolSlot(ToolKind.LASSO, state, actions)
        Spacer(Modifier.width(6.dp))
        QuickColors(state, actions)
        Spacer(Modifier.width(6.dp))
        if (state.quickSizes.isNotEmpty()) QuickSizes(state, actions)
        SizeChip(state, actions)
    }
}

/** Forme, linea dritta (righello), blocca zoom (lucchetto): un tocco le accende o spegne, accese restano evidenziate. */
@Composable
private fun ModeToggles(state: ToolbarState, actions: ToolbarActions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        ToolbarIcon(TrattoIcons.Shapes, "Forme", selected = state.shapeMode, onClick = actions.onToggleShapeMode)
        ToolbarIcon(TrattoIcons.Ruler, "Linea dritta", selected = state.lineMode, onClick = actions.onToggleLineMode)
        ToolbarIcon(TrattoIcons.Lock, "Blocca zoom", selected = state.zoomLocked, onClick = actions.onToggleZoomLock)
    }
}

/** Strumento a cui si applicano colori e spessori rapidi: quello attivo se scrive, altrimenti l'ultima penna. */
private fun ToolbarState.inkTool(): ToolKind =
    if (activeTool.isPen || activeTool == ToolKind.HIGHLIGHTER) activeTool else penKind

/**
 * Colori con un tocco (quanti, si sceglie nelle impostazioni generali). Toccando di nuovo quello attivo (o tenendolo premuto) si cambia il colore
 * del pallino: comuni, tavolozza o spettro, e resta salvato lì. "+" = tavolozza completa per la penna.
 */
@Composable
private fun QuickColors(state: ToolbarState, actions: ToolbarActions) {
    val kind = state.inkTool()
    val current = state.tools.getValue(kind).color
    var editing by remember { mutableIntStateOf(-1) }
    var spectrumUsed by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        state.quickColors.forEachIndexed { i, c ->
            val selected = (current and 0xFFFFFF) == (c and 0xFFFFFF)
            Box {
                Box(
                    Modifier
                        .padding(horizontal = 2.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(if (selected) 2.5.dp else 1.dp, if (selected) InkBlue else Color(0xFFD0D6DE), CircleShape)
                        .padding(if (selected) 4.dp else 2.dp)
                        .clip(CircleShape)
                        .background(Color(c))
                        .combinedClickable(
                            onClick = { if (selected) editing = i else actions.onQuickColor(c) },
                            onLongClick = { actions.onQuickColor(c); editing = i },
                        ),
                )
                DropdownMenu(
                    expanded = editing == i,
                    onDismissRequest = {
                        // un colore fatto con lo spettro finisce anche fra "I tuoi colori"
                        if (spectrumUsed) actions.onCustomColor(state.quickColors[i])
                        spectrumUsed = false
                        editing = -1
                    },
                ) {
                    Column(Modifier.width(372.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Text("Colore rapido ${i + 1}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Il colore che scegli resta salvato in questo pallino",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 8.dp),
                        )
                        FullColorChooser(
                            color = state.quickColors[i],
                            withAlpha = false,
                            onChange = { actions.onSetQuickColor(i, it) },
                            extra = state.customColors.filter { it ushr 24 == 0xFF },
                            onSpectrumUsed = { spectrumUsed = true },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Spessori con un tocco (quanti, come sopra), disegnati come linee del loro spessore. Toccando di nuovo quello attivo
 * (o tenendolo premuto) si regola il suo valore 1–100, che resta salvato lì.
 */
@Composable
private fun QuickSizes(state: ToolbarState, actions: ToolbarActions) {
    val kind = state.inkTool()
    val settings = state.tools.getValue(kind)
    val level = Brushes.levelForSize(kind, settings.sizePt)
    var editing by remember { mutableIntStateOf(-1) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        state.quickSizes.forEachIndexed { i, l ->
            val selected = l == level
            Box {
                Column(
                    Modifier
                        .padding(horizontal = 1.dp)
                        .size(width = 38.dp, height = 38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .combinedClickable(
                            onClick = { if (selected) editing = i else actions.onQuickSize(l) },
                            onLongClick = { actions.onQuickSize(l); editing = i },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 24.dp, height = (1f + l * 0.09f).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.onSurface),
                    )
                    Text("$l", style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp), modifier = Modifier.padding(top = 2.dp))
                }
                DropdownMenu(expanded = editing == i, onDismissRequest = { editing = -1 }) {
                    QuickSizeEditor(i, kind, settings, l) { actions.onSetQuickSize(i, it.coerceIn(1, 100)) }
                }
            }
        }
    }
}

@Composable
private fun QuickSizeEditor(index: Int, kind: ToolKind, settings: ToolSettings, level: Int, onLevel: (Int) -> Unit) {
    Column(Modifier.width(360.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Spessore rapido ${index + 1} · ${kind.label}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Il valore che scegli resta salvato in questo tratto",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text("$level", style = MaterialTheme.typography.headlineSmall)
        }
        StrokePreview(kind, settings.copy(sizePt = Brushes.sizeForLevel(kind, level)))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−") { onLevel(level - 1) }
            Slider(
                value = level.toFloat(),
                onValueChange = { onLevel(it.roundToInt()) },
                valueRange = 1f..100f,
                modifier = Modifier.weight(1f),
            )
            StepButton("+") { onLevel(level + 1) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(1, 5, 10, 20, 35, 50, 65, 80, 100).forEach { preset ->
                Box(
                    Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(if (preset == level) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { onLevel(preset) },
                    contentAlignment = Alignment.Center,
                ) { Text("$preset", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

/** Icona compatta della barra (38 dp invece dei 48 dei pulsanti standard: la barra resta su una riga). */
@Composable
private fun ToolbarIcon(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            modifier = Modifier.size(22.dp),
            tint = when {
                !enabled -> MaterialTheme.colorScheme.outlineVariant
                selected -> InkBlue
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

internal fun iconFor(kind: ToolKind) = when (kind) {
    ToolKind.PEN -> TrattoIcons.Fountain
    ToolKind.BALLPOINT -> TrattoIcons.Pen
    ToolKind.PENCIL -> TrattoIcons.Pencil
    ToolKind.CALLIGRAPHY -> TrattoIcons.Calligraphy
    ToolKind.MARKER -> TrattoIcons.Marker
    ToolKind.HIGHLIGHTER -> TrattoIcons.Highlighter
    ToolKind.ERASER -> TrattoIcons.Eraser
    ToolKind.LASSO -> TrattoIcons.Lasso
}

/** Primo tocco seleziona lo strumento; tocco sullo strumento già attivo apre tipo, spessore e colori. */
@Composable
private fun ToolSlot(kind: ToolKind, state: ToolbarState, actions: ToolbarActions) {
    var open by remember { mutableStateOf(false) }
    val selected = if (kind.isPen) state.activeTool.isPen else state.activeTool == kind
    val settings = state.tools.getValue(kind)
    Box {
        Box(
            Modifier
                .padding(horizontal = 1.dp)
                .size(42.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .clickable {
                    when {
                        !selected -> actions.onSelectTool(kind)
                        kind != ToolKind.LASSO -> open = true
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(iconFor(kind), contentDescription = kind.label, tint = if (selected) InkBlue else MaterialTheme.colorScheme.onSurface)
            if (kind != ToolKind.ERASER && kind != ToolKind.LASSO) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp)
                        .size(width = 18.dp, height = 3.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(settings.color)),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ToolOptions(if (kind.isPen) state.penKind else kind, state, actions)
        }
    }
}

@Composable
private fun ToolOptions(kind: ToolKind, state: ToolbarState, actions: ToolbarActions) {
    val settings = state.tools.getValue(kind)
    val level = Brushes.levelForSize(kind, settings.sizePt)
    Column(Modifier.width(380.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (kind.isPen) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                ToolKind.pens.forEach { p ->
                    PenTypeChip(p, selected = p == kind, modifier = Modifier.weight(1f)) { actions.onSelectTool(p) }
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kind.label, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            Text("Spessore $level", style = MaterialTheme.typography.labelLarge)
        }
        StrokePreview(kind, settings)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepButton("−") { actions.onUpdateTool(settings.copy(sizePt = Brushes.sizeForLevel(kind, level - 1))) }
            Slider(
                value = level.toFloat(),
                onValueChange = { actions.onUpdateTool(settings.copy(sizePt = Brushes.sizeForLevel(kind, it.roundToInt()))) },
                valueRange = 1f..100f,
                modifier = Modifier.weight(1f),
            )
            StepButton("+") { actions.onUpdateTool(settings.copy(sizePt = Brushes.sizeForLevel(kind, level + 1))) }
        }
        if (kind.isPen || kind == ToolKind.HIGHLIGHTER) {
            val already = state.favorites.any { it.kind == kind && it.color == settings.color && it.sizePt == settings.sizePt }
            TextButton(onClick = { actions.onAddFavorite(settings) }, enabled = !already && state.favorites.size < 10) {
                Text(if (already) "★ Nei preferiti" else "☆ Aggiungi ai preferiti")
            }
        }
        if (kind.isPen && kind != ToolKind.MARKER) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Text("Sensibilità pressione", style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Bassa", "Media", "Alta").forEachIndexed { i, label ->
                    FilterChip(
                        selected = state.penPressure == i,
                        onClick = { actions.onPenFeel(i, state.penStabilization) },
                        label = { Text(label) },
                    )
                }
            }
            Text("Stabilizzazione", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("No", "Normale", "Morbida", "Max").forEachIndexed { i, label ->
                    FilterChip(
                        selected = state.penStabilization == i,
                        onClick = { actions.onPenFeel(state.penPressure, i) },
                        label = { Text(label) },
                    )
                }
            }
        }
        if (kind == ToolKind.ERASER) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !state.eraserPartial,
                    onClick = { actions.onEraserPartial(false) },
                    label = { Text("Tratto intero") },
                )
                FilterChip(
                    selected = state.eraserPartial,
                    onClick = { actions.onEraserPartial(true) },
                    label = { Text("Parziale") },
                )
            }
        }
        if (kind != ToolKind.ERASER) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            Palette.forTool(kind).chunked(8).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    row.forEach { c -> ColorDot(c, settings.color == c, size = 30) { actions.onUpdateTool(settings.copy(color = c)) } }
                }
            }
            Text("I tuoi colori", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
            val mine = state.customColors.filter { (it ushr 24 == 0xFF) != (kind == ToolKind.HIGHLIGHTER) }.take(7)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                mine.forEach { c -> ColorDot(c, settings.color == c, size = 30) { actions.onUpdateTool(settings.copy(color = c)) } }
                AddColorButton { actions.onOpenPicker(kind) }
            }
        }
    }
}

/** Spessore dello strumento attivo: un tocco apre solo le misure rapide e lo slider. */
@Composable
private fun SizeChip(state: ToolbarState, actions: ToolbarActions) {
    val kind = if (state.activeTool == ToolKind.LASSO) state.penKind else state.activeTool
    val settings = state.tools.getValue(kind)
    val level = Brushes.levelForSize(kind, settings.sizePt)
    val auto = state.sizeFollowsZoom && kind.isPen
    val shown = if (auto) Brushes.levelForZoom(level, state.zoomPercent) else level
    var open by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(width = 44.dp, height = 34.dp)
                .clip(RoundedCornerShape(10.dp))
                .border(1.dp, Color(0xFFD7DDE5), RoundedCornerShape(10.dp))
                .clickable { open = true },
            contentAlignment = Alignment.Center,
        ) {
            Text(if (auto) "A$shown" else "$level", style = MaterialTheme.typography.labelLarge)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Column(Modifier.width(340.dp).padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Spessore · ${kind.label}", style = MaterialTheme.typography.titleSmall)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    listOf(1, 10, 25, 40, 55, 70, 90).forEach { preset ->
                        val selected = preset == level
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { actions.onUpdateTool(settings.copy(sizePt = Brushes.sizeForLevel(kind, preset))); open = false },
                            contentAlignment = Alignment.Center,
                        ) {
                            val d = (4f + preset * 0.26f).dp
                            Box(Modifier.size(d).clip(CircleShape).background(if (kind == ToolKind.ERASER) Color(0xFFB0B8C4) else Color(settings.color or 0xFF000000.toInt())))
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StepButton("−") { actions.onUpdateTool(settings.copy(sizePt = Brushes.sizeForLevel(kind, level - 1))) }
                    Slider(
                        value = level.toFloat(),
                        onValueChange = { actions.onUpdateTool(settings.copy(sizePt = Brushes.sizeForLevel(kind, it.roundToInt()))) },
                        valueRange = 1f..100f,
                        modifier = Modifier.weight(1f),
                    )
                    StepButton("+") { actions.onUpdateTool(settings.copy(sizePt = Brushes.sizeForLevel(kind, level + 1))) }
                    Text("$level", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(30.dp))
                }
                if (kind.isPen) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text("Proporzionale allo zoom", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (auto) "Al ${state.zoomPercent}% scrivi a $shown (scelto $level = spessore al 100%)"
                                else "Più zoomi, più il tratto si assottiglia",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = state.sizeFollowsZoom, onCheckedChange = { actions.onToggleSizeFollowsZoom() })
                    }
                }
            }
        }
    }
}

@Composable
private fun PenTypeChip(kind: ToolKind, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    ) {
        Icon(iconFor(kind), contentDescription = null, tint = if (selected) InkBlue else MaterialTheme.colorScheme.onSurface)
        Text(
            kind.label.substringBefore(' ').let { if (kind == ToolKind.BALLPOINT) "Sfera" else it },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Anteprima indicativa a zoom 100% (1 pt ≈ 1.25 dp). */
@Composable
private fun StrokePreview(kind: ToolKind, settings: ToolSettings) {
    // inchiostro chiaro (bianco per la carta scura): anteprima su fondo scuro, se no non si vede
    val light = kind != ToolKind.ERASER && androidx.core.graphics.ColorUtils.calculateLuminance(settings.color or 0xFF000000.toInt()) > 0.7
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (light) Color(0xFF2B2F36) else Color.Transparent)
            .height(48.dp)
            .padding(vertical = 4.dp),
    ) {
        val color = if (kind == ToolKind.ERASER) Color(0xFFB0B8C4) else Color(settings.color)
        val w = (settings.sizePt * 1.25f * density).coerceIn(1f, size.height * 0.9f)
        val path = Path().apply {
            moveTo(w + 6f, size.height * 0.65f)
            cubicTo(size.width * 0.3f, -size.height * 0.1f, size.width * 0.6f, size.height * 1.1f, size.width - w - 6f, size.height * 0.35f)
        }
        drawPath(path, color, style = Stroke(width = w, cap = StrokeCap.Round))
    }
}

@Composable
internal fun StepButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

@Composable
internal fun AddColorButton(diameter: Int = 30, onClick: () -> Unit) {
    Box(
        Modifier
            .size(diameter.dp)
            .clip(CircleShape)
            .border(1.5.dp, Color(0xFF9AA4B2), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter.dp)) {
            // spicchi di colore + simbolo "+"
            val r = size.minDimension / 2 - 3.dp.toPx()
            val hues = listOf(0f, 60f, 120f, 180f, 240f, 300f)
            hues.forEachIndexed { i, h ->
                drawArc(Color.hsv(h, 0.75f, 0.95f), i * 60f, 60f, useCenter = true,
                    topLeft = Offset(center.x - r, center.y - r), size = androidx.compose.ui.geometry.Size(2 * r, 2 * r))
            }
            drawCircle(Color.White, radius = r * 0.55f)
            val s = r * 0.32f
            drawLine(Color(0xFF333B48), Offset(center.x - s, center.y), Offset(center.x + s, center.y), 2.dp.toPx())
            drawLine(Color(0xFF333B48), Offset(center.x, center.y - s), Offset(center.x, center.y + s), 2.dp.toPx())
        }
    }
}

@Composable
internal fun ColorDot(color: Int, selected: Boolean, size: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.White)
            .border(if (selected) 2.5.dp else 1.dp, if (selected) InkBlue else Color(0xFFD0D6DE), CircleShape)
            .padding(if (selected) 4.dp else 2.dp)
            .clip(CircleShape)
            .background(Color(color))
            .clickable(onClick = onClick),
    )
}

/** Numero di pagina (con il nastrino se ha un segnalibro): toccandolo si apre la griglia delle pagine. */
@Composable
private fun PageIndicator(page: Int, pageCount: Int, bookmarked: Boolean, onShowPages: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, Color(0xFFD7DDE5), RoundedCornerShape(8.dp))
            .clickable(onClick = onShowPages)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bookmarked) Icon(TrattoIcons.BookmarkFilled, "Pagina con segnalibro", tint = InkBlue, modifier = Modifier.size(14.dp).padding(end = 2.dp))
        Text("${page + 1}/$pageCount", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun MoreMenu(state: ToolbarState, actions: ToolbarActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolbarIcon(TrattoIcons.More, "Altro", onClick = { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Segnalibri e indice…") },
                leadingIcon = { Icon(if (state.pageBookmarked) TrattoIcons.BookmarkFilled else TrattoIcons.Bookmark, null, tint = InkBlue) },
                onClick = { open = false; actions.onBookmarks() },
            )
            DropdownMenuItem(
                text = { Text("Aggiungi pagina in fondo") },
                leadingIcon = { Icon(TrattoIcons.PageAdd, null) },
                onClick = { open = false; actions.onAddPage() },
            )
            var exportOpen by remember { mutableStateOf(false) }
            DropdownMenuItem(
                text = { Text("Esporta / condividi") },
                leadingIcon = { Icon(TrattoIcons.Share, null) },
                trailingIcon = { Text(if (exportOpen) "▴" else "▾") },
                onClick = { exportOpen = !exportOpen },
            )
            if (exportOpen) {
                ExportKind.entries.forEach { kind ->
                    DropdownMenuItem(
                        text = { Text(kind.label, modifier = Modifier.padding(start = 36.dp)) },
                        onClick = { open = false; exportOpen = false; actions.onExport(kind) },
                    )
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (state.favorite) "Togli dai preferiti" else "Aggiungi ai preferiti") },
                leadingIcon = { Icon(if (state.favorite) TrattoIcons.StarFilled else TrattoIcons.Star, null, tint = Color(0xFFF0A500)) },
                onClick = { open = false; actions.onToggleFavorite() },
            )
            DropdownMenuItem(
                text = { Text("Tag…") },
                leadingIcon = { Icon(TrattoIcons.Tag, null) },
                onClick = { open = false; actions.onTags() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Inserisci immagine") },
                leadingIcon = { Icon(TrattoIcons.Image, null) },
                onClick = { open = false; actions.onInsertImage() },
            )
            DropdownMenuItem(
                text = { Text("Incolla") },
                leadingIcon = { Icon(TrattoIcons.Copy, null) },
                onClick = { open = false; actions.onPaste() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Impostazioni foglio") },
                leadingIcon = { Icon(TrattoIcons.Page, null) },
                onClick = { open = false; actions.onPageSetup() },
            )
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Zoom", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 4.dp))
                listOf(100, 200, 400, 600, 1000).forEach { z ->
                    TextButton(onClick = { open = false; actions.onZoom(z) }) { Text("$z%") }
                }
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Inserisci pagina dopo questa") },
                leadingIcon = { Icon(TrattoIcons.PageAdd, null) },
                onClick = { open = false; actions.onInsertPage() },
            )
            DropdownMenuItem(
                text = { Text("Duplica pagina") },
                leadingIcon = { Icon(TrattoIcons.Copy, null) },
                onClick = { open = false; actions.onDuplicatePage() },
            )
            DropdownMenuItem(
                text = { Text("Elimina pagina", color = MaterialTheme.colorScheme.error) },
                leadingIcon = { Icon(TrattoIcons.Trash, null, tint = MaterialTheme.colorScheme.error) },
                enabled = state.pageCount > 1,
                onClick = { open = false; actions.onDeletePage() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Forme automatiche (tieni premuto)") },
                leadingIcon = { Icon(TrattoIcons.Page, null) },
                trailingIcon = { if (state.shapeRecognition) Icon(TrattoIcons.Check, null, tint = InkBlue) },
                onClick = { open = false; actions.onToggleShapes() },
            )
            DropdownMenuItem(
                text = { Text("Barra penne preferite") },
                leadingIcon = { Icon(TrattoIcons.Fountain, null) },
                trailingIcon = { if (state.showFavoritesBar) Icon(TrattoIcons.Check, null, tint = InkBlue) },
                onClick = { open = false; actions.onToggleFavoritesBar() },
            )
            DropdownMenuItem(
                text = { Text("Bordi extra-morbidi") },
                leadingIcon = { Icon(TrattoIcons.Pen, null) },
                trailingIcon = { if (state.smoothEdges) Icon(TrattoIcons.Check, null, tint = InkBlue) },
                onClick = { open = false; actions.onToggleSmoothEdges() },
            )
            DropdownMenuItem(
                text = { Text("Schermo intero") },
                leadingIcon = { Icon(TrattoIcons.Page, null) },
                trailingIcon = { if (state.fullscreen) Icon(TrattoIcons.Check, null, tint = InkBlue) },
                onClick = { open = false; actions.onToggleFullscreen() },
            )
            DropdownMenuItem(
                text = { Text("Scrivi col dito") },
                leadingIcon = { Icon(TrattoIcons.Hand, null) },
                trailingIcon = { if (state.fingerDrawing) Icon(TrattoIcons.Check, null, tint = InkBlue) },
                onClick = { open = false; actions.onToggleFinger() },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Impostazioni generali…") },
                leadingIcon = { Icon(TrattoIcons.Settings, null) },
                onClick = { open = false; actions.onAppSettings() },
            )
        }
    }
}
