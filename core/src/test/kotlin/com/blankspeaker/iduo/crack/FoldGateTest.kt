package com.blankspeaker.iduo.crack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoldGateTest {
    private val d = 2.625f
    private val v = CreaseOrientation.VERTICAL
    private val hz = CreaseOrientation.HORIZONTAL

    @Test fun openFlatInnerShows() = assertTrue(FoldGate.shouldShow(Hinge(v, 1038f, Posture.FLAT), 2076, 2152, d))
    @Test fun halfOpenedShows() = assertTrue(FoldGate.shouldShow(Hinge(v, 1038f, Posture.HALF_OPENED), 2076, 2152, d))
    @Test fun rotatedHorizontalHingeShows() = assertTrue(FoldGate.shouldShow(Hinge(hz, 1038f, Posture.FLAT), 2152, 2076, d))
    @Test fun noHingeHides() = assertFalse(FoldGate.shouldShow(null, 2076, 2152, d))
    @Test fun coverScreenHidesEvenIfHingeReported() = assertFalse(FoldGate.shouldShow(Hinge(v, 540f, Posture.FLAT), 1080, 2364, d))
    @Test fun regularPhoneHides() = assertFalse(FoldGate.shouldShow(null, 1080, 2400, 2.75f))
    @Test fun hingeOutsideOverlayHides() = assertFalse(FoldGate.shouldShow(Hinge(v, 2500f, Posture.FLAT), 2076, 2152, d))
    @Test fun zeroSizeHides() = assertFalse(FoldGate.shouldShow(Hinge(v, 10f, Posture.FLAT), 0, 0, d))
}

class CrackGeneratorTest {
    private val w = 2076
    private val h = 2152
    private val c = 1038
    private fun gen(s: Float, seed: Long = 5) = CrackGenerator.generate(CrackSpec(w, h, creasePos = c.toFloat(), severity = s, seed = seed))

    @Test fun noCreaseMeansEmptyScene() = assertTrue(CrackGenerator.generate(CrackSpec(w, h, creasePos = null)).isEmpty)

    @Test fun allRectsAreInsideTheScreen() {
        for (s in listOf(0f, 0.3f, 0.7f, 1f)) for (seed in 1L..20L) for (r in gen(s, seed).rects) {
            assertTrue(r.left >= 0 && r.top >= 0 && r.right <= w && r.bottom <= h && r.width > 0 && r.height > 0)
        }
    }

    @Test fun deadLineRunsFromTopEdgeOnTheCrease() {
        val dead = gen(0.5f).rects.filter { it.part == Part.DEAD }.maxBy { it.height }
        assertEquals(0, dead.top)
        assertTrue(dead.left <= c && dead.right >= c && dead.width in 3..4)
        assertTrue(dead.bottom in (h * 0.55f).toInt()..(h * 0.62f).toInt() + 1)
    }

    @Test fun bandIsOneContiguousRowBlockStartingAtTheCrease() {
        for (seed in 1L..20L) {
            val band = gen(0.6f, seed).rects.filter { it.part == Part.BAND }
            assertTrue(band.isNotEmpty())
            val top = band.minOf { it.top }; val bottom = band.maxOf { it.bottom }
            assertTrue(bottom - top <= (h * 0.053f).toInt() + 8, "band too tall: ${bottom - top}")
            band.forEach { assertTrue(it.left in c..c + 3, "starts at crease") }
        }
    }

    @Test fun nothingDrawnLeftOfTheCrease() {
        for (seed in 1L..20L) gen(1f, seed).rects.forEach { assertTrue(it.right > c - 12, "left of crease: ${it.left}") }
    }

    @Test fun severityScalesThicknessAndLength() {
        fun stats(s: Float): Pair<Int, Int> {
            val band = gen(s).rects.filter { it.part == Part.BAND }
            return (band.maxOf { it.bottom } - band.minOf { it.top }) to band.maxOf { it.right }
        }
        val (loT, loLen) = stats(0f)
        val (hiT, hiLen) = stats(1f)
        assertTrue(hiT > loT * 3, "thickness lo=$loT hi=$hiT")
        assertTrue(hiLen >= loLen && hiLen == w, "length lo=$loLen hi=$hiLen")
        assertEquals(w, CrackGenerator.generate(CrackSpec(w, h, creasePos = c.toFloat(), severity = 0.2f, seed = 5)).rects.filter { it.part == Part.BAND }.maxOf { it.right })
    }

    @Test fun litColumnGoesDownFromBandBesideCrease() {
        val line = gen(1f).rects.single { it.part == Part.LINE }
        assertEquals(h, line.bottom)
        assertTrue(line.left in c..c + 3 && line.width in 4..6)
    }

    @Test fun horizontalHingeIsTransposed() {
        val sc = CrackGenerator.generate(CrackSpec(h, w, CreaseOrientation.HORIZONTAL, c.toFloat(), 0.6f, true, 5))
        val dead = sc.rects.filter { it.part == Part.DEAD }.maxBy { it.width }
        assertEquals(0, dead.left)
        assertTrue(dead.top <= c && dead.bottom >= c)
    }
}
