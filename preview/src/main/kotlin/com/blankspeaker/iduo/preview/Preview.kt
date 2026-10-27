package com.blankspeaker.iduo.preview

import com.blankspeaker.iduo.crack.*
import java.awt.*
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** Renders the exact same CrackGenerator/CrackPainter output the app draws, pixel for pixel, no AA. */
class AwtSurface(private val g: Graphics2D) : PixelSurface {
    override fun fill(left: Int, top: Int, right: Int, bottom: Int, argb: Int) {
        g.color = Color(argb, true)
        g.fillRect(left, top, right - left, bottom - top)
    }
}

fun mockScreen(w: Int, h: Int, seed: Int): BufferedImage {
    val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.paint = GradientPaint(0f, 0f, Color(0x1d2b64), w.toFloat(), h.toFloat(), Color(0xf8a978 + seed))
    g.fillRect(0, 0, w, h)
    val cell = minOf(w, h) / 7
    val colors = intArrayOf(0x4285F4, 0xEA4335, 0xFBBC05, 0x34A853, 0x9C27B0, 0x00BCD4, 0xFF7043)
    var k = 0
    var y = cell
    while (y < h - cell * 2) {
        var x = cell / 2
        while (x < w - cell) {
            g.color = Color(colors[k++ % colors.size])
            g.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), cell * 0.62f, cell * 0.62f, cell * 0.3f, cell * 0.3f))
            x += cell
        }
        y += cell
    }
    g.color = Color(255, 255, 255, 220)
    g.font = Font(Font.SANS_SERIF, Font.BOLD, cell / 3)
    g.drawString("10:31", cell / 2, cell / 2)
    g.dispose()
    return img
}

fun render(spec: CrackSpec, label: String, file: File, labelIt: Boolean = true): BufferedImage {
    val img = mockScreen(spec.width, spec.height, (spec.seed % 7).toInt())
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
    // Android 12+ caps pass-through overlays at 0.8 opacity, so preview at 0.8 too.
    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f)
    val scene = CrackGenerator.generate(spec)
    CrackPainter.paint(scene, AwtSurface(g))
    g.composite = AlphaComposite.SrcOver
    if (labelIt) {
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0, 0, 0, 170)
        val fs = spec.width / 40
        g.font = Font(Font.SANS_SERIF, Font.BOLD, fs)
        val tw = g.fontMetrics.stringWidth(label)
        g.fillRect(0, spec.height - fs * 2, tw + fs, fs * 2)
        g.color = Color.WHITE
        g.drawString(label, fs / 2, spec.height - fs / 2)
    }
    g.dispose()
    ImageIO.write(img, "png", file)
    val band = scene.rects.filter { it.part == Part.BAND }
    println("wrote ${file.path} rects=${scene.rects.size} bandTop=${band.minOfOrNull { it.top }} bandBottom=${band.maxOfOrNull { it.bottom }}")
    return img
}

fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: "out").apply { mkdirs() }
    dir.listFiles()?.filter { it.name.startsWith("preview-") }?.forEach { it.delete() }
    val tag = "COMPUTER RENDER (app drawing code, mock screen)"
    // Pixel 10 Pro Fold-like inner display, vertical crease at the centre.
    val a = render(CrackSpec(2076, 2152, CreaseOrientation.VERTICAL, 1038f, 0.05f, true, 7), "Inner screen, low - $tag", File(dir, "preview-inner-low.png"))
    val b = render(CrackSpec(2076, 2152, CreaseOrientation.VERTICAL, 1038f, 0.5f, true, 42), "Inner screen, medium - $tag", File(dir, "preview-inner-medium.png"))
    val c = render(CrackSpec(2076, 2152, CreaseOrientation.VERTICAL, 1038f, 1f, true, 1337), "Inner screen, max - $tag", File(dir, "preview-inner-max.png"))
    render(CrackSpec(2152, 2076, CreaseOrientation.HORIZONTAL, 1038f, 0.6f, true, 5), "Inner screen rotated (horizontal hinge) - $tag", File(dir, "preview-inner-rotated.png"))
    // Folded: the gate says no, so the generator gets no crease and draws nothing.
    val show = FoldGate.shouldShow(null, 1080, 2364, 2.625f)
    render(CrackSpec(1080, 2364, CreaseOrientation.VERTICAL, if (show) 540f else null, 1f, true, 3), "Folded / cover screen: nothing drawn - $tag", File(dir, "preview-cover-folded.png"))
    // Unlabeled renders for side-by-side comparisons with reference photos.
    for ((name, sev, seed) in listOf(Triple("low", 0.15f, 11L), Triple("mid", 0.5f, 21L), Triple("high", 0.9f, 31L))) {
        render(CrackSpec(2076, 2152, CreaseOrientation.VERTICAL, 1038f, sev, true, seed), "", File(dir, "raw-$name.png"), labelIt = false)
    }
    val sw = a.width / 2; val sh = a.height / 2
    val strip = BufferedImage(sw * 3 + 40, sh + 20, BufferedImage.TYPE_INT_RGB)
    val g = strip.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g.color = Color(18, 18, 18); g.fillRect(0, 0, strip.width, strip.height)
    listOf(a, b, c).forEachIndexed { i, im -> g.drawImage(im, 10 + i * (sw + 10), 10, sw, sh, null) }
    g.dispose()
    ImageIO.write(strip, "png", File(dir, "preview-severity-strip.png"))
    println("wrote strip")
}
