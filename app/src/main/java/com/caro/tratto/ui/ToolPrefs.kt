package com.caro.tratto.ui

import android.content.Context
import androidx.core.content.edit
import com.caro.tratto.ink.Brushes
import com.caro.tratto.model.ToolKind
import com.caro.tratto.model.ToolSettings

/** Ricorda colore e spessore di ogni strumento, l'ultimo strumento usato e la scrittura col dito. */
internal class ToolPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("tool_prefs", Context.MODE_PRIVATE)

    fun load(kind: ToolKind): ToolSettings {
        val def = Brushes.defaultSettings(kind)
        return ToolSettings(
            kind,
            prefs.getInt("${kind.name}_color", def.color),
            prefs.getFloat("${kind.name}_size", def.sizePt),
        )
    }

    fun save(settings: ToolSettings) = prefs.edit {
        putInt("${settings.kind.name}_color", settings.color)
        putFloat("${settings.kind.name}_size", settings.sizePt)
    }

    var lastTool: ToolKind
        get() = ToolKind.entries.firstOrNull { it.name == prefs.getString("last_tool", null) } ?: ToolKind.PEN
        set(value) = prefs.edit { putString("last_tool", value.name) }

    /** Ultima penna usata nella slot "Penna" della barra. */
    var lastPen: ToolKind
        get() = ToolKind.entries.firstOrNull { it.name == prefs.getString("last_pen", null) && it.isPen } ?: ToolKind.PEN
        set(value) = prefs.edit { putString("last_pen", value.name) }

    /** Colori creati con il selettore, il più recente per primo. */
    var customColors: List<Int>
        get() = prefs.getString("custom_colors", "").orEmpty().split(',').mapNotNull { it.toIntOrNull() }
        set(value) = prefs.edit { putString("custom_colors", value.take(16).joinToString(",")) }

    /** Penne preferite (tipo + colore + spessore), al massimo 10. */
    var favorites: List<ToolSettings>
        get() = prefs.getString("favorites", "").orEmpty().split(';').mapNotNull { item ->
            val parts = item.split(':')
            if (parts.size != 3) return@mapNotNull null
            val kind = ToolKind.entries.firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
            val color = parts[1].toIntOrNull() ?: return@mapNotNull null
            val size = parts[2].toFloatOrNull() ?: return@mapNotNull null
            ToolSettings(kind, color, size)
        }
        set(value) = prefs.edit {
            putString("favorites", value.take(10).joinToString(";") { "${it.kind.name}:${it.color}:${it.sizePt}" })
        }

    /** Sensibilità alla pressione delle penne: 0 bassa, 1 media, 2 alta. */
    var penPressure: Int
        get() = prefs.getInt("pen_pressure", 1)
        set(value) = prefs.edit { putInt("pen_pressure", value) }

    /** Stabilizzazione del tratto: 0 nessuna, 1 normale, 2 morbida, 3 massima. */
    var penStabilization: Int
        get() = prefs.getInt("pen_stabilization", 1)
        set(value) = prefs.edit { putInt("pen_stabilization", value) }

    var smoothEdges: Boolean
        get() = prefs.getBoolean("smooth_edges", false)
        set(value) = prefs.edit { putBoolean("smooth_edges", value) }

    var shapeMode: Boolean
        get() = prefs.getBoolean("shape_mode", false)
        set(value) = prefs.edit { putBoolean("shape_mode", value) }

    var lineMode: Boolean
        get() = prefs.getBoolean("line_mode", false)
        set(value) = prefs.edit { putBoolean("line_mode", value) }

    var zoomLocked: Boolean
        get() = prefs.getBoolean("zoom_locked", false)
        set(value) = prefs.edit { putBoolean("zoom_locked", value) }

    /**
     * I colori rapidi della barra: ne restano salvati sempre [MAX_QUICK_COLORS], la barra mostra i
     * primi [quickColorCount]. Le posizioni mai toccate prendono i colori di serie.
     */
    var quickColors: List<Int>
        get() = padded(prefs.getString("quick_colors", null), DEFAULT_QUICK_COLORS)
        set(value) = prefs.edit { putString("quick_colors", value.take(MAX_QUICK_COLORS).joinToString(",")) }

    /** Gli spessori rapidi (livelli 1–100): salvati [MAX_QUICK_SIZES], in barra i primi [quickSizeCount]. */
    var quickSizes: List<Int>
        get() = padded(prefs.getString("quick_sizes", null), DEFAULT_QUICK_SIZES)
        set(value) = prefs.edit { putString("quick_sizes", value.take(MAX_QUICK_SIZES).joinToString(",")) }

    /** Quanti colori rapidi in barra (impostazioni generali, uguale per tutti i quaderni). */
    var quickColorCount: Int
        get() = prefs.getInt("quick_color_count", 6).coerceIn(0, MAX_QUICK_COLORS)
        set(value) = prefs.edit { putInt("quick_color_count", value.coerceIn(0, MAX_QUICK_COLORS)) }

    /** Quanti spessori rapidi in barra. */
    var quickSizeCount: Int
        get() = prefs.getInt("quick_size_count", 3).coerceIn(0, MAX_QUICK_SIZES)
        set(value) = prefs.edit { putInt("quick_size_count", value.coerceIn(0, MAX_QUICK_SIZES)) }

    /** Ordine dei quaderni in libreria (i preferiti stanno comunque in cima). */
    var librarySort: LibrarySort
        get() = LibrarySort.entries.firstOrNull { it.name == prefs.getString("library_sort", null) } ?: LibrarySort.UPDATED
        set(value) = prefs.edit { putString("library_sort", value.name) }

    /** Ordine al contrario: Z → A, oppure i più vecchi prima. */
    var librarySortReversed: Boolean
        get() = prefs.getBoolean("library_sort_reversed", false)
        set(value) = prefs.edit { putBoolean("library_sort_reversed", value) }

    private fun padded(saved: String?, defaults: List<Int>): List<Int> {
        val list = saved?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty().take(defaults.size)
        return list + defaults.drop(list.size)
    }

    var showFavoritesBar: Boolean
        get() = prefs.getBoolean("show_favorites_bar", true)
        set(value) = prefs.edit { putBoolean("show_favorites_bar", value) }

    var sizeFollowsZoom: Boolean
        get() = prefs.getBoolean("size_follows_zoom", false)
        set(value) = prefs.edit { putBoolean("size_follows_zoom", value) }

    var eraserPartial: Boolean
        get() = prefs.getBoolean("eraser_partial", false)
        set(value) = prefs.edit { putBoolean("eraser_partial", value) }

    var shapeRecognition: Boolean
        get() = prefs.getBoolean("shape_recognition", true)
        set(value) = prefs.edit { putBoolean("shape_recognition", value) }

    /**
     * Schermo intero nel foglio: senza la barra di stato (ora, batteria) né quella in basso. Acceso di serie dalla 0.43
     * (chiave nuova, così vale anche per chi aveva la vecchia "fullscreen" spenta).
     */
    var fullscreen: Boolean
        get() = prefs.getBoolean("immersive", true)
        set(value) = prefs.edit { putBoolean("immersive", value) }

    var fingerDrawing: Boolean
        get() = prefs.getBoolean("finger_drawing", false)
        set(value) = prefs.edit { putBoolean("finger_drawing", value) }
}

internal const val MAX_QUICK_COLORS = 12
internal const val MAX_QUICK_SIZES = 6

/** Di serie: i primi 6 colori e i primi 3 spessori sono quelli delle versioni precedenti. */
private val DEFAULT_QUICK_COLORS = listOf(
    0xFF111111, 0xFF1E63D6, 0xFFD32F2F, 0xFF2E7D32, 0xFFFBC02D, 0xFFEF6C00,
    0xFF7B1FA2, 0xFF6D4C41, 0xFF757575, 0xFF00ACC1, 0xFFD81B60, 0xFFFFFFFF,
).map { it.toInt() }
private val DEFAULT_QUICK_SIZES = listOf(25, 45, 65, 80, 90, 100)

internal object Palette {
    val ink = listOf(
        0xFF111111, 0xFF424242, 0xFF757575, 0xFFBDBDBD, 0xFFFFFFFF, 0xFF1F3A5F, 0xFF0D47A1, 0xFF1E63D6,
        0xFF039BE5, 0xFF00ACC1, 0xFF00897B, 0xFF2E7D32, 0xFF7CB342, 0xFFC0CA33, 0xFFFBC02D, 0xFFFFA000,
        0xFFEF6C00, 0xFFF4511E, 0xFFD32F2F, 0xFFAD1457, 0xFFD81B60, 0xFFF06292, 0xFF7B1FA2, 0xFF6D4C41,
    ).map { it.toInt() }

    val highlighter = listOf(
        0xFFE14D, 0xEEFF41, 0x76FF03, 0x64FFDA, 0x18FFFF, 0x40C4FF, 0x448AFF, 0xB388FF,
        0xEA80FC, 0xFF4081, 0xFF5252, 0xFFAB40, 0xFFD180, 0xBCAAA4, 0x90A4AE, 0xE0E0E0,
    ).map { 0x66000000 or it }

    fun forTool(kind: ToolKind) = if (kind == ToolKind.HIGHLIGHTER) highlighter else ink
}
