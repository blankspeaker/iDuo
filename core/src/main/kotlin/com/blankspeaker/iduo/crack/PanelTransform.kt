package com.blankspeaker.iduo.crack

/**
 * Maps between the inner panel's natural (ROTATION_0) pixel grid and the current screen coordinates.
 * [rotation] uses android.view.Surface values: 0 = 0°, 1 = 90°, 2 = 180°, 3 = 270°
 * (the rotation of the drawn content, i.e. Display.getRotation()).
 * [w0] x [h0] is the natural panel size. Rects are half-open [left, right) x [top, bottom).
 *
 * Point mapping natural (x, y) -> screen:
 *   0: (x, y)    1: (y, w0 - x)    2: (w0 - x, h0 - y)    3: (h0 - y, x)
 */
object PanelTransform {
    fun screenSize(rotation: Int, w0: Int, h0: Int): Pair<Int, Int> =
        if (rotation and 1 == 1) h0 to w0 else w0 to h0

    /** Natural panel size from the current screen size. */
    fun naturalSize(rotation: Int, w: Int, h: Int): Pair<Int, Int> = screenSize(rotation, w, h)

    fun toScreen(r: PixelRect, rotation: Int, w0: Int, h0: Int, dx: Int = 0, dy: Int = 0): PixelRect {
        val (l, t, rr, b) = when (rotation and 3) {
            0 -> Quad(r.left, r.top, r.right, r.bottom)
            1 -> Quad(r.top, w0 - r.right, r.bottom, w0 - r.left)
            2 -> Quad(w0 - r.right, h0 - r.bottom, w0 - r.left, h0 - r.top)
            else -> Quad(h0 - r.bottom, r.left, h0 - r.top, r.right)
        }
        return PixelRect(l - dx, t - dy, rr - dx, b - dy, r.color, r.part, r.flicker)
    }

    fun toNatural(r: PixelRect, rotation: Int, w0: Int, h0: Int, dx: Int = 0, dy: Int = 0): PixelRect {
        val sl = r.left + dx; val st = r.top + dy; val sr = r.right + dx; val sb = r.bottom + dy
        val (l, t, rr, b) = when (rotation and 3) {
            0 -> Quad(sl, st, sr, sb)
            1 -> Quad(w0 - sb, sl, w0 - st, sr)
            2 -> Quad(w0 - sr, h0 - sb, w0 - sl, h0 - st)
            else -> Quad(st, h0 - sr, sb, h0 - sl)
        }
        return PixelRect(l, t, rr, b, r.color, r.part, r.flicker)
    }

    /** Re-maps a natural-coordinate scene onto the current screen, offset by the overlay's on-screen origin. */
    fun sceneToScreen(scene: CrackScene, rotation: Int, dx: Int = 0, dy: Int = 0): CrackScene {
        val (w, h) = screenSize(rotation, scene.width, scene.height)
        return CrackScene(w, h, scene.rects.map { toScreen(it, rotation, scene.width, scene.height, dx, dy) })
    }

    /** A hinge reported in current screen coordinates, expressed in natural panel coordinates. */
    fun hingeToNatural(o: CreaseOrientation, pos: Float, rotation: Int, w0: Int, h0: Int): Pair<CreaseOrientation, Float> {
        val v = o == CreaseOrientation.VERTICAL
        return when (rotation and 3) {
            0 -> o to pos
            1 -> if (v) CreaseOrientation.HORIZONTAL to pos else CreaseOrientation.VERTICAL to (w0 - pos)
            2 -> if (v) CreaseOrientation.VERTICAL to (w0 - pos) else CreaseOrientation.HORIZONTAL to (h0 - pos)
            else -> if (v) CreaseOrientation.HORIZONTAL to (h0 - pos) else CreaseOrientation.VERTICAL to pos
        }
    }

    /** Inverse of [hingeToNatural]. */
    fun hingeToScreen(o: CreaseOrientation, pos: Float, rotation: Int, w0: Int, h0: Int): Pair<CreaseOrientation, Float> {
        val v = o == CreaseOrientation.VERTICAL
        return when (rotation and 3) {
            0 -> o to pos
            1 -> if (v) CreaseOrientation.HORIZONTAL to (w0 - pos) else CreaseOrientation.VERTICAL to pos
            2 -> if (v) CreaseOrientation.VERTICAL to (w0 - pos) else CreaseOrientation.HORIZONTAL to (h0 - pos)
            else -> if (v) CreaseOrientation.HORIZONTAL to pos else CreaseOrientation.VERTICAL to (h0 - pos)
        }
    }

    private data class Quad(val a: Int, val b: Int, val c: Int, val d: Int)
}
