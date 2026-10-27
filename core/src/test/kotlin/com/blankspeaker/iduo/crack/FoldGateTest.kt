package com.blankspeaker.iduo.crack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FoldGateTest {
    private val d = 2.625f // Pixel 10 Pro Fold-ish density
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

    @Test fun noCreaseMeansEmptyScene() =
        assertTrue(CrackGenerator.generate(CrackSpec(2076, 2152, creasePos = null)).isEmpty)

    @Test fun severityScalesDamage() {
        val lo = CrackGenerator.generate(CrackSpec(2076, 2152, creasePos = 1038f, severity = 0f, seed = 3))
        val hi = CrackGenerator.generate(CrackSpec(2076, 2152, creasePos = 1038f, severity = 1f, seed = 3))
        assertTrue(hi.strokes.size > lo.strokes.size * 2, "lo=${lo.strokes.size} hi=${hi.strokes.size}")
        assertTrue(lo.strokes.count { it.kind == StrokeKind.BRIGHT && it.ys[0] == it.ys[1] } >= 1)
    }

    @Test fun deterministicPerSeed() {
        val a = CrackGenerator.generate(CrackSpec(2076, 2152, creasePos = 1038f, seed = 9))
        val b = CrackGenerator.generate(CrackSpec(2076, 2152, creasePos = 1038f, seed = 9))
        assertEquals(a.strokes.size, b.strokes.size)
    }

    @Test fun rowsRunFromCreaseToRightEdge() {
        val sc = CrackGenerator.generate(CrackSpec(2076, 2152, creasePos = 1038f, severity = 0.6f, seed = 5))
        val rows = sc.strokes.filter { it.kind == StrokeKind.BRIGHT && it.ys[0] == it.ys[1] }
        assertTrue(rows.isNotEmpty())
        rows.forEach { assertEquals(1038f, it.xs[0]); assertEquals(2076f, it.xs[1]) }
    }
}
