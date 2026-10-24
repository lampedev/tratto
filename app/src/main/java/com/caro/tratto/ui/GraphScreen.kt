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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.caro.tratto.data.NotebookRepository
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
 * collegamenti), una linea per ogni coppia di quaderni collegati, i colori delle materie.
 * Si costruisce solo dai meta.json (linkTargets): niente tratti in memoria, resta leggero anche con
 * centinaia di quaderni. Le posizioni vengono da una simulazione di forze come d3-force.
 */

private val GraphBackground = Color(0xFF0E1117)
private val GraphGlow = Color(0xFF1A2030)
private val GraphText = Color(0xFFE6E9F0)
private val GraphMuted = Color(0xFF8A93A6)
private val GraphCard = Color(0xFF191E29)
/** Quaderni fuori dalle cartelle: grigio chiaro, come le note senza gruppo di Obsidian. */
private val LooseColor = Color(0xFFC3CAD6)
/** Materie senza un colore scelto: tinte che si distinguono sul fondo scuro. */
private val GroupPalette = listOf(0xFF7FB3F5, 0xFF7ED6C4, 0xFFF5C26B, 0xFFF59A9A, 0xFFC59AF5, 0xFF9AD67E, 0xFFF5A3D3, 0xFF8FD3F5)

private class GraphNode(
    val id: String,
    val title: String,
    val color: Color,
    /** Materia (cartella di primo livello) come indice in [Graph.groups], -1 = nessuna. */
    val group: Int,
    val place: String,
) {
    var x = 0f
    var y = 0f
    var vx = 0f
    var vy = 0f
    var degree = 0
    /** Tenuto dal dito: sta dove dice il dito. */
    var held = false
    /** Raggio in dp a zoom 1. */
    val radius get() = 4.5f + 2.4f * sqrt(degree.toFloat())
    val neighbors = ArrayList<Int>()
}

private class GraphGroup(val name: String, val color: Color)

private class Graph(val nodes: List<GraphNode>, val edges: IntArray, val groups: List<GraphGroup>) {
    val edgeCount get() = edges.size / 2
    /** Nomi da scrivere per primi quando non c'è posto per tutti: i più collegati. */
    val labelOrder: List<Int> by lazy { nodes.indices.sortedByDescending { nodes[it].degree } }
}

/** Posizioni e inquadratura del grafo finché l'app è aperta: tornando da un quaderno il grafo è com'era. */
private object GraphMemory {
    val positions = HashMap<String, Pair<Float, Float>>()
    var scale = Float.NaN
    var panX = 0f
    var panY = 0f
    var autoFit = true
}

private fun buildGraph(metas: List<NotebookMeta>, folders: List<Folder>, withLoose: Boolean): Graph {
    val byId = folders.associateBy { it.id }
    fun topOf(id: String?): Folder? {
        var f = id?.let(byId::get) ?: return null
        var guard = 0
        while (f.parentId != null && guard++ < 64) f = byId[f.parentId] ?: break
        return f
    }
    fun pathOf(id: String?): String {
        val names = ArrayList<String>()
        var f = id?.let(byId::get)
        var guard = 0
        while (f != null && guard++ < 64) {
            names.add(0, f.name)
            f = f.parentId?.let(byId::get)
        }
        return names.joinToString(" › ")
    }
    val ids = metas.mapTo(HashSet()) { it.id }
    val pairs = LinkedHashSet<Pair<String, String>>()
    for (m in metas) for (t in m.linkTargets) {
        if (t == m.id || t !in ids) continue
        pairs.add(if (m.id < t) m.id to t else t to m.id)
    }
    val linked = pairs.flatMapTo(HashSet()) { listOf(it.first, it.second) }
    val shown = metas.filter { withLoose || it.id in linked }
    val tops = shown.mapNotNull { topOf(it.folderId) }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
    val groups = tops.mapIndexed { i, f ->
        GraphGroup(f.name, f.color?.let { brighten(Color(it)) } ?: Color(GroupPalette[i % GroupPalette.size].toInt()))
    }
    val groupIndex = tops.withIndex().associate { (i, f) -> f.id to i }
    val nodes = shown.map { m ->
        val g = topOf(m.folderId)?.let { groupIndex[it.id] } ?: -1
        GraphNode(m.id, m.title, if (g >= 0) groups[g].color else LooseColor, g, pathOf(m.folderId))
    }
    val index = nodes.withIndex().associate { (i, n) -> n.id to i }
    val edges = IntArray(pairs.size * 2)
    pairs.forEachIndexed { e, (a, b) ->
        val ia = index.getValue(a)
        val ib = index.getValue(b)
        edges[2 * e] = ia
        edges[2 * e + 1] = ib
        nodes[ia].degree++
        nodes[ib].degree++
        nodes[ia].neighbors.add(ib)
        nodes[ib].neighbors.add(ia)
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
    return Graph(nodes, edges, groups)
}

/** I colori delle cartelle sono pensati per il fondo chiaro: quelli scuri si schiariscono. */
private fun brighten(c: Color): Color {
    val lum = 0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue
    if (lum >= 0.45f) return c
    val k = (0.45f - lum) / (1f - lum)
    return Color(c.red + (1 - c.red) * k, c.green + (1 - c.green) * k, c.blue + (1 - c.blue) * k)
}

/**
 * Forze come d3-force: molle sui collegamenti, repulsione fra tutti i quaderni (che non si
 * sovrappongono), una leggera gravità verso il centro e verso la propria materia, che così si
 * raccoglie in un grappolo del suo colore. [alpha] si raffredda: dopo qualche secondo è tutto fermo.
 */
private class Simulation(private val g: Graph, warm: Boolean) {
    var alpha = if (warm) 0.12f else 1f
    var alphaTarget = 0f
    val running get() = alpha >= ALPHA_MIN || alphaTarget > 0f
    private val n = g.nodes.size
    private val edgeBias = FloatArray(g.edgeCount)
    private val edgeStrength = FloatArray(g.edgeCount)
    private val groupX = FloatArray(g.groups.size)
    private val groupY = FloatArray(g.groups.size)
    private val groupN = IntArray(g.groups.size)

    init {
        for (e in 0 until g.edgeCount) {
            val a = g.nodes[g.edges[2 * e]].degree.toFloat()
            val b = g.nodes[g.edges[2 * e + 1]].degree.toFloat()
            edgeBias[e] = a / (a + b)
            edgeStrength[e] = 1f / min(a, b)
        }
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
            var dx = b.x + b.vx - a.x - a.vx
            var dy = b.y + b.vy - a.y - a.vy
            if (dx == 0f && dy == 0f) {
                dx = jiggle()
                dy = jiggle()
            }
            val l = sqrt(dx * dx + dy * dy)
            val k = (l - LINK_DISTANCE) / l * alpha * edgeStrength[e]
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
            for (j in i + 1 until n) {
                val q = nodes[j]
                var dx = q.x - p.x
                var dy = q.y - p.y
                var d2 = dx * dx + dy * dy
                if (d2 < 1e-6f) {
                    dx = jiggle()
                    dy = jiggle()
                    d2 = dx * dx + dy * dy
                }
                if (d2 > MAX_DISTANCE2) continue
                val w = CHARGE * alpha / max(d2, 1f)
                p.vx += dx * w
                p.vy += dy * w
                q.vx -= dx * w
                q.vy -= dy * w
                val minD = p.radius + q.radius + COLLIDE_GAP
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
        // gravità: verso il centro e verso il centro della propria materia
        groupX.fill(0f)
        groupY.fill(0f)
        groupN.fill(0)
        for (p in nodes) if (p.group >= 0) {
            groupX[p.group] += p.x
            groupY[p.group] += p.y
            groupN[p.group]++
        }
        for (p in nodes) {
            p.vx -= p.x * GRAVITY * alpha
            p.vy -= p.y * GRAVITY * alpha
            if (p.group >= 0 && groupN[p.group] > 1) {
                p.vx += (groupX[p.group] / groupN[p.group] - p.x) * COHESION * alpha
                p.vy += (groupY[p.group] / groupN[p.group] - p.y) * COHESION * alpha
            }
        }
        for (p in nodes) {
            if (p.held) {
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
        const val LINK_DISTANCE = 70f
        const val CHARGE = -160f
        const val MAX_DISTANCE2 = 900f * 900f
        const val COLLIDE_GAP = 6f
        const val COLLIDE_STRENGTH = 0.35f
        const val GRAVITY = 0.035f
        const val COHESION = 0.05f
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
    fun stepFit(nodes: List<GraphNode>, top: Float, bottom: Float): Boolean {
        if (nodes.isEmpty() || width <= 0f) return false
        var x0 = Float.MAX_VALUE
        var y0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE
        var y1 = -Float.MAX_VALUE
        for (p in nodes) {
            x0 = min(x0, p.x - p.radius)
            y0 = min(y0, p.y - p.radius)
            x1 = max(x1, p.x + p.radius)
            y1 = max(y1, p.y + p.radius)
        }
        val margin = 48f * density
        val availW = width - 2 * margin
        val availH = height - top - bottom - 2 * margin
        val target = min(availW / ((x1 - x0) * density), availH / ((y1 - y0) * density)).coerceIn(0.2f, 1.6f)
        val tx = -(x0 + x1) / 2 * target * density
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
    var withLoose by remember { mutableStateOf(true) }
    var graph by remember { mutableStateOf<Graph?>(null) }
    LaunchedEffect(withLoose) {
        graph = withContext(Dispatchers.IO) { buildGraph(repository.list(), repository.folders(), withLoose) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(GraphGlow, GraphBackground), radius = 1800f)),
    ) {
        graph?.let { g -> GraphCanvas(g, onOpen) }
        // in alto: indietro, titolo e conti, quaderni senza collegamenti sì/no
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(TrattoIcons.Back, contentDescription = "Indietro", tint = GraphText) }
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Grafo dei collegamenti", color = GraphText, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                graph?.let { g ->
                    Text("${g.nodes.size} ${if (g.nodes.size == 1) "quaderno" else "quaderni"} · ${g.edgeCount} ${if (g.edgeCount == 1) "collegamento" else "collegamenti"}", color = GraphMuted, fontSize = 13.sp)
                }
            }
            GraphChip("Anche senza collegamenti", withLoose) {
                GraphMemory.autoFit = true
                withLoose = !withLoose
            }
        }
        val g = graph
        if (g != null && g.edgeCount == 0) {
            Text(
                if (g.nodes.isEmpty()) "Nessun quaderno collegato" else "Ancora nessun collegamento: seleziona qualcosa col lazo → Collega a…",
                color = GraphMuted,
                fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun GraphChip(label: String, on: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, if (on) GraphText.copy(alpha = 0.5f) else GraphMuted.copy(alpha = 0.35f), RoundedCornerShape(50))
            .background(if (on) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) Icon(TrattoIcons.Check, null, tint = GraphText, modifier = Modifier.size(16.dp).padding(end = 2.dp))
        Text(label, color = if (on) GraphText else GraphMuted, fontSize = 13.sp)
    }
}

@Composable
private fun GraphCanvas(g: Graph, onOpen: (String) -> Unit) {
    val density = LocalDensity.current
    val open by rememberUpdatedState(onOpen)
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
    val sim = remember(g) { Simulation(g, warm = g.nodes.isNotEmpty() && g.nodes.all { it.id in GraphMemory.positions }) }
    // il disegno legge [frame]: ogni passo della simulazione o del dito lo fa ridisegnare
    var frame by remember { mutableIntStateOf(0) }
    var selected by remember(g) { mutableIntStateOf(-1) }
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    val topInset = with(density) { 72.dp.toPx() }
    val bottomInset = with(density) { 24.dp.toPx() }

    LaunchedEffect(g) {
        while (true) {
            withFrameNanos { }
            val simulating = sim.running
            if (simulating) sim.tick()
            val fitting = camera.autoFit && camera.stepFit(g.nodes, topInset, bottomInset)
            if (simulating || fitting) {
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

    val labelPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            setShadowLayer(6f, 0f, 0f, android.graphics.Color.BLACK)
        }
    }

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
                            val dx = camera.sx(p.x) - at.x
                            val dy = camera.sy(p.y) - at.y
                            val d = sqrt(dx * dx + dy * dy)
                            if (d < max(minR, p.radius * camera.scale * camera.density + 8f) && d < bestD) {
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
                                val c = event.calculateCentroid()
                                camera.zoomAround(c.x, c.y, event.calculateZoom())
                                val pan = event.calculatePan()
                                camera.panX += pan.x
                                camera.panY += pan.y
                            } else {
                                val c = pressed[0]
                                if (!moved && (c.position - down.position).getDistance() > slop) moved = true
                                if (moved && !multi) {
                                    // l'inquadratura sta ferma: il pallino resta sotto il dito
                                    camera.autoFit = false
                                    if (dragged >= 0) {
                                        val p = g.nodes[dragged]
                                        p.x = camera.wx(c.position.x)
                                        p.y = camera.wy(c.position.y)
                                        sim.reheat(0.3f)
                                    } else {
                                        camera.panX += c.position.x - c.previousPosition.x
                                        camera.panY += c.position.y - c.previousPosition.y
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
                            // tocco: sul pallino già scelto lo apre, altrove sceglie o toglie la scelta
                            if (tapped >= 0 && tapped == wasSelected) open(g.nodes[tapped].id)
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
            drawGraph(g, camera, selected, labelPaint, labelFade = ((0.45f - sim.alpha) / 0.3f).coerceIn(0f, 1f))
        }
        // in basso: la legenda delle materie e, se c'è, il quaderno scelto
        Column(
            Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (g.groups.isNotEmpty() || g.nodes.any { it.group < 0 }) Legend(g)
        }
        if (selected in g.nodes.indices) {
            val p = g.nodes[selected]
            SelectedCard(
                p,
                Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            ) { onOpen(p.id) }
        }
        IconButton(
            onClick = {
                camera.autoFit = true
                wake.trySend(Unit)
            },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 64.dp, end = 12.dp),
        ) {
            Icon(TrattoIcons.Fit, contentDescription = "Inquadra tutto", tint = GraphMuted)
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGraph(g: Graph, cam: Camera, selected: Int, labelPaint: Paint, labelFade: Float) {
    val focus = HashSet<Int>()
    if (selected in g.nodes.indices) {
        focus.add(selected)
        focus.addAll(g.nodes[selected].neighbors)
    }
    val dimmed = focus.isNotEmpty()
    val px = cam.scale * cam.density
    // linee
    val thin = max(0.6f, 1.1f * cam.density * min(1f, cam.scale))
    for (e in 0 until g.edgeCount) {
        val a = g.nodes[g.edges[2 * e]]
        val b = g.nodes[g.edges[2 * e + 1]]
        val ia = g.edges[2 * e]
        val ib = g.edges[2 * e + 1]
        val lit = dimmed && (ia == selected || ib == selected)
        val color = when {
            lit -> g.nodes[selected].color.copy(alpha = 0.85f)
            dimmed -> Color.White.copy(alpha = 0.05f)
            else -> Color.White.copy(alpha = 0.16f)
        }
        drawLine(color, Offset(cam.sx(a.x), cam.sy(a.y)), Offset(cam.sx(b.x), cam.sy(b.y)), strokeWidth = if (lit) thin * 1.8f else thin)
    }
    // pallini con alone
    for ((i, p) in g.nodes.withIndex()) {
        val r = max(1.6f * cam.density, p.radius * px)
        val c = Offset(cam.sx(p.x), cam.sy(p.y))
        if (c.x < -r * 3 || c.y < -r * 3 || c.x > size.width + r * 3 || c.y > size.height + r * 3) continue
        val faded = dimmed && i !in focus
        val a = if (faded) 0.22f else 1f
        drawCircle(p.color.copy(alpha = 0.07f * a), r * 2.6f, c)
        drawCircle(p.color.copy(alpha = 0.16f * a), r * 1.7f, c)
        drawCircle(p.color.copy(alpha = a), r, c)
        if (i == selected) drawCircle(Color.White, r + 3f * cam.density, c, style = Stroke(1.6f * cam.density))
    }
    // nomi: da vicino tutti, da lontano il quaderno scelto, i suoi vicini e i più collegati;
    // mai uno sopra l'altro: se non c'è posto vince il più importante
    val maxDegree = g.nodes.maxOfOrNull { it.degree } ?: 0
    val allLabels = ((cam.scale - 0.7f) / 0.4f).coerceIn(0f, 1f)
    labelPaint.textSize = 12.5f * cam.density
    val placed = ArrayList<android.graphics.RectF>()
    val pad = 4f * cam.density
    val order = if (selected in g.nodes.indices) listOf(selected) + g.nodes[selected].neighbors + g.labelOrder else g.labelOrder
    val done = HashSet<Int>()
    for (i in order) {
        if (!done.add(i)) continue
        val p = g.nodes[i]
        val important = i in focus || (!dimmed && maxDegree >= 3 && p.degree >= max(3, (maxDegree * 0.6f).toInt()))
        val alpha = when {
            i in focus -> 1f
            dimmed -> allLabels * 0.3f * labelFade
            important -> max(allLabels, 0.85f) * labelFade
            else -> allLabels * 0.85f * labelFade
        }
        if (alpha <= 0.02f) continue
        val r = max(1.6f * cam.density, p.radius * px)
        val x = cam.sx(p.x)
        val y = cam.sy(p.y) + r + 15f * cam.density
        if (x < -200 || y < -50 || x > size.width + 200 || y > size.height + 50) continue
        val text = if (p.title.length > 30) p.title.take(29) + "…" else p.title
        labelPaint.isFakeBoldText = i == selected
        val w = labelPaint.measureText(text)
        val box = android.graphics.RectF(x - w / 2 - pad, y - labelPaint.textSize, x + w / 2 + pad, y + pad)
        if (i != selected && placed.any { android.graphics.RectF.intersects(it, box) }) continue
        placed.add(box)
        labelPaint.color = GraphText.copy(alpha = alpha).toArgb()
        drawContext.canvas.nativeCanvas.drawText(text, x - w / 2, y, labelPaint)
    }
}

@Composable
private fun Legend(g: Graph) {
    Surface(color = GraphCard.copy(alpha = 0.85f), shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val items = g.groups.map { it.name to it.color } + if (g.nodes.any { it.group < 0 }) listOf("Fuori dalle cartelle" to LooseColor) else emptyList()
            items.take(10).forEach { (name, color) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.size(8.dp))
                    Text(name, color = GraphText.copy(alpha = 0.85f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
                }
            }
            if (items.size > 10) Text("e altre ${items.size - 10}", color = GraphMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SelectedCard(p: GraphNode, modifier: Modifier, onOpen: () -> Unit) {
    Surface(color = GraphCard, shape = RoundedCornerShape(16.dp), shadowElevation = 6.dp, modifier = modifier.widthIn(max = 360.dp)) {
        Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(p.color))
            Column(Modifier.weight(1f, fill = false).padding(horizontal = 12.dp)) {
                Text(p.title, color = GraphText, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val links = if (p.degree == 1) "1 collegamento" else "${p.degree} collegamenti"
                Text(if (p.place.isEmpty()) links else "${p.place} · $links", color = GraphMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onOpen, colors = ButtonDefaults.buttonColors(containerColor = p.color, contentColor = GraphBackground)) { Text("Apri") }
        }
    }
}
