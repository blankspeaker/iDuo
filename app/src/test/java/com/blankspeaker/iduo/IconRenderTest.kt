package com.blankspeaker.iduo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** Renders the real adaptive launcher icon + notification icon to PNG for visual review. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class IconRenderTest {
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val dir = File(System.getProperty("iduo.shots") ?: "build/shots", "icon").apply { mkdirs() }

    private fun save(b: Bitmap, name: String) = FileOutputStream(File(dir, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }

    private fun render(d: android.graphics.drawable.Drawable, size: Int, bg: Int = Color.TRANSPARENT): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b); c.drawColor(bg)
        d.setBounds(0, 0, size, size); d.draw(c)
        return b
    }

    @Test fun renderLauncherIcon() {
        val icon = ContextCompat.getDrawable(ctx, R.mipmap.ic_launcher) as AdaptiveIconDrawable
        assertNotNull(icon.monochrome)
        for (s in listOf(512, 192, 96, 48)) save(render(icon, s), "ic_launcher_$s.png")
        // Themed (monochrome) icon, as Android 13+ tints it
        val mono = icon.monochrome!!.mutate()
        mono.setTint(0xFFD3E5F4.toInt())
        val themedBg = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val c = Canvas(themedBg)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF33414C.toInt() }
        c.drawCircle(256f, 256f, 256f, p)
        mono.setBounds(-128, -128, 640, 640) // adaptive layers are 108dp drawn into a 72dp mask
        mono.draw(c)
        save(themedBg, "ic_launcher_themed_512.png")
        save(render(ContextCompat.getDrawable(ctx, R.drawable.ic_stat_crack)!!, 96, 0xFF202124.toInt()), "ic_notification_96.png")
    }
}
