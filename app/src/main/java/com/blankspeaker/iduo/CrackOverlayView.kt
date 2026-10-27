package com.blankspeaker.iduo

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.View
import com.blankspeaker.iduo.crack.CrackGenerator
import com.blankspeaker.iduo.crack.CrackPainter
import com.blankspeaker.iduo.crack.CrackScene
import com.blankspeaker.iduo.crack.CrackSpec
import com.blankspeaker.iduo.crack.CreaseOrientation
import com.blankspeaker.iduo.crack.FoldGate
import com.blankspeaker.iduo.crack.Hinge
import com.blankspeaker.iduo.crack.PanelTransform
import com.blankspeaker.iduo.crack.Posture
import com.blankspeaker.iduo.crack.PixelRect
import com.blankspeaker.iduo.crack.PixelSurface
import java.util.IdentityHashMap
import kotlin.random.Random

/** Hinge in the inner panel's natural (ROTATION_0) coordinates, plus that panel's natural size. */
data class PanelHinge(val orientation: CreaseOrientation, val pos: Float, val posture: Posture, val w0: Int, val h0: Int)

class CanvasSurface : PixelSurface {
    var canvas: Canvas? = null
    // No anti-aliasing: every rect lands on whole device pixels.
    private val paint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = false; isDither = false }

    override fun fill(left: Int, top: Int, right: Int, bottom: Int, argb: Int) {
        val c = canvas ?: return
        paint.color = argb
        c.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
    }
}

/**
 * Full-screen, non-interactive view that draws the fake pixel damage, only while unfolded.
 * The damage is generated once in natural panel coordinates and re-mapped for each rotation, so it stays on
 * the same physical pixels like a real panel defect.
 */
@SuppressLint("ViewConstructor")
class CrackOverlayView(context: Context) : View(context) {
    private var settings = CrackSettings()
    private var panel: PanelHinge? = null
    private var naturalScene: CrackScene? = null
    private var sceneKey: List<Any>? = null
    private var scene: CrackScene? = null          // current-screen copy of naturalScene
    private var mappedRotation = -1
    private var cache: Bitmap? = null
    private val surface = CanvasSurface()
    private val flickerAlpha = IdentityHashMap<PixelRect, Float>()
    private val loc = IntArray(2)

    var placement: String = "waiting for hinge"
        private set

    /** True while damage is actually being drawn (phone open on the inner display). */
    var showing: Boolean = false
        private set

    private val tick = object : Runnable {
        override fun run() {
            val sc = scene ?: return
            if (!settings.flicker || !showing) return
            for (r in sc.rects) if (r.flicker) {
                flickerAlpha[r] = if (Random.nextFloat() < 0.06f) 0.2f else 0.75f + 0.25f * Random.nextFloat()
            }
            invalidate()
            postDelayed(this, 40L + Random.nextLong(360L))
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == display?.displayId) remap()
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

    fun setPanel(h: PanelHinge?) {
        if (h == panel) return
        panel = h
        rebuild()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        remap()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        remap()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        context.getSystemService(DisplayManager::class.java)
            ?.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        restartFlicker()
    }

    override fun onDetachedFromWindow() {
        context.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        removeCallbacks(tick)
        cache?.recycle()
        cache = null
        super.onDetachedFromWindow()
    }

    private fun restartFlicker() {
        removeCallbacks(tick)
        if (settings.flicker && showing && isAttachedToWindow) post(tick)
    }

    /** (Re)generates the natural-space damage only when the hinge, seed, severity or dead-line option changes. */
    private fun rebuild() {
        val p = panel
        val natural = p?.let { Hinge(it.orientation, it.pos, it.posture) }
        showing = p != null && FoldGate.shouldShow(natural, p.w0, p.h0, resources.displayMetrics.density)
        if (!showing || p == null) {
            placement = if (p == null) "hidden: no hinge (folded / cover screen / not a foldable)"
            else "hidden: not the open inner display (${p.w0}x${p.h0})"
            naturalScene = null; sceneKey = null; scene = null; mappedRotation = -1
            removeCallbacks(tick)
            cache?.recycle(); cache = null
            invalidate()
            return
        }
        val key = listOf(p, settings.severity, settings.deadLine, settings.seed)
        if (key != sceneKey) {
            naturalScene = CrackGenerator.generate(
                CrackSpec(p.w0, p.h0, p.orientation, p.pos, settings.severity / 100f, settings.deadLine, settings.seed)
            )
            sceneKey = key
        }
        placement = "showing: natural hinge ${p.orientation.name.lowercase()} at ${p.pos.toInt()}px on ${p.w0}x${p.h0}"
        mappedRotation = -1
        remap()
    }

    /** Maps the natural scene into the current rotation. Draws nothing while size and rotation disagree. */
    private fun remap() {
        val ns = naturalScene ?: return
        val w = width; val h = height
        if (w <= 0 || h <= 0) return
        val rot = display?.rotation ?: 0
        val (sw, sh) = PanelTransform.screenSize(rot, ns.width, ns.height)
        val consistent = sw == sh || (sw > sh) == (w > h)
        if (!consistent) {
            // Mid-rotation: the window hasn't been resized yet. Hide rather than flash in the wrong place.
            scene = null; mappedRotation = -1
            invalidate()
            return
        }
        getLocationOnScreen(loc)
        if (rot == mappedRotation && scene != null && cache?.width == w && cache?.height == h) return
        val sc = PanelTransform.sceneToScreen(ns, rot, loc[0], loc[1])
        scene = sc
        mappedRotation = rot

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
        val sc = scene ?: return
        if ((display?.rotation ?: 0) != mappedRotation) {
            post { remap() }
            return
        }
        cache?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        if (settings.flicker) {
            surface.canvas = canvas
            CrackPainter.paint(sc, surface, filter = { it.flicker }, alphaOf = { flickerAlpha[it] ?: 1f })
            surface.canvas = null
        }
    }
}
