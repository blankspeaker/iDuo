package com.blankspeaker.iduo.crack

import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Direction of the hinge crease on screen. */
enum class CreaseOrientation { VERTICAL, HORIZONTAL }

/** Hinge posture as reported by Jetpack WindowManager's FoldingFeature. */
enum class Posture { FLAT, HALF_OPENED }

/** A hinge in the overlay's own coordinates. [pos] is px along the axis perpendicular to the crease. */
data class Hinge(val orientation: CreaseOrientation, val pos: Float, val posture: Posture)

object FoldGate {
    /** Smallest side (dp) a screen must have to count as the big inner display. Cover screens are ~400 dp. */
    const val MIN_INNER_DP = 560f

    /**
     * Only draw when the phone is open on its inner display: a hinge must be reported, be FLAT or
     * HALF_OPENED, sit inside the overlay, and the overlay must be tablet-sized. Anything else
     * (folded / cover screen / no FoldingFeature / regular phone) draws nothing.
     */
    fun shouldShow(hinge: Hinge?, widthPx: Int, heightPx: Int, density: Float): Boolean {
        if (hinge == null || widthPx <= 0 || heightPx <= 0 || density <= 0f) return false
        val minDp = min(widthPx, heightPx) / density
        if (minDp < MIN_INNER_DP) return false
        val span = if (hinge.orientation == CreaseOrientation.VERTICAL) widthPx else heightPx
        if (hinge.pos <= 0f || hinge.pos >= span) return false
        return hinge.posture == Posture.FLAT || hinge.posture == Posture.HALF_OPENED
    }
}

/**
 * Everything needed to build the damage. [creasePos] is the crease centre in px along the axis
 * perpendicular to the crease (x for a vertical crease). null = nothing is drawn.
 */
data class CrackSpec(
    val width: Int,
    val height: Int,
    val orientation: CreaseOrientation = CreaseOrientation.VERTICAL,
    val creasePos: Float? = null,
    val severity: Float = 0.5f,
    val deadLine: Boolean = true,
    val seed: Long = 1L,
)

/** BRIGHT = stuck-on OLED line, DEAD = black pixels, PIXEL = individual stuck sub-pixels. */
enum class StrokeKind { BRIGHT, DEAD, PIXEL }

class Stroke(
    val xs: FloatArray,
    val ys: FloatArray,
    val width: Float,
    val color: Int,
    val kind: StrokeKind,
    val flicker: Boolean = false,
)

class CrackScene(val width: Int, val height: Int, val strokes: List<Stroke>) {
    val isEmpty get() = strokes.isEmpty()
}

/** Minimal drawing backend so Android Canvas and java.awt share one painter. */
interface LineSurface {
    fun polyline(xs: FloatArray, ys: FloatArray, width: Float, argb: Int, roundCap: Boolean)
}

fun withAlpha(argb: Int, mul: Float): Int {
    val a = (((argb ushr 24) and 0xFF) * mul.coerceIn(0f, 1f)).roundToInt()
    return (a shl 24) or (argb and 0x00FFFFFF)
}

object CrackPainter {
    fun paint(
        scene: CrackScene,
        surface: LineSurface,
        filter: (Stroke) -> Boolean = { true },
        alphaOf: (Stroke) -> Float = { 1f },
    ) {
        // Dead pixels first, lit pixels on top (a stuck-on row can cross a dead column).
        val order = listOf(StrokeKind.DEAD, StrokeKind.BRIGHT, StrokeKind.PIXEL)
        for (k in order) for (s in scene.strokes) {
            if (s.kind != k || !filter(s)) continue
            val a = alphaOf(s)
            if (a <= 0.01f) continue
            when (k) {
                StrokeKind.DEAD -> surface.polyline(s.xs, s.ys, s.width, withAlpha(s.color, a), false)
                StrokeKind.BRIGHT -> {
                    // Very light bleed: OLED lines are crisp, the glass diffuses them a touch.
                    surface.polyline(s.xs, s.ys, s.width * 3f, withAlpha(s.color, 0.10f * a), false)
                    surface.polyline(s.xs, s.ys, s.width, withAlpha(s.color, a), false)
                }
                StrokeKind.PIXEL -> surface.polyline(s.xs, s.ys, s.width, withAlpha(s.color, a), false)
            }
        }
    }
}

object CrackGenerator {
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val GREEN = 0xFF3DFF5A.toInt()
    private const val MAGENTA = 0xFFFF3DF2.toInt()
    private const val CYAN = 0xFF7DF6FF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private val LINE_COLORS = intArrayOf(WHITE, WHITE, WHITE, GREEN, MAGENTA, GREEN, MAGENTA, CYAN)
    private val SUBPIXELS = intArrayOf(0xFFFF2020.toInt(), 0xFF20FF20.toInt(), 0xFF3050FF.toInt(), WHITE)

    fun generate(spec: CrackSpec): CrackScene {
        val crease = spec.creasePos ?: return CrackScene(spec.width, spec.height, emptyList())
        val w = max(1, spec.width).toFloat()
        val h = max(1, spec.height).toFloat()
        val s = spec.severity.coerceIn(0f, 1f)
        val r = Random(spec.seed)
        // One "unit" is roughly one physical pixel on a ~2100 px inner display, scaled for others.
        val unit = max(1f, min(w, h) / 1000f)
        val out = ArrayList<Stroke>()
        val vertical = spec.orientation == CreaseOrientation.VERTICAL
        val len = if (vertical) h else w          // along the crease
        val across = if (vertical) w else h       // perpendicular
        fun add(us: FloatArray, vs: FloatArray, width: Float, color: Int, kind: StrokeKind, flicker: Boolean = false) {
            out += if (vertical) Stroke(vs, us, width, color, kind, flicker) else Stroke(us, vs, width, color, kind, flicker)
        }
        fun hLine(u: Float, v0: Float, v1: Float, width: Float, color: Int, kind: StrokeKind, flicker: Boolean = false) =
            add(floatArrayOf(u, u), floatArrayOf(v0, v1), width, color, kind, flicker)
        fun vLine(v: Float, u0: Float, u1: Float, width: Float, color: Int, kind: StrokeKind, flicker: Boolean = false) =
            add(floatArrayOf(u0, u1), floatArrayOf(v, v), width, color, kind, flicker)

        // Where the panel "popped": 50-62% down the crease. Lines run toward the larger side (right on a Fold).
        val pu = len * (0.50f + r.nextFloat() * 0.12f)
        val dir = if (across - crease >= crease) 1f else -1f
        val edge = if (dir > 0) across else 0f

        // 1) Dead pixels down the crease from the top to the pop point.
        //    Low severity = sparse dead dots/short runs; high = a solid, ragged dead band.
        if (spec.deadLine) {
            val bandW = unit * (1.2f + 14f * s.pow(1.6f))
            if (s < 0.2f) {
                val dots = 6 + (s * 60).toInt()
                repeat(dots) {
                    val u = r.nextFloat() * pu
                    val l = unit * (1f + r.nextFloat() * 6f) + len * 0.03f * r.nextFloat() * s * 5
                    vLine(crease + (r.nextFloat() - 0.5f) * unit, u, min(pu, u + l), unit * 1.3f, BLACK, StrokeKind.DEAD)
                }
            } else {
                var u = 0f
                while (u < pu) {
                    val seg = len * (0.03f + r.nextFloat() * 0.12f)
                    val end = min(pu, u + seg)
                    val bw = bandW * (0.75f + r.nextFloat() * 0.5f)
                    vLine(crease + dir * bw * 0.15f * (r.nextFloat() - 0.3f), u, end, bw, BLACK, StrokeKind.DEAD)
                    u = end + (if (r.nextFloat() < 0.25f * (1 - s)) len * r.nextFloat() * 0.02f else 0f)
                }
            }
            // Thin green column hugging the dead band (driver line), flickers.
            val gv = crease + dir * (bandW / 2f + unit * (1.5f + r.nextFloat() * 1.5f))
            vLine(gv, pu * r.nextFloat() * 0.5f, pu, unit, GREEN, StrokeKind.BRIGHT, flicker = true)
        }

        // 2) Straight bright rows from the crease to the edge.
        val rows = 1 + (s * 11f + r.nextFloat() * 1.5f).toInt()
        val spread = len * (0.015f + 0.22f * s)
        for (i in 0 until rows) {
            val du = if (i == 0) 0f else r.nextFloat().pow(1.3f) * spread * (if (r.nextFloat() < 0.85f) 1f else -0.3f)
            val u = (pu + du).coerceIn(0f, len)
            val thick = r.nextFloat() < 0.08f + 0.22f * s
            val width = if (thick) unit * (3f + r.nextFloat() * 6f * s) else unit * (1f + r.nextFloat() * (0.6f + 1.4f * s))
            hLine(u, crease, edge, width, LINE_COLORS[r.nextInt(LINE_COLORS.size)], StrokeKind.BRIGHT, flicker = r.nextFloat() < 0.3f)
        }

        // 3) Vertical lines from the pop point down to the bottom, next to the crease.
        val cols = if (s < 0.15f) 0 else 1 + (s * 4f).toInt()
        repeat(cols) {
            val v = crease + dir * unit * (1f + r.nextFloat() * (6f + 40f * s))
            val start = pu + len * 0.04f * r.nextFloat()
            vLine(v, start, len, unit * (1f + r.nextFloat() * s), LINE_COLORS[r.nextInt(LINE_COLORS.size)], StrokeKind.BRIGHT, flicker = r.nextFloat() < 0.3f)
        }

        // 4) Stuck sub-pixels: a cluster around the pop point plus a few strays along the crease.
        val cluster = 3 + (s * 40f).toInt()
        repeat(cluster) {
            val u = pu + (r.nextGaussian() * unit * (4 + 18 * s)).toFloat()
            val v = crease + dir * kotlin.math.abs(r.nextGaussian() * unit * (3 + 14 * s)).toFloat()
            stuck(r, u, v, unit, ::add)
        }
        repeat(2 + (s * 10f).toInt()) {
            stuck(r, r.nextFloat() * len, crease + (r.nextFloat() - 0.5f) * unit * 20f, unit, ::add)
        }
        return CrackScene(spec.width, spec.height, out)
    }

    private fun stuck(
        r: Random, u: Float, v: Float, unit: Float,
        add: (FloatArray, FloatArray, Float, Int, StrokeKind, Boolean) -> Unit,
    ) {
        val size = unit * (1f + r.nextFloat())
        add(floatArrayOf(u - size / 2, u + size / 2), floatArrayOf(v, v), size, SUBPIXELS[r.nextInt(SUBPIXELS.size)], StrokeKind.PIXEL, r.nextFloat() < 0.2f)
    }
}
