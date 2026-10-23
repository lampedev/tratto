package com.caro.tratto.ink

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.behavior.BinaryOpNode
import androidx.ink.brush.behavior.DampingNode
import androidx.ink.brush.behavior.EasingFunction
import androidx.ink.brush.behavior.ProgressDomain
import androidx.ink.brush.behavior.ResponseNode
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.brush.behavior.ToolTypeFilterNode
import androidx.ink.brush.behavior.ValueNode
import com.caro.tratto.model.ToolKind
import com.caro.tratto.model.ToolSettings
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Penne dell'app costruite con il motore di Ink. Unità: punti PDF.
 *
 * Rispetto alla pressurePen stock (pressione attiva solo tra 0.8 e 1.0, spessore che cambia
 * con la direzione) qui la pressione agisce su tutta la corsa, con una curva morbida e uno
 * smorzamento nel tempo: lo spessore non "trema" e il tratto risulta liscio come in Samsung Notes.
 */
object Brushes {

    // ---------------- scala spessore 1..100 ----------------

    /** Spessore in pt ai livelli 1 e 100 (scala esponenziale: fine sui livelli bassi). */
    private fun sizeRange(kind: ToolKind): Pair<Float, Float> = when (kind) {
        ToolKind.HIGHLIGHTER -> 2f to 60f
        ToolKind.ERASER -> 2f to 100f
        else -> 0.12f to 40f   // livello 1 ≈ 0.12 pt: sottile anche al 600%
    }

    fun sizeForLevel(kind: ToolKind, level: Int): Float {
        val (a, b) = sizeRange(kind)
        return a * (b / a).pow((level.coerceIn(1, 100) - 1) / 99f)
    }

    /**
     * Spessore proporzionale allo zoom (tabella di Caro, riferita a una penna al livello 46):
     * zoom% → livello. Fra i punti si interpola sul logaritmo dello zoom.
     */
    private val zoomTable = listOf(75f to 55f, 100f to 46f, 150f to 40f, 200f to 35f, 300f to 25f, 450f to 15f, 750f to 10f, 1000f to 5f)

    /** Livello effettivo allo [zoomPercent] per chi ha scelto [baseLevel] (= livello al 100%). */
    fun levelForZoom(baseLevel: Int, zoomPercent: Int): Int {
        val z = zoomPercent.toFloat().coerceIn(zoomTable.first().first, zoomTable.last().first)
        var level = zoomTable.last().second
        for (i in 0 until zoomTable.size - 1) {
            val (z0, l0) = zoomTable[i]
            val (z1, l1) = zoomTable[i + 1]
            if (z <= z1) {
                val t = (ln(z) - ln(z0)) / (ln(z1) - ln(z0))
                level = l0 + (l1 - l0) * t
                break
            }
        }
        return (level + (baseLevel - 46)).roundToInt().coerceIn(1, 100)
    }

    fun levelForSize(kind: ToolKind, sizePt: Float): Int {
        val (a, b) = sizeRange(kind)
        return (1 + 99 * ln(sizePt.coerceIn(a, b) / a) / ln(b / a)).roundToInt().coerceIn(1, 100)
    }

    // ---------------- famiglie di pennelli ----------------

    private fun pressure(): ValueNode =
        ToolTypeFilterNode(setOf(InputToolType.STYLUS), SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f))

    /** Pressione → spessore, con curva e smorzamento (secondi) per un tratto senza scalini. */
    private fun pressureToSize(min: Float, max: Float, curve: EasingFunction, damping: Float) = BrushBehavior(
        TargetNode(
            TargetNode.Target.SIZE_MULTIPLIER, min, max,
            DampingNode(ProgressDomain.TIME_IN_SECONDS, damping, ResponseNode(curve, pressure())),
        ),
    )

    /** Attacco del tratto leggermente affusolato (prime 1.5 volte lo spessore). */
    private fun startTaper(from: Float) = BrushBehavior(
        TargetNode(
            TargetNode.Target.SIZE_MULTIPLIER, from, 1f,
            SourceNode(SourceNode.Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, 1.5f),
        ),
    )

    /** Come le penne stock: la parte "predetta" del tratto sfuma, così l'anticipo non si nota. */
    private fun predictionFadeOut() = BrushBehavior(
        TargetNode(
            TargetNode.Target.OPACITY_MULTIPLIER, 1f, 0.3f,
            BinaryOpNode(
                BinaryOpNode.BinaryOp.PRODUCT,
                SourceNode(SourceNode.Source.PREDICTED_TIME_ELAPSED_IN_SECONDS, 0f, 0.024f),
                ResponseNode(
                    EasingFunction.Predefined.EASE_IN_OUT,
                    SourceNode(SourceNode.Source.PREDICTED_DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, 1.5f, 2f),
                ),
            ),
        ),
    )

    /** Intervallo pressione→spessore adattato alla sensibilità scelta (0 bassa, 1 media, 2 alta). */
    private fun range(min: Float, max: Float, sensitivity: Int): Pair<Float, Float> = when (sensitivity) {
        0 -> (1f - (1f - min) * 0.5f) to (1f + (max - 1f) * 0.5f)
        2 -> (1f - (1f - min) * 1.6f).coerceAtLeast(0.08f) to (1f + (max - 1f) * 1.6f)
        else -> min to max
    }

    /** Stabilizzazione: 0 nessuna, 1 normale (default Ink), 2 morbida, 3 massima. */
    private fun inputModel(stabilization: Int): BrushFamily.InputModel = when (stabilization) {
        0 -> BrushFamily.InputModel.PASSTHROUGH_MODEL
        2 -> BrushFamily.InputModel.SlidingWindowModel(40L, 180)
        3 -> BrushFamily.InputModel.SlidingWindowModel(70L, 180)
        else -> BrushFamily.InputModel.DEFAULT_INPUT_MODEL
    }

    private fun pen(
        variant: Int,
        min: Float,
        max: Float,
        curve: EasingFunction,
        damping: Float,
        tip: (List<BrushBehavior>) -> BrushTip = { BrushTip(behaviors = it) },
        paint: BrushPaint = BrushPaint(selfOverlap = SelfOverlap.DISCARD),
        extra: List<BrushBehavior> = emptyList(),
    ): BrushFamily {
        val (a, b) = range(min, max, PenFeel.pressure(variant))
        return BrushFamily(
            tip = tip(listOf(predictionFadeOut(), pressureToSize(a, b, curve, damping)) + extra),
            paint = paint,
            inputModel = inputModel(PenFeel.stabilization(variant)),
        )
    }

    /** Stilografica: molto sensibile alla pressione, attacco affusolato. */
    private fun fountain(v: Int) = pen(v, 0.35f, 1.25f, EasingFunction.Predefined.EASE_OUT, 0.018f, extra = listOf(startTaper(0.55f)))

    /** Penna a sfera: spessore quasi costante, la pressione si sente appena. */
    private fun ballpoint(v: Int) = pen(v, 0.82f, 1.08f, EasingFunction.Predefined.EASE_OUT, 0.025f)

    /** Matita: tratto fine e leggermente trasparente (opacità fissa: identica nel PDF). */
    private fun pencil(v: Int) = pen(
        v, 0.6f, 1.1f, EasingFunction.Predefined.LINEAR, 0.02f,
        paint = BrushPaint(
            colorFunctions = listOf(BrushPaint.ColorFunction.OpacityMultiplier(PENCIL_OPACITY)),
            selfOverlap = SelfOverlap.DISCARD,
        ),
    )

    /** Calligrafica: pennino piatto a 45°, pieni e filini in base alla direzione. */
    private fun calligraphy(v: Int) = pen(
        v, 0.7f, 1.12f, EasingFunction.Predefined.EASE_OUT, 0.02f,
        tip = { BrushTip(scaleX = 1f, scaleY = 0.22f, cornerRounding = 0.4f, rotationDegrees = 45f, behaviors = it) },
    )

    private val families = java.util.concurrent.ConcurrentHashMap<Long, BrushFamily>()

    private fun family(tool: ToolKind, variant: Int): BrushFamily = when (tool) {
        ToolKind.MARKER, ToolKind.ERASER, ToolKind.LASSO -> marker
        ToolKind.HIGHLIGHTER -> highlighter
        else -> families.getOrPut(tool.ordinal.toLong() shl 32 or variant.toLong()) {
            when (tool) {
                ToolKind.PEN -> fountain(variant)
                ToolKind.BALLPOINT -> ballpoint(variant)
                ToolKind.PENCIL -> pencil(variant)
                else -> calligraphy(variant)
            }
        }
    }

    private val marker: BrushFamily by lazy { StockBrushes.marker() }

    // DISCARD: dove il tratto si sovrappone a se stesso non si scurisce (come Samsung Notes, e identico al PDF).
    private val highlighter: BrushFamily by lazy { StockBrushes.highlighter(SelfOverlap.DISCARD) }

    fun create(tool: ToolKind, color: Int, sizePt: Float, variant: Int = PenFeel.DEFAULT): Brush {
        val family = family(tool, variant)
        val size = sizePt.coerceAtLeast(0.05f)
        // Precisione della geometria (pt). La punta tonda è un poligono con scarto ≤ epsilon:
        // con epsilon = 1.2% dello spessore il cerchio ha ~16+ lati e resta tondo anche al 600%
        // (con 0.05 pt fisso le penne a spessore 20–30 diventavano esagoni).
        val epsilon = (size * 0.012f).coerceIn(0.002f, 0.03f)
        return Brush.createWithColorIntArgb(family, color, size, epsilon)
    }

    /** Opacità aggiuntiva della famiglia, da replicare nell'export vettoriale. */
    fun opacityMultiplier(tool: ToolKind): Float = if (tool == ToolKind.PENCIL) PENCIL_OPACITY else 1f

    fun defaultSettings(tool: ToolKind): ToolSettings = when (tool) {
        ToolKind.PEN -> ToolSettings(tool, 0xFF111111.toInt(), sizeForLevel(tool, 45))
        ToolKind.BALLPOINT -> ToolSettings(tool, 0xFF1F3A5F.toInt(), sizeForLevel(tool, 40))
        ToolKind.PENCIL -> ToolSettings(tool, 0xFF3C3C3C.toInt(), sizeForLevel(tool, 38))
        ToolKind.CALLIGRAPHY -> ToolSettings(tool, 0xFF111111.toInt(), sizeForLevel(tool, 58))
        ToolKind.MARKER -> ToolSettings(tool, 0xFFD32F2F.toInt(), sizeForLevel(tool, 50))
        ToolKind.HIGHLIGHTER -> ToolSettings(tool, 0x66FFE14D, sizeForLevel(tool, 50))
        ToolKind.ERASER -> ToolSettings(tool, 0xFF000000.toInt(), sizeForLevel(tool, 40))
        ToolKind.LASSO -> ToolSettings(tool, 0xFF1F3A5F.toInt(), 1f)
    }

    private const val PENCIL_OPACITY = 0.82f
}

/**
 * Sensazione della penna, salvata con ogni tratto come intero "variant".
 * 0 = default (pressione media, stabilizzazione normale): così i tratti salvati prima
 * di questa opzione si ricostruiscono identici.
 */
object PenFeel {
    const val DEFAULT = 0

    fun encode(pressure: Int, stabilization: Int): Int =
        if (pressure == 1 && stabilization == 1) DEFAULT else 1 + pressure.coerceIn(0, 2) * 4 + stabilization.coerceIn(0, 3)

    fun pressure(variant: Int): Int = if (variant <= 0) 1 else ((variant - 1) / 4).coerceIn(0, 2)

    fun stabilization(variant: Int): Int = if (variant <= 0) 1 else ((variant - 1) % 4).coerceIn(0, 3)
}
