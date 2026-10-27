package com.blankspeaker.iduo.crack

import java.util.Random
import kotlin.math.max
import kotlin.math.min
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

    /** Draw only when open on the inner display: FLAT/HALF_OPENED hinge inside a tablet-sized overlay. */
    fun shouldShow(hinge: Hinge?, widthPx: Int, heightPx: Int, density: Float): Boolean {
        if (hinge == null || widthPx <= 0 || heightPx <= 0 || density <= 0f) return false
        if (min(widthPx, heightPx) / density < MIN_INNER_DP) return false
        val span = if (hinge.orientation == CreaseOrientation.VERTICAL) widthPx else heightPx
        if (hinge.pos <= 0f || hinge.pos >= span) return false
        return hinge.posture == Posture.FLAT || hinge.posture == Posture.HALF_OPENED
    }
}

data class CrackSpec(
    val width: Int,
    val height: Int,
    val orientation: CreaseOrientation = CreaseOrientation.VERTICAL,
    val creasePos: Float? = null,
    val severity: Float = 0.5f,
    val deadLine: Boolean = true,
    val seed: Long = 1L,
)

/** DEAD = crease dead line, BAND = stuck pixel rows, LINE = lit column below the band, HALO = faint bleed. */
enum class Part { DEAD, BAND, LINE, HALO }

/** One axis-aligned block of device pixels: [left, right) x [top, bottom). */
class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int, val color: Int, val part: Part, val flicker: Boolean = false) {
    val width get() = right - left
    val height get() = bottom - top
}

class CrackScene(val width: Int, val height: Int, val rects: List<PixelRect>) {
    val isEmpty get() = rects.isEmpty()
}

/** Drawing backend: fill whole device pixels, no anti-aliasing. */
interface PixelSurface {
    fun fill(left: Int, top: Int, right: Int, bottom: Int, argb: Int)
}

fun withAlpha(argb: Int, mul: Float): Int {
    val a = (((argb ushr 24) and 0xFF) * mul.coerceIn(0f, 1f)).roundToInt()
    return (a shl 24) or (argb and 0x00FFFFFF)
}

object CrackPainter {
    fun paint(
        scene: CrackScene,
        surface: PixelSurface,
        filter: (PixelRect) -> Boolean = { true },
        alphaOf: (PixelRect) -> Float = { 1f },
    ) {
        for (r in scene.rects) {
            if (!filter(r)) continue
            val a = alphaOf(r)
            if (a <= 0.01f) continue
            surface.fill(r.left, r.top, r.right, r.bottom, withAlpha(r.color, a))
        }
    }
}

/**
 * Matches photos of failed iPhone Duo inner screens (Ars Technica / BBC / iFixit, Oct 2026):
 *  - a thin black dead line exactly on the crease, from the top edge down to the failure point (~55-62%),
 *    with a small dark knot right where it fails;
 *  - from that point, ONE horizontal band of adjacent stuck pixel rows (white / cyan / mint / green /
 *    pink / magenta stripes, or a solid cyan block) running from the crease to the far edge;
 *  - a single lit yellow-green / mint column hugging the crease from the band down to the bottom edge.
 * Everything is integer device pixels. Severity scales band thickness, length and stripe density.
 */
object CrackGenerator {
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val CYAN = 0xFF8FF6FF.toInt()
    private const val MINT = 0xFFC4FFD2.toInt()
    private const val GREEN = 0xFF74FF86.toInt()
    private const val PINK = 0xFFFFA6EC.toInt()
    private const val MAGENTA = 0xFFEE66FF.toInt()
    private const val LIME = 0xFFEEFF86.toInt()
    // Photos show the band ordered top -> bottom: pink/magenta fringe, white/cyan core, green/lime fringe.
    private val TOP = intArrayOf(PINK, MAGENTA, PINK, WHITE)
    private val CORE = intArrayOf(WHITE, WHITE, CYAN, CYAN, MINT)
    private val BOTTOM = intArrayOf(GREEN, LIME, MINT, GREEN)
    private val COLUMN = intArrayOf(LIME, LIME, 0xFFF6FF9E.toInt(), 0xFFDFFFE8.toInt())

    fun generate(spec: CrackSpec): CrackScene {
        val creaseF = spec.creasePos ?: return CrackScene(spec.width, spec.height, emptyList())
        val s = spec.severity.coerceIn(0f, 1f)
        val r = Random(spec.seed)
        val vertical = spec.orientation == CreaseOrientation.VERTICAL
        val len = if (vertical) spec.height else spec.width       // along the crease
        val across = if (vertical) spec.width else spec.height    // perpendicular
        if (len <= 0 || across <= 0) return CrackScene(spec.width, spec.height, emptyList())
        val c = creaseF.roundToInt().coerceIn(1, across - 1)
        val out = ArrayList<PixelRect>()
        // Local rect: a = along crease [a0,a1), b = across [b0,b1).
        fun add(a0: Int, a1: Int, b0: Int, b1: Int, color: Int, part: Part, flicker: Boolean = false) {
            val aa0 = a0.coerceIn(0, len); val aa1 = a1.coerceIn(0, len)
            val bb0 = min(b0, b1).coerceIn(0, across); val bb1 = max(b0, b1).coerceIn(0, across)
            if (aa1 <= aa0 || bb1 <= bb0) return
            out += if (vertical) PixelRect(bb0, aa0, bb1, aa1, color, part, flicker)
            else PixelRect(aa0, bb0, aa1, bb1, color, part, flicker)
        }

        val dir = if (across - c >= c) 1 else -1
        val farEdge = if (dir > 0) across else 0
        val pop = (len * (0.55f + r.nextFloat() * 0.07f)).roundToInt()
        val deadW = 3 + (s * 1.5f).roundToInt()                   // 3..4 px
        val deadL = c - deadW / 2
        val deadR = deadL + deadW
        val bandT = max(8, (len * (0.012f + 0.040f * s)).roundToInt())  // ~26..112 px on a 2152 px panel
        // Real failures run all the way to the edge; only the faintest setting stops short.
        val bandEnd = dir * (((farEdge - c) * dir) * min(1f, 0.55f + 3f * s)).roundToInt() + c

        // 1) Dead line on the crease, top edge -> failure point, plus the dark knot.
        if (spec.deadLine) {
            add(0, pop, deadL, deadR, BLACK, Part.DEAD)
            val knotH = 6 + r.nextInt(10 + (s * 20).toInt())
            val knotW = deadW + 2 + r.nextInt(4 + (s * 6).toInt())
            add(pop - knotH, pop + 2, c - knotW / 2, c - knotW / 2 + knotW, BLACK, Part.DEAD)
        }

        // 2) The band: adjacent rows, crease -> far edge. Low severity = sparse thin stripes,
        //    high severity = dense stripes that merge into solid blocks.
        val bandStart = if (dir > 0) deadR else deadL
        val solidBlock = s > 0.8f || (s > 0.6f && r.nextFloat() < 0.5f)
        var a = pop
        val bandBottom = pop + bandT
        val coreTop = pop + (bandT * 0.08f).roundToInt()
        val coreBottom = pop + (bandT * 0.86f).roundToInt()
        if (solidBlock) {
            // Ars Technica photo: one solid cyan block with thin pale lines near its lower edge.
            add(coreTop, coreBottom, bandStart, bandEnd, CYAN, Part.BAND, flicker = true)
            add(coreBottom - 3, coreBottom - 2, bandStart, bandEnd, WHITE, Part.BAND)
            add(coreTop, coreTop + 1, bandStart, bandEnd, WHITE, Part.BAND)
        }
        while (a < bandBottom) {
            if (solidBlock && a >= coreTop && a < coreBottom) { a = coreBottom; continue }
            val t = (a - pop).toFloat() / bandT
            val h = 1 + r.nextInt(if (s < 0.4f) 2 else 3)
            val lit = r.nextFloat() < 0.62f + 0.36f * s
            if (lit) {
                val color = when {
                    t < 0.18f -> TOP[r.nextInt(TOP.size)]
                    t > 0.78f -> BOTTOM[r.nextInt(BOTTOM.size)]
                    else -> CORE[r.nextInt(CORE.size)]
                }
                add(a, a + h, bandStart, bandEnd, color, Part.BAND, flicker = r.nextFloat() < 0.25f)
            }
            a += h
        }
        // Faint 1-2 px bleed just above/below the band.
        add(pop - 2, pop, bandStart, bandEnd, withAlpha(WHITE, 0.30f), Part.HALO)
        add(bandBottom, bandBottom + 2, bandStart, bandEnd, withAlpha(WHITE, 0.25f), Part.HALO)

        // 3) Lit column hugging the crease, from the band to the bottom edge.
        val lineW = 4 + (s * 2f).roundToInt()                     // 4..6 px
        val lineEnd = bandBottom + ((len - bandBottom) * min(1f, 0.6f + 3f * s)).roundToInt()
        val lb0 = if (dir > 0) deadR else deadL - lineW
        add(bandBottom, lineEnd, lb0, lb0 + lineW, COLUMN[r.nextInt(COLUMN.size)], Part.LINE, flicker = r.nextFloat() < 0.4f)

        return CrackScene(spec.width, spec.height, out)
    }
}
