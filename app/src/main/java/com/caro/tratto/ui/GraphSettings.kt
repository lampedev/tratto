package com.caro.tratto.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Impostazioni del grafo, come il pannello di Obsidian: Filtri, Gruppi (colori dati da una ricerca),
 * Visualizzazione e Forze. Si ricordano fra un'apertura e l'altra.
 */

/** Gruppo di colore: i quaderni che rispondono a [query] (sintassi di [GraphQuery]) prendono [color]. */
internal data class GraphColorGroup(val query: String, val color: Int)

internal data class GraphSettings(
    /** [THEME_LIGHT] come l'app, [THEME_DARK], [THEME_SYSTEM] come il telefono. */
    val theme: Int = THEME_LIGHT,
    /** Ricerca: si vedono solo i quaderni che rispondono (vuota = tutti). */
    val search: String = "",
    /** I tag come pallini, collegati ai loro quaderni. */
    val tags: Boolean = false,
    /** Anche i quaderni senza collegamenti. */
    val orphans: Boolean = true,
    /** Quaderni tolti dal grafo a mano (id). */
    val hidden: Set<String> = emptySet(),
    /** null = un gruppo per materia (cartella di primo livello), con il colore della cartella. */
    val groups: List<GraphColorGroup>? = null,
    val arrows: Boolean = false,
    /** Le linee dei collegamenti. */
    val links: Boolean = true,
    /** Zoom (0..1 sul cursore) da cui compaiono i nomi: vedi [labelZoom]. */
    val textFade: Float = 0.45f,
    val nodeSize: Float = 1f,
    val linkThickness: Float = 1f,
    // forze, 0..1 sul cursore
    val centerForce: Float = 0.5f,
    val repelForce: Float = 0.5f,
    val linkForce: Float = 0.5f,
    val linkDistance: Float = 0.25f,
) {
    /** Zoom (1 = 100%) da cui i nomi sono pieni: da 12% a 600%, in scala logaritmica. */
    val labelZoom: Float get() = ZOOM_MIN * (ZOOM_MAX / ZOOM_MIN).pow(textFade)

    // forze in unità della simulazione: ai valori di serie sono quelle della prima versione del grafo
    val gravity: Float get() = 0.07f * centerForce
    val charge: Float get() = -(5f + 310f * repelForce)
    val linkStrength: Float get() = 2f * linkForce
    val distance: Float get() = 20f + 200f * linkDistance

    companion object {
        const val THEME_LIGHT = 0
        const val THEME_DARK = 1
        const val THEME_SYSTEM = 2
        const val ZOOM_MIN = 0.12f
        const val ZOOM_MAX = 6f

        fun textFadeFor(zoom: Float): Float = (ln(zoom / ZOOM_MIN) / ln(ZOOM_MAX / ZOOM_MIN)).coerceIn(0f, 1f)
    }
}

internal class GraphPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("graph_prefs", Context.MODE_PRIVATE)

    fun load(): GraphSettings = runCatching {
        val o = JSONObject(prefs.getString("settings", null) ?: return GraphSettings())
        val d = GraphSettings()
        GraphSettings(
            theme = o.optInt("theme", d.theme),
            search = o.optString("search", d.search),
            tags = o.optBoolean("tags", d.tags),
            orphans = o.optBoolean("orphans", d.orphans),
            hidden = o.optJSONArray("hidden")?.let { a -> (0 until a.length()).mapTo(HashSet()) { a.getString(it) } } ?: d.hidden,
            groups = o.optJSONArray("groups")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { GraphColorGroup(it.optString("query"), it.optInt("color")) }
            },
            arrows = o.optBoolean("arrows", d.arrows),
            links = o.optBoolean("links", d.links),
            textFade = o.optDouble("textFade", d.textFade.toDouble()).toFloat(),
            nodeSize = o.optDouble("nodeSize", d.nodeSize.toDouble()).toFloat(),
            linkThickness = o.optDouble("linkThickness", d.linkThickness.toDouble()).toFloat(),
            centerForce = o.optDouble("centerForce", d.centerForce.toDouble()).toFloat(),
            repelForce = o.optDouble("repelForce", d.repelForce.toDouble()).toFloat(),
            linkForce = o.optDouble("linkForce", d.linkForce.toDouble()).toFloat(),
            linkDistance = o.optDouble("linkDistance", d.linkDistance.toDouble()).toFloat(),
        )
    }.getOrDefault(GraphSettings())

    fun save(s: GraphSettings) = prefs.edit {
        val o = JSONObject()
            .put("theme", s.theme)
            .put("search", s.search)
            .put("tags", s.tags)
            .put("orphans", s.orphans)
            .put("hidden", JSONArray(s.hidden.toList()))
            .put("arrows", s.arrows)
            .put("links", s.links)
            .put("textFade", s.textFade.toDouble())
            .put("nodeSize", s.nodeSize.toDouble())
            .put("linkThickness", s.linkThickness.toDouble())
            .put("centerForce", s.centerForce.toDouble())
            .put("repelForce", s.repelForce.toDouble())
            .put("linkForce", s.linkForce.toDouble())
            .put("linkDistance", s.linkDistance.toDouble())
        s.groups?.let { gs -> o.put("groups", JSONArray(gs.map { JSONObject().put("query", it.query).put("color", it.color) })) }
        putString("settings", o.toString())
    }
}

/**
 * Ricerca come quella di Obsidian, per filtrare e per i gruppi di colore:
 * `path:Biologia` (cartella), `file:proteine` (titolo), `tag:esame` o `#esame`, parole libere (titolo
 * o cartella), `"più parole"` fra virgolette, `-` davanti per escludere, `OR` fra alternative.
 * Maiuscole e accenti non contano.
 */
internal class GraphQuery(text: String) {
    private class Term(val negate: Boolean, val field: String, val value: String)

    private val clauses: List<List<Term>>

    init {
        val tokens = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        for (ch in text.trim()) {
            when {
                ch == '"' -> quoted = !quoted
                ch.isWhitespace() && !quoted -> if (cur.isNotEmpty()) { tokens.add(cur.toString()); cur.clear() }
                else -> cur.append(ch)
            }
        }
        if (cur.isNotEmpty()) tokens.add(cur.toString())
        val out = ArrayList<List<Term>>()
        var clause = ArrayList<Term>()
        for (t in tokens) {
            if (t == "OR") {
                if (clause.isNotEmpty()) out.add(clause)
                clause = ArrayList()
                continue
            }
            var s = t
            val negate = s.startsWith("-") && s.length > 1
            if (negate) s = s.substring(1)
            val field = when {
                s.startsWith("#") -> "tag".also { s = s.substring(1) }
                ':' in s && s.substringBefore(':').lowercase() in FIELDS -> s.substringBefore(':').lowercase().also { s = s.substringAfter(':') }
                else -> ""
            }
            val value = fold(s)
            if (value.isNotEmpty()) clause.add(Term(negate, field, value))
        }
        if (clause.isNotEmpty()) out.add(clause)
        clauses = out
    }

    val isEmpty: Boolean get() = clauses.isEmpty()

    /** [path] = cartelle separate da "/", [tags] = nomi dei tag. */
    fun matches(title: String, path: String, tags: List<String>): Boolean {
        if (clauses.isEmpty()) return true
        val t = fold(title)
        val p = fold(path)
        val tg = tags.map(::fold)
        return clauses.any { clause ->
            clause.all { term ->
                val hit = when (term.field) {
                    "path" -> p.contains(term.value)
                    "file" -> t.contains(term.value)
                    "tag" -> tg.any { it.contains(term.value) }
                    else -> t.contains(term.value) || p.contains(term.value)
                }
                hit != term.negate
            }
        }
    }

    companion object {
        private val FIELDS = setOf("path", "file", "tag")
        private val MARKS = Regex("\\p{Mn}+")
        fun fold(s: String): String = MARKS.replace(Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD), "")
    }
}

/** Colori del grafo: fondo chiaro come l'app o scuro, e il viola di Obsidian per il quaderno scelto. */
internal class GraphColors(val dark: Boolean) {
    val background = if (dark) Color(0xFF1E1E1E) else Color(0xFFFAFAFB)
    val text = if (dark) Color(0xFFDADADA) else Color(0xFF222222)
    val muted = if (dark) Color(0xFF999999) else Color(0xFF6B6F76)
    val card = if (dark) Color(0xFF262626) else Color.White
    val border = if (dark) Color(0xFF363636) else Color(0xFFE3E5E8)
    val node = if (dark) Color(0xFFA3A3A3) else Color(0xFF8C8F94)
    val line = if (dark) Color(0x40FFFFFF) else Color(0x33000000)
    val lineDim = if (dark) Color(0x12FFFFFF) else Color(0x0F000000)
    val accent = if (dark) Color(0xFFA68AF9) else Color(0xFF7F5AF0)
    val field = if (dark) Color(0xFF1A1A1A) else Color(0xFFF2F3F5)
}

private val GREYS = setOf(0xFF9AA4AE.toInt(), 0xFF4A5560.toInt())

/** Colori proposti per i gruppi: quelli delle cartelle più qualche tinta in più. */
internal val GroupSwatches: List<Int> = FolderColors.filterNotNull().map { it.toInt() } +
    listOf(0xFF7F5AF0, 0xFF26A69A, 0xFFEC407A, 0xFF8D6E63).map { it.toInt() }

/** Pannello delle impostazioni, a destra sopra il grafo. */
@Composable
internal fun GraphSettingsPanel(
    s: GraphSettings,
    defaultGroups: List<GraphColorGroup>,
    hiddenTitles: List<Pair<String, String>>,
    c: GraphColors,
    onChange: (GraphSettings) -> Unit,
    onAnimate: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = c.card,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 8.dp,
        modifier = modifier.width(330.dp).border(1.dp, c.border, RoundedCornerShape(14.dp)),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 10.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Impostazioni del grafo", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = { onChange(GraphSettings(theme = s.theme, hidden = s.hidden)) }) {
                    Icon(TrattoIcons.Reset, contentDescription = "Ripristina le impostazioni", tint = c.muted, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onClose) { Icon(TrattoIcons.Close, contentDescription = "Chiudi", tint = c.muted, modifier = Modifier.size(20.dp)) }
            }

            Section("Filtri", c, startOpen = true) {
                SearchField(s.search, "Cerca quaderni…  (es. path:Chimica -file:indice)", c) { onChange(s.copy(search = it)) }
                Toggle("Tag", s.tags, c) { onChange(s.copy(tags = it)) }
                Toggle("Orfani (senza collegamenti)", s.orphans, c) { onChange(s.copy(orphans = it)) }
                if (hiddenTitles.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Nascosti (${hiddenTitles.size})", color = c.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        Text("Mostra tutti", color = c.accent, fontSize = 13.sp, modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onChange(s.copy(hidden = emptySet())) }.padding(6.dp))
                    }
                    hiddenTitles.forEach { (id, title) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, color = c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onChange(s.copy(hidden = s.hidden - id)) }) {
                                Icon(TrattoIcons.Eye, contentDescription = "Mostra $title", tint = c.muted, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
            }

            Section("Gruppi", c) {
                val groups = s.groups ?: defaultGroups
                fun set(list: List<GraphColorGroup>) = onChange(s.copy(groups = list))
                groups.forEachIndexed { i, g ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        ColorDot(g.color, c) { color -> set(groups.toMutableList().also { it[i] = g.copy(color = color) }) }
                        Spacer(Modifier.width(8.dp))
                        Box(Modifier.weight(1f)) {
                            SearchField(g.query, "path:…  file:…  tag:…", c) { q -> set(groups.toMutableList().also { it[i] = g.copy(query = q) }) }
                        }
                        IconButton(onClick = { set(groups.toMutableList().also { it.removeAt(i) }) }) {
                            Icon(TrattoIcons.Close, contentDescription = "Togli il gruppo", tint = c.muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    // in cima: un gruppo nuovo vince sulle materie
                    TextChip("Nuovo gruppo", c) {
                        // un colore vivace che i gruppi non usano ancora (niente grigi)
                        val used = groups.map { it.color }.toSet()
                        val color = GroupSwatches.firstOrNull { it !in used && it !in GREYS } ?: GroupSwatches.first()
                        set(listOf(GraphColorGroup("", color)) + groups)
                    }
                    if (s.groups != null) {
                        Spacer(Modifier.width(8.dp))
                        TextChip("Uno per materia", c) { onChange(s.copy(groups = null)) }
                    }
                }
                Text("Se un quaderno corrisponde a più gruppi, vince quello più in alto.", color = c.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            }

            Section("Visualizzazione", c) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(GraphSettings.THEME_LIGHT to "Chiaro", GraphSettings.THEME_DARK to "Scuro", GraphSettings.THEME_SYSTEM to "Sistema").forEach { (t, label) ->
                        TextChip(label, c, selected = s.theme == t) { onChange(s.copy(theme = t)) }
                    }
                }
                Toggle("Frecce", s.arrows, c) { onChange(s.copy(arrows = it)) }
                Toggle("Linee dei collegamenti", s.links, c) { onChange(s.copy(links = it)) }
                SliderRow("Nomi visibili da zoom", "${(s.labelZoom * 100).roundToInt()}%", s.textFade, 0f..1f, c) { onChange(s.copy(textFade = it)) }
                SliderRow("Dimensione dei pallini", "×${"%.1f".format(s.nodeSize)}", s.nodeSize, 0.4f..2.5f, c) { onChange(s.copy(nodeSize = it)) }
                SliderRow("Spessore delle linee", "×${"%.1f".format(s.linkThickness)}", s.linkThickness, 0.2f..3f, c) { onChange(s.copy(linkThickness = it)) }
                Row(Modifier.padding(top = 4.dp)) {
                    TextChip("Anima", c, icon = TrattoIcons.Play, onClick = onAnimate)
                }
            }

            Section("Forze", c) {
                SliderRow("Forza verso il centro", null, s.centerForce, 0f..1f, c) { onChange(s.copy(centerForce = it)) }
                SliderRow("Forza di repulsione", null, s.repelForce, 0f..1f, c) { onChange(s.copy(repelForce = it)) }
                SliderRow("Forza dei collegamenti", null, s.linkForce, 0f..1f, c) { onChange(s.copy(linkForce = it)) }
                SliderRow("Distanza dei collegamenti", null, s.linkDistance, 0f..1f, c) { onChange(s.copy(linkDistance = it)) }
            }
        }
    }
}

@Composable
private fun Section(title: String, c: GraphColors, startOpen: Boolean = false, content: @Composable () -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    Column {
        Box(Modifier.fillMaxWidth().height(1.dp).background(c.border))
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (open) TrattoIcons.ChevronDown else TrattoIcons.ChevronRight, contentDescription = null, tint = c.muted, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(title, color = c.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        }
        if (open) Column(Modifier.padding(start = 16.dp, end = 12.dp, bottom = 8.dp)) { content() }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, c: GraphColors, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = on,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.accent,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = c.field,
                uncheckedThumbColor = c.muted,
                uncheckedBorderColor = c.border,
            ),
        )
    }
}

@Composable
private fun SliderRow(label: String, value: String?, v: Float, range: ClosedFloatingPointRange<Float>, c: GraphColors, onChange: (Float) -> Unit) {
    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f))
            if (value != null) Text(value, color = c.muted, fontSize = 13.sp)
        }
        Slider(
            value = v.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.field),
            modifier = Modifier.height(32.dp),
        )
    }
}

@Composable
private fun SearchField(value: String, hint: String, c: GraphColors, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        textStyle = TextStyle(color = c.text, fontSize = 14.sp),
        cursorBrush = SolidColor(c.accent),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        decorationBox = { inner ->
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(c.field).border(1.dp, c.border, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp),
            ) {
                if (value.isEmpty()) Text(hint, color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                inner()
            }
        },
    )
}

@Composable
private fun TextChip(label: String, c: GraphColors, selected: Boolean = false, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .border(1.dp, if (selected) c.accent else c.border, RoundedCornerShape(50))
            .background(if (selected) c.accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = c.text, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = if (selected) c.accent else c.text, fontSize = 13.sp)
    }
}

/** Pallino del colore del gruppo; un tocco apre i colori proposti. */
@Composable
private fun ColorDot(color: Int, c: GraphColors, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.size(26.dp).clip(CircleShape).background(Color(color)).border(1.dp, c.border, CircleShape).clickable { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            GroupSwatches.chunked(4).forEach { row ->
                Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { sw ->
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(Color(sw))
                                .border(if (sw == color) 3.dp else 1.dp, if (sw == color) Color(0xFF7F5AF0) else Color(0x22000000), CircleShape)
                                .clickable { open = false; onPick(sw) },
                        )
                    }
                }
            }
        }
    }
}
