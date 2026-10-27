package com.blankspeaker.iduo.crack

import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** Direction of the hinge crease on screen. */
enum class CreaseOrientation { VERTICAL, HORIZONTAL }

/**
 * Everything needed to build a crack.
 * [creasePos] is the crease centre in px along the axis perpendicular to the crease
 * (x for a vertical crease, y for a horizontal one). null = no hinge reported.
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

enum class StrokeKind { GLASS, BRIGHT, DEAD }

class Stroke(
    val xs: FloatArray,
    val ys: FloatArray,
    val width: Float,
    val color: Int,
    val kind: StrokeKind,
    val flicker: Boolean = false,
)

enum class CrackMode { HINGE, HINGE_GUESS, CORNER }

class CrackScene(val width: Int, val height: Int, val mode: CrackMode, val strokes: List<Stroke>)

/** Minimal drawing backend so Android Canvas and java.awt share one painter. */
interface LineSurface {
    fun polyline(xs: FloatArray, ys: FloatArray, width: Float, argb: Int, roundCap: Boolean)
}

fun withAlpha(argb: Int, mul: Float): Int {
    val a = (((argb ushr 24) and 0xFF) * mul.coerceIn(0f, 1f)).roundToInt()
    return (a shl 24) or (argb and 0x00FFFFFF)
}

object CrackPainter {
    /** Paints [scene]. [filter] picks strokes; [alphaOf] lets callers flicker individual strokes. */
    fun paint(
        scene: CrackScene,
        surface: LineSurface,
        filter: (Stroke) -> Boolean = { true },
        alphaOf: (Stroke) -> Float = { 1f },
    ) {
        // Draw order: glass shadows, bright OLED lines, dead pixels, glass cracks on top.
        val order = listOf(StrokeKind.BRIGHT, StrokeKind.DEAD, StrokeKind.GLASS)
        val unit = min(scene.width, scene.height) / 1000f
        for (k in order) for (s in scene.strokes) {
            if (s.kind != k || !filter(s)) continue
            val a = alphaOf(s)
            if (a <= 0.01f) continue
            when (k) {
                StrokeKind.BRIGHT -> {
                    // Soft bloom around a hard pixel row, like a stuck-on OLED line.
                    surface.polyline(s.xs, s.ys, s.width * 5f, withAlpha(s.color, 0.10f * a), false)
                    surface.polyline(s.xs, s.ys, s.width * 2.2f, withAlpha(s.color, 0.30f * a), false)
                    surface.polyline(s.xs, s.ys, s.width, withAlpha(s.color, a), false)
                }
                StrokeKind.DEAD -> surface.polyline(s.xs, s.ys, s.width, withAlpha(s.color, a), false)
                StrokeKind.GLASS -> {
                    surface.polyline(s.xs, s.ys, s.width * 3.5f, withAlpha(0xFF000000.toInt(), 0.10f * a), true)
                    surface.polyline(s.xs, s.ys, s.width, withAlpha(s.color, a), true)
                    // Refraction highlight just beside the crack.
                    val off = max(0.8f, unit * 0.9f)
                    val hx = FloatArray(s.xs.size) { s.xs[it] + off }
                    val hy = FloatArray(s.ys.size) { s.ys[it] + off }
                    surface.polyline(hx, hy, max(0.6f, s.width * 0.55f), withAlpha(0xFFFFFFFF.toInt(), 0.38f * a), true)
                }
            }
        }
    }
}

object CrackGenerator {
    private val BRIGHT_COLORS = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0xFFF2F8FF.toInt(),
        0xFFBFF4FF.toInt(), 0xFFFFC6EF.toInt(), 0xFFD6FFD2.toInt(), 0xFFFFF3C4.toInt(),
    )
    private const val GLASS_DARK = 0xB0101418.toInt()
    private const val DEAD_BLACK = 0xFF000000.toInt()
    private const val DEAD_GREEN = 0xFF39FF6A.toInt()

    fun generate(spec: CrackSpec): CrackScene {
        val w = max(1, spec.width).toFloat()
        val h = max(1, spec.height).toFloat()
        val s = spec.severity.coerceIn(0f, 1f)
        val r = Random(spec.seed)
        val unit = min(w, h) / 1000f
        val out = ArrayList<Stroke>()

        val aspect = w / h
        val (mode, orient, crease) = when {
            spec.creasePos != null -> Triple(CrackMode.HINGE, spec.orientation, spec.creasePos)
            aspect in 0.70f..1.45f -> Triple(CrackMode.HINGE_GUESS, CreaseOrientation.VERTICAL, w / 2f)
            else -> Triple(CrackMode.CORNER, CreaseOrientation.VERTICAL, 0f)
        }
        if (mode == CrackMode.CORNER) {
            corner(out, r, w, h, s, unit, spec.deadLine)
        } else {
            hinge(out, r, w, h, s, unit, orient, crease, spec.deadLine)
        }
        return CrackScene(spec.width, spec.height, mode, out)
    }

    /**
     * iPhone Duo style: dead pixels down the crease to an impact point just below the
     * middle, then perfectly straight bright pixel rows running from the crease to the edge,
     * and (at higher severity) a real glass fracture + spiderweb at the impact point.
     * Built in crease-local coords: u = along crease, v = across.
     */
    private fun hinge(
        out: MutableList<Stroke>, r: Random, w: Float, h: Float, s: Float, unit: Float,
        orient: CreaseOrientation, crease: Float, deadLine: Boolean,
    ) {
        val vertical = orient == CreaseOrientation.VERTICAL
        val len = if (vertical) h else w          // along crease
        val across = if (vertical) w else h       // perpendicular
        fun add(us: FloatArray, vs: FloatArray, width: Float, color: Int, kind: StrokeKind, flicker: Boolean = false) {
            if (vertical) out += Stroke(vs, us, width, color, kind, flicker)
            else out += Stroke(us, vs, width, color, kind, flicker)
        }

        val pu = len * (0.50f + r.nextFloat() * 0.12f)
        // Lines run toward the larger side (the Duo shows them on the right half).
        val dir = if (across - crease >= crease || r.nextFloat() < 0.15f) 1f else -1f
        val edge = if (dir > 0) across else 0f

        // 1) Dead / damaged pixel column following the crease from the top to the impact.
        if (deadLine) {
            var u = 0f
            while (u < pu) {
                val seg = len * (0.04f + r.nextFloat() * 0.18f)
                val end = min(pu, u + seg)
                val v = crease + (r.nextFloat() - 0.5f) * unit * 1.2f
                add(floatArrayOf(u, end), floatArrayOf(v, v), unit * (1.4f + 1.6f * s), DEAD_BLACK, StrokeKind.DEAD)
                u = end + len * r.nextFloat() * 0.015f
            }
            val gv = crease + dir * unit * (2.6f + r.nextFloat() * 2f)
            val gStart = pu * r.nextFloat() * 0.4f
            add(floatArrayOf(gStart, pu), floatArrayOf(gv, gv), max(1f, unit * 0.9f), DEAD_GREEN, StrokeKind.DEAD, flicker = true)
        }

        // 2) Bright stuck-on pixel rows from the crease to the edge.
        val n = 1 + (s * 8f + r.nextFloat() * 1.5f).toInt()
        for (i in 0 until n) {
            val spread = len * (0.02f + 0.20f * s)
            val du = if (i == 0) 0f else r.nextFloat().pow(1.4f) * spread * (if (r.nextFloat() < 0.82f) 1f else -0.35f)
            val u = (pu + du).coerceIn(0f, len)
            val v0 = crease - dir * unit * r.nextFloat() * 3f
            val band = r.nextFloat() < 0.12f + 0.25f * s
            val width = if (band) unit * (4f + r.nextFloat() * 9f * s + 2f) else unit * (0.9f + r.nextFloat() * (1.5f + 3f * s))
            val color = BRIGHT_COLORS[r.nextInt(BRIGHT_COLORS.size)]
            add(floatArrayOf(u, u), floatArrayOf(v0, edge), width, color, StrokeKind.BRIGHT, flicker = r.nextFloat() < 0.35f)
            if (band && r.nextFloat() < 0.7f) {
                // RGB fringe on the thick bands.
                val fu = u + (width / 2f + unit * 0.8f) * (if (r.nextBoolean()) 1 else -1)
                add(floatArrayOf(fu, fu), floatArrayOf(v0, edge), unit * 0.9f, BRIGHT_COLORS[3 + r.nextInt(4)], StrokeKind.BRIGHT)
            }
        }

        // 3) Bright column continuing down the crease to the bottom.
        if (s > 0.25f) {
            val v = crease + dir * unit * (1f + r.nextFloat() * 2f)
            add(floatArrayOf(pu, len), floatArrayOf(v, v), unit * (1.2f + 2f * s), 0xFFF6FBFF.toInt(), StrokeKind.BRIGHT, flicker = r.nextFloat() < 0.5f)
            if (s > 0.55f) {
                val v2 = v + dir * unit * 3f
                add(floatArrayOf(pu + len * 0.05f * r.nextFloat(), len), floatArrayOf(v2, v2), unit, BRIGHT_COLORS[3 + r.nextInt(4)], StrokeKind.BRIGHT)
            }
        }

        // 4) Physical glass fracture along the crease + branches.
        if (s > 0.35f) {
            val top = max(0f, pu - len * (0.04f + 0.30f * s))
            val bot = min(len, pu + len * (0.04f + 0.34f * s))
            val step = len / 70f
            val us = ArrayList<Float>(); val vs = ArrayList<Float>()
            var u = top; var v = crease
            val wander = unit * (3f + 10f * s)
            while (u <= bot) {
                us += u; vs += v
                u += step * (0.5f + r.nextFloat())
                v = (v + (r.nextFloat() - 0.5f) * wander).coerceIn(crease - wander * 1.5f, crease + wander * 1.5f)
            }
            add(us.toFloatArray(), vs.toFloatArray(), unit * (1.2f + s), GLASS_DARK, StrokeKind.GLASS)
            val branches = (s * 12f).toInt()
            repeat(branches) {
                val idx = r.nextInt(us.size)
                val side = if (r.nextBoolean()) 1.0 else -1.0
                val ang = (if (r.nextBoolean()) 0.0 else PI) + side * (0.35 + r.nextDouble() * 0.9)
                // angle measured in (u, v) space: 0 = along crease
                val length = unit * (40f + r.nextFloat() * (120f + 260f * s))
                branch(r, us[idx], vs[idx], ang, length, unit, 2) { bu, bv, bw ->
                    add(bu, bv, bw, GLASS_DARK, StrokeKind.GLASS)
                }
            }
        }

        // 5) Spiderweb at the impact point.
        if (s > 0.6f) {
            web(r, pu, crease, unit, s) { bu, bv, bw -> add(bu, bv, bw, GLASS_DARK, StrokeKind.GLASS) }
        }
    }

    /** Non-foldable / outer screen: a dropped-phone corner spiderweb plus a couple of OLED lines. */
    private fun corner(out: MutableList<Stroke>, r: Random, w: Float, h: Float, s: Float, unit: Float, deadLine: Boolean) {
        val cx = w * (0.78f + r.nextFloat() * 0.12f)
        val cy = h * (0.84f + r.nextFloat() * 0.08f)
        val sink: (FloatArray, FloatArray, Float) -> Unit = { a, b, bw -> out += Stroke(a, b, bw, GLASS_DARK, StrokeKind.GLASS) }
        web(r, cx, cy, unit, max(0.45f, s), sink)
        val rays = 3 + (s * 8f).toInt()
        repeat(rays) {
            val ang = PI + (r.nextDouble() - 0.3) * PI * 0.9
            branch(r, cx, cy, ang, max(w, h) * (0.15f + 0.5f * s * r.nextFloat()), unit, 2, sink)
        }
        val n = (s * 4f).toInt()
        repeat(n) {
            val y = cy - h * 0.15f * r.nextFloat()
            out += Stroke(floatArrayOf(0f, cx), floatArrayOf(y, y), unit * (1f + 3f * r.nextFloat()), BRIGHT_COLORS[r.nextInt(BRIGHT_COLORS.size)], StrokeKind.BRIGHT, r.nextFloat() < 0.4f)
        }
        if (deadLine) {
            val x = cx - unit * 4f
            out += Stroke(floatArrayOf(x, x), floatArrayOf(0f, h), unit * 1.2f, DEAD_GREEN, StrokeKind.DEAD, true)
        }
    }

    private fun web(r: Random, a0: Float, b0: Float, unit: Float, s: Float, sink: (FloatArray, FloatArray, Float) -> Unit) {
        val k = 8 + (s * 10f).toInt()
        val radii = FloatArray(k) { unit * (50f + r.nextFloat() * (120f + 260f * s)) }
        val angles = DoubleArray(k) { (it + r.nextDouble() * 0.6) * 2 * PI / k }
        val rings = 2 + (s * 3f).toInt()
        val ringPts = Array(rings) { FloatArray(k * 2) }
        for (i in 0 until k) {
            val pa = ArrayList<Float>(); val pb = ArrayList<Float>()
            var ang = angles[i]
            val steps = 10
            var ca = a0; var cb = b0
            pa += ca; pb += cb
            for (j in 1..steps) {
                ang += (r.nextDouble() - 0.5) * 0.25
                val stepLen = radii[i] / steps
                ca += (cos(ang) * stepLen).toFloat(); cb += (sin(ang) * stepLen).toFloat()
                pa += ca; pb += cb
            }
            sink(pa.toFloatArray(), pb.toFloatArray(), unit * (0.9f + 0.8f * r.nextFloat()))
            for (ring in 0 until rings) {
                val f = (ring + 1f) / (rings + 1f) * (0.85f + r.nextFloat() * 0.3f)
                val idx = (f * steps).roundToInt().coerceIn(1, steps)
                ringPts[ring][i * 2] = pa[idx]; ringPts[ring][i * 2 + 1] = pb[idx]
            }
        }
        // Concentric fractures between neighbouring rays (with gaps, like real glass).
        for (ring in 0 until rings) for (i in 0 until k) {
            if (r.nextFloat() < 0.25f) continue
            val j = (i + 1) % k
            val p = ringPts[ring]
            val ma = (p[i * 2] + p[j * 2]) / 2f + (r.nextFloat() - 0.5f) * unit * 10f
            val mb = (p[i * 2 + 1] + p[j * 2 + 1]) / 2f + (r.nextFloat() - 0.5f) * unit * 10f
            sink(floatArrayOf(p[i * 2], ma, p[j * 2]), floatArrayOf(p[i * 2 + 1], mb, p[j * 2 + 1]), unit * 0.8f)
        }
        // Tiny chips at the centre.
        repeat(6) {
            val ang = r.nextDouble() * 2 * PI
            val l = unit * (6f + r.nextFloat() * 14f)
            sink(floatArrayOf(a0, a0 + (cos(ang) * l).toFloat()), floatArrayOf(b0, b0 + (sin(ang) * l).toFloat()), unit * 1.6f)
        }
    }

    private fun branch(
        r: Random, a0: Float, b0: Float, angle0: Double, length: Float, unit: Float, depth: Int,
        sink: (FloatArray, FloatArray, Float) -> Unit,
    ) {
        val steps = max(3, (length / (unit * 14f)).toInt())
        val stepLen = length / steps
        val pa = FloatArray(steps + 1); val pb = FloatArray(steps + 1)
        pa[0] = a0; pb[0] = b0
        var ang = angle0
        for (i in 1..steps) {
            ang += (r.nextDouble() - 0.5) * 0.6
            pa[i] = pa[i - 1] + (cos(ang) * stepLen).toFloat()
            pb[i] = pb[i - 1] + (sin(ang) * stepLen).toFloat()
            if (depth > 0 && r.nextFloat() < 0.10f) {
                branch(r, pa[i], pb[i], ang + (if (r.nextBoolean()) 1 else -1) * (0.4 + r.nextDouble() * 0.6),
                    length * (0.25f + r.nextFloat() * 0.35f), unit, depth - 1, sink)
            }
        }
        sink(pa, pb, unit * (0.6f + 0.6f * depth / 2f + 0.3f * r.nextFloat()))
    }
}
