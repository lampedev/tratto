package com.caro.tratto.ui

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caro.tratto.data.NotebookRepository
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import com.caro.tratto.model.Tag
import com.caro.tratto.model.Folder
import com.caro.tratto.model.NotebookMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/*
 * Grafo dei collegamenti, come quello di Obsidian: un pallino per quaderno (più grande se ha più
 * collegamenti), una linea per ogni coppia di quaderni collegati, i colori dei gruppi (di serie uno
 * per materia). Si costruisce solo dai meta.json (linkTargets): niente tratti in memoria, resta
 * leggero anche con centinaia di quaderni. Le posizioni vengono da una simulazione di forze come
 * d3-force; filtri, gruppi, aspetto e forze si regolano dal pannello (GraphSettings.kt).
 */

/** Materie senza un colore scelto: tinte che si leggono sia sul chiaro che sullo scuro. */
private val GroupPalette = listOf(0xFF5DADE2, 0xFF4DB6AC, 0xFFF0A04B, 0xFFE06666, 0xFF9C7FD1, 0xFF6DBE7A, 0xFFE88AB4, 0xFF8FD3F5)

private class GraphNode(
    val id: String,
    val title: String,
    /** Colore del gruppo o del tag, null = grigio del tema. */
    baseColor: Int?,
    /** Gruppo di colore (indice), -1 = nessuno: i quaderni dello stesso gruppo stanno vicini. */
    val group: Int,
    val place: String,
    val isTag: Boolean,
    val created: Long,
) {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var degree = 0
    /** Tenuto dal dito: sta dove dice il dito. */
    var held = false
    /** Durante "Anima" i pallini compaiono uno alla volta. */
    var visible = true
    val neighbors = ArrayList<Int>()
    private val light = baseColor?.let { forLight(Color(it)) }
    private val dark = baseColor?.let { forDark(Color(it)) }

    /** Raggio in dp a zoom 1. */
    fun radius(size: Float) = (4.5f + 2.4f * sqrt(degree.toFloat())) * size
    fun color(c: GraphColors): Color = (if (c.dark) dark else light) ?: c.node
}

/** [edges]: coppie di pallini; [dirs]: 1 = dal primo al secondo, 2 = al contrario, 3 = entrambi. */
private class Graph(val nodes: List<GraphNode>, val edges: IntArray, val dirs: IntArray, val legend: List<Pair<String, Int>>) {
    val edgeCount get() = edges.size / 2
    val notebookCount get() = nodes.count { !it.isTag }
    /** Nomi da scrivere per primi quando non c'è posto per tutti: i più collegati. */
    val labelOrder: List<Int> by lazy { nodes.indices.sortedByDescending { nodes[it].degree } }
}

/** Quello che serve al grafo dalla libreria. */
private class GraphData(val metas: List<NotebookMeta>, val folders: List<Folder>, val tags: List<Tag>)

/** Posizioni e inquadratura del grafo finché l'app è aperta: tornando da un quaderno il grafo è com'era. */
private object GraphMemory {
    val positions = HashMap<String, Pair<Float, Float>>()
    var scale = Float.NaN
    var panX = 0f
    var panY = 0f
    var autoFit = true
}

private fun folderPath(byId: Map<String, Folder>, id: String?): List<String> {
    val names = ArrayList<String>()
    var f = id?.let(byId::get)
    var guard = 0
    while (f != null && guard++ < 64) {
        names.add(0, f.name)
        f = f.parentId?.let(byId::get)
    }
    return names
}

/** Gruppi di serie: uno per materia (cartella di primo livello), con il colore della cartella. */
private fun defaultGroups(folders: List<Folder>): List<GraphColorGroup> =
    folders.filter { it.parentId == null }.sortedBy { it.name.lowercase() }.mapIndexed { i, f ->
        val q = if (f.name.any { it.isWhitespace() }) "\"${f.name}\"" else f.name
        GraphColorGroup("path:$q", f.color ?: GroupPalette[i % GroupPalette.size].toInt())
    }

/** Nome di un gruppo nella legenda: "path:Biologia" → "Biologia", altrimenti la ricerca com'è. */
private fun groupLabel(query: String): String {
    val q = query.trim()
    if (q.startsWith("path:") && q.count { it == ':' } == 1 && (q.drop(5).startsWith("\"") || ' ' !in q)) return q.drop(5).trim('"')
    return q
}

private fun buildGraph(d: GraphData, s: GraphSettings): Graph {
    val byId = d.folders.associateBy { it.id }
    val tagsById = d.tags.associateBy { it.id }
    val search = GraphQuery(s.search)
    val groups = (s.groups ?: defaultGroups(d.folders)).filter { it.query.isNotBlank() }.map { it to GraphQuery(it.query) }
    class Info(val meta: NotebookMeta, val path: String, val place: String, val tagNames: List<String>)
    val infos = d.metas.mapNotNull { m ->
        if (m.id in s.hidden) return@mapNotNull null
        val names = folderPath(byId, m.folderId)
        val tagNames = m.tags.mapNotNull { tagsById[it]?.name }
        if (!search.matches(m.title, names.joinToString("/"), tagNames)) return@mapNotNull null
        Info(m, names.joinToString("/"), names.joinToString(" › "), tagNames)
    }
    val ids = infos.mapTo(HashSet()) { it.meta.id }
    val directed = LinkedHashSet<Pair<String, String>>()
    for (i in infos) for (t in i.meta.linkTargets) if (t != i.meta.id && t in ids) directed.add(i.meta.id to t)
    if (s.tags) for (i in infos) for (t in i.meta.tags) {
        if (t in tagsById && "tag:$t" !in s.hidden) directed.add(i.meta.id to "tag:$t")
    }
    val linked = directed.flatMapTo(HashSet()) { listOf(it.first, it.second) }
    val shown = infos.filter { s.orphans || it.meta.id in linked }
    val usedTags = directed.map { it.second }.filter { it.startsWith("tag:") }.distinct()
    val legendCount = IntArray(groups.size)
    val nodes = ArrayList<GraphNode>()
    for (i in shown) {
        val g = groups.indexOfFirst { (_, q) -> q.matches(i.meta.title, i.path, i.tagNames) }
        if (g >= 0) legendCount[g]++
        nodes.add(GraphNode(i.meta.id, i.meta.title, if (g >= 0) groups[g].first.color else null, g, i.place, false, i.meta.createdAt))
    }
    for (t in usedTags) {
        val tag = tagsById.getValue(t.removePrefix("tag:"))
        nodes.add(GraphNode(t, "#${tag.name}", tag.color or 0xFF000000.toInt(), -1, "Tag", true, 0L))
    }
    val index = nodes.withIndex().associate { (i, n) -> n.id to i }
    // coppie non orientate, con il verso dei collegamenti (per le frecce)
    val pairDir = LinkedHashMap<Long, Int>()
    for ((a, b) in directed) {
        val ia = index[a] ?: continue
        val ib = index[b] ?: continue
        val lo = min(ia, ib)
        val hi = max(ia, ib)
        val key = lo.toLong() shl 32 or hi.toLong()
        pairDir[key] = (pairDir[key] ?: 0) or (if (ia == lo) 1 else 2)
    }
    val edges = IntArray(pairDir.size * 2)
    val dirs = IntArray(pairDir.size)
    var e = 0
    for ((key, dir) in pairDir) {
        val ia = (key shr 32).toInt()
        val ib = key.toInt()
        edges[2 * e] = ia
        edges[2 * e + 1] = ib
        dirs[e] = dir
        nodes[ia].degree++
        nodes[ib].degree++
        nodes[ia].neighbors.add(ib)
        nodes[ib].neighbors.add(ia)
        e++
    }
    // partenza: una spirale stretta (o dove erano l'ultima volta), da cui il grafo "sboccia"
    val golden = PI * (3 - sqrt(5.0))
    nodes.forEachIndexed { i, n ->
        val known = GraphMemory.positions[n.id]
        if (known != null) {
            n.x = known.first
            n.y = known.second
        } else {
            val r = 6f * sqrt(0.5f + i)
            n.x = (r * cos(i * golden)).toFloat()
            n.y = (r * sin(i * golden)).toFloat()
        }
    }
    val legend = groups.indices.filter { legendCount[it] > 0 }.map { groupLabel(groups[it].first.query) to groups[it].first.color }
    return Graph(nodes, edges, dirs, legend)
}

/** Sul fondo scuro i colori scuri si schiariscono. */
private fun forDark(c: Color): Color {
    val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    if (lum >= 0.45f) return c
    val k = (0.45f - lum) / (1f - lum)
    return Color(c.red + (1 - c.red) * k, c.green + (1 - c.green) * k, c.blue + (1 - c.blue) * k)
}

/** Sul fondo chiaro i colori troppo pallidi si scuriscono un po'. */
private fun forLight(c: Color): Color {
    val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    if (lum <= 0.7f) return c
    val k = 1f - 0.7f / lum
    return Color(c.red * (1 - k), c.green * (1 - k), c.blue * (1 - k))
}

/**
 * Forze come d3-force (e Obsidian): molle sui collegamenti, repulsione fra tutti i pallini (che non si
 * sovrappongono), una gravità verso il centro e una più leggera verso il proprio gruppo di colore.
 * [alpha] si raffredda: dopo qualche secondo è tutto fermo. Le forze vengono dal pannello ([configure]).
 */
private class Simulation(private val g: Graph, warm: Boolean) {
    var alpha = if (warm) 0.12f else 1f
    var alphaTarget = 0f
    val running get() = alpha >= ALPHA_MIN || alphaTarget > 0f
    private val n = g.nodes.size
    private val edgeBias = FloatArray(g.edgeCount)
    private val edgeStrength = FloatArray(g.edgeCount)
    private val groupCount = (g.nodes.maxOfOrNull { it.group } ?: -1) + 1
    private val groupX = FloatArray(groupCount)
    private val groupY = FloatArray(groupCount)
    private val groupN = IntArray(groupCount)
    private var gravity = 0.035f
    private var charge = -160f
    private var linkK = 1f
    private var distance = 70f
    var nodeSize = 1f
        private set

    init {
        for (e in 0 until g.edgeCount) {
            val a = g.nodes[g.edges[2 * e]].degree.toFloat()
            val b = g.nodes[g.edges[2 * e + 1]].degree.toFloat()
            edgeBias[e] = a / (a + b)
            edgeStrength[e] = 1f / min(a, b)
        }
    }

    fun configure(s: GraphSettings) {
        gravity = s.gravity
        charge = s.charge
        linkK = s.linkStrength
        distance = s.distance
        nodeSize = s.nodeSize
    }

    fun reheat(target: Float) {
        alphaTarget = target
        if (alpha < target) alpha = target
    }

    fun tick() {
        alpha += (alphaTarget - alpha) * ALPHA_DECAY
        val nodes = g.nodes
        // molle: ogni collegamento tende alla sua lunghezza
        for (e in 0 until g.edgeCount) {
            val a = nodes[g.edges[2 * e]]
            val b = nodes[g.edges[2 * e + 1]]
            if (!a.visible || !b.visible) continue
            var dx = b.x + b.vx - a.x - a.vx
            var dy = b.y + b.vy - a.y - a.vy
            if (dx == 0f && dy == 0f) {
                dx = jiggle()
                dy = jiggle()
            }
            val l = sqrt(dx * dx + dy * dy)
            val k = (l - distance) / l * alpha * min(1f, edgeStrength[e] * linkK)
            dx *= k
            dy *= k
            b.vx -= dx * edgeBias[e]
            b.vy -= dy * edgeBias[e]
            a.vx += dx * (1 - edgeBias[e])
            a.vy += dy * (1 - edgeBias[e])
        }
        // repulsione fra tutti e niente sovrapposizioni (n² va bene fino a qualche migliaio)
        for (i in 0 until n) {
            val p = nodes[i]
            if (!p.visible) continue
            val rp = p.radius(nodeSize)
            for (j in i + 1 until n) {
                val q = nodes[j]
                if (!q.visible) continue
                var dx = q.x - p.x
                var dy = q.y - p.y
                var d2 = dx * dx + dy * dy
                if (d2 < 1e-6f) {
                    dx = jiggle()
                    dy = jiggle()
                    d2 = dx * dx + dy * dy
                }
                if (d2 > MAX_DISTANCE2) continue
                val w = charge * alpha / max(d2, 1f)
                p.vx += dx * w
                p.vy += dy * w
                q.vx -= dx * w
                q.vy -= dy * w
                val minD = rp + q.radius(nodeSize) + COLLIDE_GAP
                if (d2 < minD * minD) {
                    val d = sqrt(d2)
                    val push = (minD - d) / d * COLLIDE_STRENGTH
                    p.vx -= dx * push
                    p.vy -= dy * push
                    q.vx += dx * push
                    q.vy += dy * push
                }
            }
        }
        // gravità: verso il centro e verso il centro del proprio gruppo
        groupX.fill(0f)
        groupY.fill(0f)
        groupN.fill(0)
        for (p in nodes) if (p.visible && p.group >= 0) {
            groupX[p.group] += p.x
            groupY[p.group] += p.y
            groupN[p.group]++
        }
        for (p in nodes) {
            if (!p.visible) continue
            p.vx -= p.x * gravity * alpha
            p.vy -= p.y * gravity * alpha
            if (p.group >= 0 && groupN[p.group] > 1) {
                p.vx += (groupX[p.group] / groupN[p.group] - p.x) * COHESION * alpha
                p.vy += (groupY[p.group] / groupN[p.group] - p.y) * COHESION * alpha
            }
        }
        for (p in nodes) {
            if (p.held || !p.visible) {
                p.vx = 0f
                p.vy = 0f
                continue
            }
            p.vx *= VELOCITY_KEEP
            p.vy *= VELOCITY_KEEP
            p.x += p.vx
            p.y += p.vy
        }
    }

    private fun jiggle() = (Random.nextFloat() - 0.5f) * 1e-3f

    companion object {
        const val ALPHA_MIN = 0.002f
        val ALPHA_DECAY = 1f - ALPHA_MIN.pow(1f / 300f)
        const val MAX_DISTANCE2 = 900f * 900f
        const val COLLIDE_GAP = 6f
        const val COLLIDE_STRENGTH = 0.35f
        const val COHESION = 0.04f
        const val VELOCITY_KEEP = 0.6f
    }
}

/** Inquadratura: punto del mondo (dp) → schermo (px) = centro + pan + mondo × scala × densità. */
private class Camera {
    var scale = 1f
    var panX = 0f
    var panY = 0f
    var autoFit = true
    var width = 0f
    var height = 0f
    var density = 1f

    fun sx(x: Float) = width / 2 + panX + x * scale * density
    fun sy(y: Float) = height / 2 + panY + y * scale * density
    fun wx(px: Float) = (px - width / 2 - panX) / (scale * density)
    fun wy(py: Float) = (py - height / 2 - panY) / (scale * density)

    fun zoomAround(px: Float, py: Float, factor: Float) {
        val wx = wx(px)
        val wy = wy(py)
        scale = (scale * factor).coerceIn(0.12f, 6f)
        panX = px - width / 2 - wx * scale * density
        panY = py - height / 2 - wy * scale * density
    }

    /** Avvicina l'inquadratura a quella che contiene tutto il grafo; true se c'è ancora da muoversi. */
    fun stepFit(nodes: List<GraphNode>, size: Float, top: Float, bottom: Float, right: Float): Boolean {
        if (nodes.none { it.visible } || width <= 0f) return false
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (p in nodes) {
            if (!p.visible) continue
            val r = p.radius(size)
            x0 = min(x0, p.x - r)
            y0 = min(y0, p.y - r)
            x1 = max(x1, p.x + r)
            y1 = max(y1, p.y + r)
        }
        // un pallino solo: un po' di margine, non uno zoom infinito
        if (x1 - x0 < 60f) { x0 -= 30f; x1 += 30f }
        if (y1 - y0 < 60f) { y0 -= 30f; y1 += 30f }
        val margin = 48f * density
        val availW = width - 2 * margin - right
        val availH = height - top - bottom - 2 * margin
        val target = min(availW / ((x1 - x0) * density), availH / ((y1 - y0) * density)).coerceIn(0.2f, 1.6f)
        val tx = -(x0 + x1) / 2 * target * density - right / 2
        val ty = (top - bottom) / 2 - (y0 + y1) / 2 * target * density
        val k = 0.12f
        scale += (target - scale) * k
        panX += (tx - panX) * k
        panY += (ty - panY) * k
        return kotlin.math.abs(target - scale) > 0.002f || kotlin.math.abs(tx - panX) > 0.5f || kotlin.math.abs(ty - panY) > 0.5f
    }
}

@Composable
fun GraphScreen(repository: NotebookRepository, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { GraphPrefs(context) }
    var settings by remember { mutableStateOf(prefs.load()) }
    var data by remember { mutableStateOf<GraphData?>(null) }
    var graph by remember { mutableStateOf<Graph?>(null) }
    var panel by rememberSaveable { mutableStateOf(false) }
    var fitRequest by remember { mutableIntStateOf(0) }
    var animateRequest by remember { mutableIntStateOf(0) }
    fun update(s: GraphSettings) {
        // filtri diversi = altri pallini: si riinquadra tutto
        if (s.search != settings.search || s.tags != settings.tags || s.orphans != settings.orphans || s.hidden != settings.hidden) GraphMemory.autoFit = true
        settings = s
        prefs.save(s)
    }
    LaunchedEffect(Unit) {
        data = withContext(Dispatchers.IO) { GraphData(repository.list(), repository.folders(), repository.tags()) }
    }
    val filters = listOf(settings.search, settings.tags, settings.orphans, settings.hidden, settings.groups)
    LaunchedEffect(data, filters) {
        val d = data ?: return@LaunchedEffect
        val s = settings
        graph = withContext(Dispatchers.Default) { buildGraph(d, s) }
    }
    val dark = when (settings.theme) {
        GraphSettings.THEME_DARK -> true
        GraphSettings.THEME_SYSTEM -> isSystemInDarkTheme()
        else -> false
    }
    val c = remember(dark) { GraphColors(dark) }
    Box(Modifier.fillMaxSize().background(c.background)) {
        graph?.let { g ->
            GraphCanvas(
                g, settings, c, fitRequest, animateRequest, panel, onOpen,
                onHide = { id -> update(settings.copy(hidden = settings.hidden + id)) },
            )
        }
        // in alto: indietro, titolo e conti, inquadra, tema, impostazioni
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(TrattoIcons.Back, contentDescription = "Indietro", tint = c.text) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Grafo dei collegamenti", color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                graph?.let { g ->
                    val n = g.notebookCount
                    val hidden = settings.hidden.size
                    Text(
                        "$n ${if (n == 1) "quaderno" else "quaderni"} · ${g.edgeCount} ${if (g.edgeCount == 1) "collegamento" else "collegamenti"}" +
                            if (hidden > 0) " · $hidden ${if (hidden == 1) "nascosto" else "nascosti"}" else "",
                        color = c.muted,
                        fontSize = 13.sp,
                    )
                }
            }
            IconButton(onClick = { fitRequest++ }) { Icon(TrattoIcons.Fit, contentDescription = "Inquadra tutto", tint = c.muted) }
            IconButton(onClick = { update(settings.copy(theme = if (dark) GraphSettings.THEME_LIGHT else GraphSettings.THEME_DARK)) }) {
                Icon(if (dark) TrattoIcons.Sun else TrattoIcons.Moon, contentDescription = if (dark) "Tema chiaro" else "Tema scuro", tint = c.muted)
            }
            IconButton(onClick = { panel = !panel }) {
                Icon(TrattoIcons.Settings, contentDescription = "Impostazioni del grafo", tint = if (panel) c.accent else c.muted)
            }
        }
        val g = graph
        if (g != null && (g.nodes.isEmpty() || g.edgeCount == 0)) {
            Text(
                when {
                    g.nodes.isEmpty() && (settings.search.isNotBlank() || settings.hidden.isNotEmpty()) -> "Nessun quaderno corrisponde ai filtri"
                    g.nodes.isEmpty() -> "Nessun quaderno collegato"
                    else -> "Ancora nessun collegamento: seleziona qualcosa col lazo → Collega a…"
                },
                color = c.muted,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
            )
        }
        if (panel) {
            val d = data
            val hiddenTitles = remember(settings.hidden, d) {
                val titles = d?.metas?.associate { it.id to it.title }.orEmpty() + d?.tags?.associate { "tag:${it.id}" to "#${it.name}" }.orEmpty()
                settings.hidden.map { it to (titles[it] ?: "Quaderno eliminato") }.sortedBy { it.second.lowercase() }
            }
            GraphSettingsPanel(
                s = settings,
                defaultGroups = remember(d) { d?.folders?.let(::defaultGroups).orEmpty() },
                hiddenTitles = hiddenTitles,
                c = c,
                onChange = ::update,
                onAnimate = { animateRequest++ },
                onClose = { panel = false },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().navigationBarsPadding()
                    .padding(top = 60.dp, end = 12.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun GraphCanvas(
    g: Graph,
    settings: GraphSettings,
    c: GraphColors,
    fitRequest: Int,
    animateRequest: Int,
    panelOpen: Boolean,
    onOpen: (String) -> Unit,
    onHide: (String) -> Unit,
) {
    val density = LocalDensity.current
    // con il pannello aperto l'inquadratura lascia libera la sua parte di schermo
    val rightInset = remember { floatArrayOf(0f) }
    rightInset[0] = if (panelOpen) with(density) { 354.dp.toPx() } else 0f
    val open by rememberUpdatedState(onOpen)
    val s by rememberUpdatedState(settings)
    val camera = remember(g) {
        Camera().apply {
            if (!GraphMemory.scale.isNaN()) {
                scale = GraphMemory.scale
                panX = GraphMemory.panX
                panY = GraphMemory.panY
                autoFit = GraphMemory.autoFit
            }
        }
    }
    val sim = remember(g) {
        Simulation(g, warm = g.nodes.isNotEmpty() && g.nodes.all { it.id in GraphMemory.positions }).apply { configure(settings) }
    }
    // il disegno legge [frame]: ogni passo della simulazione o del dito lo fa ridisegnare
    var frame by remember { mutableIntStateOf(0) }
    var selected by remember(g) { mutableIntStateOf(-1) }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    val topInset = with(density) { 72.dp.toPx() }
    val bottomInset = with(density) { 24.dp.toPx() }
    // "Anima": i pallini compaiono nell'ordine in cui sono nati i quaderni
    val order = remember(g) { g.nodes.indices.sortedBy { if (g.nodes[it].isTag) Long.MAX_VALUE else g.nodes[it].created } }
    var animStart by remember(g) { mutableLongStateOf(-1L) }
    var revealed by remember(g) { mutableIntStateOf(g.nodes.size) }

    DisposableEffect(g) {
        onDispose { for (p in g.nodes) if (p.visible) GraphMemory.positions[p.id] = p.x to p.y }
    }
    // forze o dimensione cambiate dal pannello: il grafo si riassesta
    val forces = listOf(settings.centerForce, settings.repelForce, settings.linkForce, settings.linkDistance, settings.nodeSize)
    val lastForces = remember(g) { arrayOf(forces) }
    LaunchedEffect(forces) {
        sim.configure(settings)
        if (lastForces[0] != forces) {
            lastForces[0] = forces
            sim.alpha = max(sim.alpha, 0.3f)
            wake.trySend(Unit)
        }
    }
    LaunchedEffect(panelOpen) { if (camera.autoFit) wake.trySend(Unit) }
    LaunchedEffect(fitRequest) {
        if (fitRequest > 0) {
            camera.autoFit = true
            wake.trySend(Unit)
        }
    }
    LaunchedEffect(animateRequest) {
        if (animateRequest == 0 || g.nodes.isEmpty()) return@LaunchedEffect
        for (p in g.nodes) {
            p.visible = false
            p.vx = 0f
            p.vy = 0f
        }
        selected = -1
        revealed = 0
        camera.autoFit = true
        sim.alpha = 1f
        animStart = System.nanoTime()
        wake.trySend(Unit)
    }

    LaunchedEffect(g) {
        while (true) {
            withFrameNanos { }
            val animating = animStart >= 0
            if (animating) {
                val duration = (g.nodes.size * 90L).coerceIn(3000L, 8000L) * 1_000_000L
                val want = (((System.nanoTime() - animStart).toDouble() / duration) * g.nodes.size).toInt().coerceIn(0, g.nodes.size)
                while (revealed < want) {
                    val p = g.nodes[order[revealed]]
                    // nasce vicino a un quaderno già comparso a cui è collegato, o al centro
                    val near = p.neighbors.map { g.nodes[it] }.firstOrNull { it.visible }
                    p.x = (near?.x ?: 0f) + (Random.nextFloat() - 0.5f) * 20f
                    p.y = (near?.y ?: 0f) + (Random.nextFloat() - 0.5f) * 20f
                    p.visible = true
                    revealed++
                }
                sim.alpha = max(sim.alpha, 0.4f)
                if (revealed >= g.nodes.size) animStart = -1L
            }
            val simulating = sim.running
            if (simulating) sim.tick()
            val fitting = camera.autoFit && camera.stepFit(g.nodes, s.nodeSize, topInset, bottomInset, rightInset[0])
            if (simulating || fitting || animating) {
                frame++
            } else {
                // tutto fermo: si ricorda dov'è e si aspetta il dito
                for (p in g.nodes) GraphMemory.positions[p.id] = p.x to p.y
                GraphMemory.scale = camera.scale
                GraphMemory.panX = camera.panX
                GraphMemory.panY = camera.panY
                GraphMemory.autoFit = camera.autoFit
                wake.receive()
            }
        }
    }

    val labelPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL) } }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(g) {
                    val slop = viewConfiguration.touchSlop
                    fun hit(at: Offset): Int {
                        var best = -1
                        var bestD = Float.MAX_VALUE
                        val minR = 22.dp.toPx()
                        for ((i, p) in g.nodes.withIndex()) {
                            if (!p.visible) continue
                            val dx = camera.sx(p.x) - at.x
                            val dy = camera.sy(p.y) - at.y
                            val d = sqrt(dx * dx + dy * dy)
                            if (d < max(minR, p.radius(s.nodeSize) * camera.scale * camera.density + 8f) && d < bestD) {
                                best = i
                                bestD = d
                            }
                        }
                        return best
                    }
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var dragged = hit(down.position)
                        val tapped = dragged
                        val wasSelected = selected
                        var moved = false
                        var multi = false
                        if (dragged >= 0) {
                            g.nodes[dragged].held = true
                            selected = dragged
                            frame++
                        }
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                // due dita: zoom e spostamento; il pallino tenuto si lascia
                                multi = true
                                moved = true
                                camera.autoFit = false
                                if (dragged >= 0) {
                                    g.nodes[dragged].held = false
                                    dragged = -1
                                    sim.reheat(0f)
                                }
                                val ce = event.calculateCentroid()
                                camera.zoomAround(ce.x, ce.y, event.calculateZoom())
                                val pan = event.calculatePan()
                                camera.panX += pan.x
                                camera.panY += pan.y
                            } else {
                                val ch = pressed[0]
                                if (!moved && (ch.position - down.position).getDistance() > slop) moved = true
                                if (moved && !multi) {
                                    // l'inquadratura sta ferma: il pallino resta sotto il dito
                                    camera.autoFit = false
                                    if (dragged >= 0) {
                                        val p = g.nodes[dragged]
                                        p.x = camera.wx(ch.position.x)
                                        p.y = camera.wy(ch.position.y)
                                        sim.reheat(0.3f)
                                    } else {
                                        camera.panX += ch.position.x - ch.previousPosition.x
                                        camera.panY += ch.position.y - ch.previousPosition.y
                                    }
                                }
                            }
                            event.changes.forEach { it.consume() }
                            frame++
                            wake.trySend(Unit)
                        }
                        if (dragged >= 0) g.nodes[dragged].held = false
                        sim.reheat(0f)
                        if (!moved) {
                            // tocco: sul quaderno già scelto lo apre, altrove sceglie o toglie la scelta
                            if (tapped >= 0 && tapped == wasSelected && !g.nodes[tapped].isTag) open(g.nodes[tapped].id)
                            selected = tapped
                        }
                        frame++
                        wake.trySend(Unit)
                    }
                },
        ) {
            frame
            camera.width = size.width
            camera.height = size.height
            camera.density = density.density
            // i nomi arrivano quando il grafo si è aperto (prima sarebbero un groviglio)
            drawGraph(g, camera, selected, labelPaint, ((0.45f - sim.alpha) / 0.3f).coerceIn(0f, 1f), settings, c)
        }
        if (g.legend.isNotEmpty()) {
            Legend(g, c, Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp))
        }
        if (selected in g.nodes.indices) {
            val p = g.nodes[selected]
            SelectedCard(
                p, c,
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
                onOpen = { onOpen(p.id) },
                onHide = {
                    selected = -1
                    onHide(p.id)
                },
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGraph(
    g: Graph,
    cam: Camera,
    selected: Int,
    labelPaint: Paint,
    labelFade: Float,
    s: GraphSettings,
    c: GraphColors,
) {
    val focus = HashSet<Int>()
    if (selected in g.nodes.indices) {
        focus.add(selected)
        focus.addAll(g.nodes[selected].neighbors)
    }
    val dimmed = focus.isNotEmpty()
    val px = cam.scale * cam.density
    fun radius(p: GraphNode) = max(1.6f * cam.density, p.radius(s.nodeSize) * px)
    // linee (e frecce, dalla parte del quaderno a cui porta il collegamento)
    if (s.links) {
        val thin = max(0.5f, 1.1f * cam.density * s.linkThickness * min(1f, cam.scale))
        val head = max(3.5f * cam.density, 5.5f * cam.density * sqrt(s.linkThickness) * min(1.3f, cam.scale))
        val arrow = Path()
        for (e in 0 until g.edgeCount) {
            val ia = g.edges[2 * e]
            val ib = g.edges[2 * e + 1]
            val a = g.nodes[ia]
            val b = g.nodes[ib]
            if (!a.visible || !b.visible) continue
            val lit = dimmed && (ia == selected || ib == selected)
            val color = when {
                lit -> c.accent
                dimmed -> c.lineDim
                else -> c.line
            }
            val ax = cam.sx(a.x)
            val ay = cam.sy(a.y)
            val bx = cam.sx(b.x)
            val by = cam.sy(b.y)
            drawLine(color, Offset(ax, ay), Offset(bx, by), strokeWidth = if (lit) thin * 1.6f else thin)
            if (!s.arrows) continue
            val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            if (len < 1f) continue
            val ux = (bx - ax) / len
            val uy = (by - ay) / len
            fun tip(tx: Float, ty: Float, dx: Float, dy: Float, r: Float) {
                val px0 = tx - dx * (r + 1.5f * cam.density)
                val py0 = ty - dy * (r + 1.5f * cam.density)
                arrow.reset()
                arrow.moveTo(px0, py0)
                arrow.lineTo(px0 - dx * head * 1.6f - dy * head * 0.7f, py0 - dy * head * 1.6f + dx * head * 0.7f)
                arrow.lineTo(px0 - dx * head * 1.6f + dy * head * 0.7f, py0 - dy * head * 1.6f - dx * head * 0.7f)
                arrow.close()
                drawPath(arrow, color)
            }
            if (g.dirs[e] and 1 != 0) tip(bx, by, ux, uy, radius(b))
            if (g.dirs[e] and 2 != 0) tip(ax, ay, -ux, -uy, radius(a))
        }
    }
    // pallini pieni, come in Obsidian; quello scelto nel colore di accento
    for ((i, p) in g.nodes.withIndex()) {
        if (!p.visible) continue
        val r = radius(p)
        val ctr = Offset(cam.sx(p.x), cam.sy(p.y))
        if (ctr.x < -r * 3 || ctr.y < -r * 3 || ctr.x > size.width + r * 3 || ctr.y > size.height + r * 3) continue
        val faded = dimmed && i !in focus
        val color = if (i == selected) c.accent else p.color(c)
        drawCircle(color.copy(alpha = if (faded) 0.25f else 1f), r, ctr)
        if (p.isTag) drawCircle(c.background.copy(alpha = if (faded) 0.25f else 1f), r * 0.45f, ctr)
    }
    // nomi: dalla soglia di zoom scelta in poi; il quaderno scelto e i suoi vicini sempre.
    // Mai uno sopra l'altro: se non c'è posto vince il più collegato
    val z = s.labelZoom
    val byZoom = ((cam.scale - z * 0.75f) / (z * 0.25f)).coerceIn(0f, 1f)
    labelPaint.textSize = 12.5f * cam.density
    labelPaint.setShadowLayer(4f * cam.density, 0f, 0f, c.background.toArgb())
    val placed = ArrayList<android.graphics.RectF>()
    val pad = 4f * cam.density
    val order = if (selected in g.nodes.indices) listOf(selected) + g.nodes[selected].neighbors + g.labelOrder else g.labelOrder
    val done = HashSet<Int>()
    for (i in order) {
        if (!done.add(i)) continue
        val p = g.nodes[i]
        if (!p.visible) continue
        val alpha = when {
            i in focus -> 1f
            dimmed -> byZoom * 0.35f * labelFade
            else -> byZoom * labelFade
        }
        if (alpha <= 0.02f) continue
        val r = radius(p)
        val x = cam.sx(p.x)
        val y = cam.sy(p.y) + r + 15f * cam.density
        if (x < -200 || y < -50 || x > size.width + 200 || y > size.height + 50) continue
        val text = if (p.title.length > 30) p.title.take(29) + "…" else p.title
        labelPaint.isFakeBoldText = i == selected
        val w = labelPaint.measureText(text)
        val box = android.graphics.RectF(x - w / 2 - pad, y - labelPaint.textSize, x + w / 2 + pad, y + pad)
        if (i != selected && placed.any { android.graphics.RectF.intersects(it, box) }) continue
        placed.add(box)
        labelPaint.color = c.text.copy(alpha = alpha).toArgb()
        drawContext.canvas.nativeCanvas.drawText(text, x - w / 2, y, labelPaint)
    }
}

@Composable
private fun Legend(g: Graph, c: GraphColors, modifier: Modifier) {
    Surface(color = c.card.copy(alpha = 0.9f), shape = RoundedCornerShape(14.dp), modifier = modifier.border(1.dp, c.border, RoundedCornerShape(14.dp))) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            g.legend.take(10).forEach { (name, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(color)))
                    Spacer(Modifier.size(8.dp))
                    Text(name, color = c.text.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                }
            }
            if (g.legend.size > 10) Text("e altri ${g.legend.size - 10}", color = c.muted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SelectedCard(p: GraphNode, c: GraphColors, modifier: Modifier, onOpen: () -> Unit, onHide: () -> Unit) {
    Surface(
        color = c.card,
        shape = RoundedCornerShape(16.dp),
        shadowElevation = 6.dp,
        modifier = modifier.widthIn(max = 400.dp).border(1.dp, c.border, RoundedCornerShape(16.dp)),
    ) {
        Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(p.color(c)))
            Column(Modifier.weight(1f, fill = false).padding(horizontal = 12.dp)) {
                Text(p.title, color = c.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val links = when {
                    p.isTag -> if (p.degree == 1) "1 quaderno" else "${p.degree} quaderni"
                    p.degree == 1 -> "1 collegamento"
                    else -> "${p.degree} collegamenti"
                }
                Text(if (p.place.isEmpty()) links else "${p.place} · $links", color = c.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onHide) { Icon(TrattoIcons.EyeOff, contentDescription = "Nascondi dal grafo", tint = c.muted) }
            if (!p.isTag) {
                Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = Color.White)) { Text("Apri") }
            }
        }
    }
}
