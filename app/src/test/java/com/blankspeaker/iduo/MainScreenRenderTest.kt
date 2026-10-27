package com.blankspeaker.iduo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.widget.FrameLayout
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.File
import java.io.FileOutputStream

/** Renders the real main-screen layout (Robolectric native graphics) to PNGs for the README. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MainScreenRenderTest {

    private fun shoot(name: String) {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val root = activity.findViewById<View>(android.R.id.content)
        val w = activity.resources.displayMetrics.widthPixels
        val h = activity.resources.displayMetrics.heightPixels
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF0F1418.toInt())
        root.draw(c)
        val dir = File(System.getProperty("iduo.shots") ?: "build/shots").apply { mkdirs() }
        FileOutputStream(File(dir, name)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun mainScreenPermissionGranted() {
        ShadowSettings.setCanDrawOverlays(true)
        shoot("main-screen.png")
    }

    @Test fun mainScreenNeedsPermission() {
        ShadowSettings.setCanDrawOverlays(false)
        shoot("main-screen-needs-permission.png")
    }

    @Test fun permissionButtonOnlyWhenMissing() {
        ShadowSettings.setCanDrawOverlays(true)
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(View.GONE, a.findViewById<View>(R.id.grantOverlay).visibility)
        ShadowSettings.setCanDrawOverlays(false)
        val b = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertEquals(View.VISIBLE, b.findViewById<View>(R.id.grantOverlay).visibility)
    }
}
