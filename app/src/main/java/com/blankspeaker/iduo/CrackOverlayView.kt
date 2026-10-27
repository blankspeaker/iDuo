package com.blankspeaker.iduo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import com.blankspeaker.iduo.crack.CrackGenerator
import com.blankspeaker.iduo.crack.CrackPainter
import com.blankspeaker.iduo.crack.CrackScene
import com.blankspeaker.iduo.crack.CrackSpec
import com.blankspeaker.iduo.crack.CreaseOrientation
import com.blankspeaker.iduo.crack.FoldGate
import com.blankspeaker.iduo.crack.Hinge
import com.blankspeaker.iduo.crack.Posture
import com.blankspeaker.iduo.crack.LineSurface
import com.blankspeaker.iduo.crack.Stroke
import java.util.IdentityHashMap
import kotlin.random.Random

/** Hinge position in screen coordinates (centre of the FoldingFeature bounds). */
data class ScreenHinge(val orientation: CreaseOrientation, val screenPos: Float, val posture: Posture)

class CanvasSurface : LineSurface {
    var canvas: Canvas? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun polyline(xs: FloatArray, ys: FloatArray, width: Float, argb: Int, roundCap: Boolean) {
        val c = canvas ?: return
        if (xs.size < 2) return
        path.rewind()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until xs.size) path.lineTo(xs[i], ys[i])
        paint.color = argb
        paint.strokeWidth = width
        paint.strokeCap = if (roundCap) Paint.Cap.ROUND else Paint.Cap.BUTT
        c.drawPath(path, paint)
    }
}

/** Full-screen, non-interactive view that draws the fake pixel damage, only while unfolded. */
@SuppressLint("ViewConstructor")
class CrackOverlayView(context: Context) : View(context) {
    private var settings = CrackSettings()
    private var hinge: ScreenHinge? = null
    private var scene: CrackScene? = null
    private var cache: Bitmap? = null
    private val surface = CanvasSurface()
    private val flickerAlpha = IdentityHashMap<Stroke, Float>()
    private val loc = IntArray(2)

    /** Human readable description of where the crack went, for the status line. */
    var placement: String = "waiting for hinge"
        private set

    /** True while damage is actually being drawn (phone open on the inner display). */
    var showing: Boolean = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            val sc = scene ?: return
            if (!settings.flicker || !showing) return
            for (s in sc.strokes) if (s.flicker) {
                flickerAlpha[s] = if (Random.nextFloat() < 0.15f) 0f else 0.55f + 0.45f * Random.nextFloat()
            }
            invalidate()
            postDelayed(this, 40L + Random.nextLong(360L))
        }
    }

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun apply(s: CrackSettings) {
        settings = s
        rebuild()
    }

    fun setHinge(h: ScreenHinge?) {
        if (h == hinge) return
        hinge = h
        rebuild()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuild()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartFlicker()
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        cache?.recycle()
        cache = null
        super.onDetachedFromWindow()
    }

    private fun restartFlicker() {
        removeCallbacks(tick)
        if (settings.flicker && showing && isAttachedToWindow) post(tick)
    }

    private fun rebuild() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        getLocationOnScreen(loc)
        val hg = hinge
        val local = hg?.let {
            val p = if (it.orientation == CreaseOrientation.VERTICAL) it.screenPos - loc[0] else it.screenPos - loc[1]
            Hinge(it.orientation, p, it.posture)
        }
        showing = FoldGate.shouldShow(local, w, h, resources.displayMetrics.density)
        placement = when {
            showing -> "showing: hinge ${local!!.orientation.name.lowercase()} at ${local.pos.toInt()}px, ${local.posture.name.lowercase()} (${w}x$h)"
            local == null -> "hidden: no hinge reported (folded / cover screen / not a foldable)"
            else -> "hidden: not on the open inner display (${w}x$h, ${local.posture.name.lowercase()})"
        }
        if (!showing) {
            scene = null
            removeCallbacks(tick)
            cache?.recycle()
            cache = null
            invalidate()
            return
        }
        val spec = CrackSpec(
            width = w, height = h,
            orientation = local!!.orientation,
            creasePos = local.pos,
            severity = settings.severity / 100f,
            deadLine = settings.deadLine,
            seed = settings.seed,
        )
        val sc = CrackGenerator.generate(spec)
        scene = sc

        var bmp = cache
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cache = bmp
        } else {
            bmp.eraseColor(0)
        }
        surface.canvas = Canvas(bmp)
        val flicker = settings.flicker
        CrackPainter.paint(sc, surface, filter = { !(flicker && it.flicker) })
        surface.canvas = null
        flickerAlpha.clear()
        restartFlicker()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!showing) return
        cache?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        val sc = scene ?: return
        if (settings.flicker) {
            surface.canvas = canvas
            CrackPainter.paint(sc, surface, filter = { it.flicker }, alphaOf = { flickerAlpha[it] ?: 1f })
            surface.canvas = null
        }
    }
}
