package com.blankspeaker.iduo.crack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PanelTransformTest {
    private val w0 = 2076
    private val h0 = 2152
    private val scene = CrackGenerator.generate(CrackSpec(w0, h0, CreaseOrientation.VERTICAL, 1038f, 0.8f, true, 77))

    private fun key(r: PixelRect) = listOf(r.left, r.top, r.right, r.bottom, r.color, r.part.ordinal, if (r.flicker) 1 else 0)

    @Test fun roundTripIsIdentityForEveryRotation() {
        for (rot in 0..3) for ((dx, dy) in listOf(0 to 0, 0 to 37, 12 to 0)) {
            val screen = PanelTransform.sceneToScreen(scene, rot, dx, dy)
            val back = screen.rects.map { PanelTransform.toNatural(it, rot, w0, h0, dx, dy) }
            assertEquals(scene.rects.map(::key), back.map(::key), "rotation $rot offset $dx,$dy")
        }
    }

    @Test fun screenRectsStayInsideRotatedScreen() {
        for (rot in 0..3) {
            val s = PanelTransform.sceneToScreen(scene, rot)
            val (w, h) = PanelTransform.screenSize(rot, w0, h0)
            assertEquals(w, s.width); assertEquals(h, s.height)
            s.rects.forEach { assertTrue(it.left >= 0 && it.top >= 0 && it.right <= w && it.bottom <= h && it.width > 0 && it.height > 0, "rot $rot") }
        }
    }

    @Test fun cornersFollowThePhysicalPanel() {
        // Natural top-left pixel. ROTATION_90 = device turned CCW: it ends up bottom-left.
        val px = PixelRect(0, 0, 1, 1, 0, Part.DEAD)
        fun at(rot: Int) = PanelTransform.toScreen(px, rot, w0, h0).let { it.left to it.top }
        assertEquals(0 to 0, at(0))
        assertEquals(0 to w0 - 1, at(1))
        assertEquals(w0 - 1 to h0 - 1, at(2))
        assertEquals(h0 - 1 to 0, at(3))
    }

    @Test fun deadLineStaysOnTheSamePhysicalPixels() {
        // The dead line is vertical from the natural top edge. After any rotation, mapping its screen rect back
        // must hit the same natural pixels, and it must run along the hinge in screen space.
        val dead = scene.rects.filter { it.part == Part.DEAD }.maxBy { it.height }
        for (rot in 0..3) {
            val s = PanelTransform.toScreen(dead, rot, w0, h0)
            val back = PanelTransform.toNatural(s, rot, w0, h0)
            assertEquals(key(dead), key(back))
            if (rot and 1 == 1) assertTrue(s.width > s.height, "rot $rot: crease is horizontal on screen")
            else assertTrue(s.height > s.width, "rot $rot: crease is vertical on screen")
        }
    }

    @Test fun hingeConversionRoundTrips() {
        for (rot in 0..3) for (o in CreaseOrientation.values()) for (p in listOf(1038f, 700.5f)) {
            val (no, np) = PanelTransform.hingeToNatural(o, p, rot, w0, h0)
            val (so, sp) = PanelTransform.hingeToScreen(no, np, rot, w0, h0)
            assertEquals(o, so); assertEquals(p, sp, 0.001f)
        }
    }

    @Test fun sameNaturalHingeFromEveryRotation() {
        // A physical vertical hinge at natural x=1000 reported in each rotation's screen coords maps back to x=1000.
        for (rot in 0..3) {
            val (so, sp) = PanelTransform.hingeToScreen(CreaseOrientation.VERTICAL, 1000f, rot, w0, h0)
            val (no, np) = PanelTransform.hingeToNatural(so, sp, rot, w0, h0)
            assertEquals(CreaseOrientation.VERTICAL, no); assertEquals(1000f, np, 0.001f)
        }
    }

    @Test fun bandSideIsDecidedInNaturalSpaceOnly() {
        // Generation never sees the rotation, so the band always goes toward the same physical edge.
        val band = scene.rects.filter { it.part == Part.BAND }
        assertTrue(band.all { it.left >= 1038 }, "band on natural right half")
        val rotated = PanelTransform.sceneToScreen(scene, 1).rects.filter { it.part == Part.BAND }
        // ROTATION_90: natural right edge (x = w0) becomes screen top (y = 0).
        assertTrue(rotated.all { it.bottom <= w0 - 1038 }, "band toward screen top in ROTATION_90")
    }
}
