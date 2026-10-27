package com.blankspeaker.iduo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import android.util.Log
import com.blankspeaker.iduo.crack.CreaseOrientation
import com.blankspeaker.iduo.crack.Posture
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CrackService : LifecycleService() {

    companion object {
        const val ACTION_START = "com.blankspeaker.iduo.START"
        const val ACTION_UPDATE = "com.blankspeaker.iduo.UPDATE"
        const val ACTION_STOP = "com.blankspeaker.iduo.STOP"
        private const val CHANNEL = "crack"
        private const val NOTIF_ID = 1
        private const val TAG = "iDuo"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
        private val _placement = MutableStateFlow("")
        val placement: StateFlow<String> = _placement

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, CrackService::class.java).setAction(action)
            if (action == ACTION_START) ContextCompat.startForegroundService(ctx, i)
            else if (_running.value) ctx.startService(i)
        }
    }

    private var wm: WindowManager? = null
    private var view: CrackOverlayView? = null
    private var layoutJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        ensureOverlay()
        view?.apply(Prefs.load(this))
        publishPlacement()
        _running.value = true
        return START_NOT_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW)
        )
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, CrackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_crack)
            .setContentTitle(getString(R.string.app_name))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun ensureOverlay() {
        if (view != null) return
        // On Android 11+ use a WindowContext so Jetpack WindowManager can report hinge info for the overlay.
        val uiCtx: Context = if (Build.VERSION.SDK_INT >= 30) {
            val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
            createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else this
        val manager = uiCtx.getSystemService(WindowManager::class.java)
        val v = CrackOverlayView(uiCtx)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "iDuo crack"
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
            if (Build.VERSION.SDK_INT >= 31) {
                // Android 12+ blocks touches through overlays more opaque than this (default 0.8).
                val max = getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch
                alpha = minOf(0.8f, max)
            }
        }
        manager.addView(v, lp)
        wm = manager
        view = v

        if (Build.VERSION.SDK_INT >= 30) {
            layoutJob = lifecycleScope.launch {
                runCatching {
                    WindowInfoTracker.getOrCreate(uiCtx).windowLayoutInfo(uiCtx).collect { info ->
                        val fold = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                        Log.i(TAG, "layout: ${info.displayFeatures}")
                        featureHinge = fold?.let {
                            val posture = when (it.state) {
                                FoldingFeature.State.FLAT -> Posture.FLAT
                                FoldingFeature.State.HALF_OPENED -> Posture.HALF_OPENED
                                else -> null
                            } ?: return@let null
                            if (it.orientation == FoldingFeature.Orientation.VERTICAL)
                                ScreenHinge(CreaseOrientation.VERTICAL, it.bounds.exactCenterX(), posture)
                            else ScreenHinge(CreaseOrientation.HORIZONTAL, it.bounds.exactCenterY(), posture)
                        }
                        layoutSeen = true
                        applyHinge()
                    }
                }.onFailure { Log.w(TAG, "WindowInfoTracker failed, using device-state fallback", it) }
            }
        }
        if (Build.VERSION.SDK_INT >= 30) registerHingeSensor(uiCtx)
        v.post { publishPlacement() }
    }

    private var featureHinge: ScreenHinge? = null
    private var layoutSeen = false
    private var deviceOpen: Boolean? = null
    private var deviceCallback: Any? = null
    private var uiContextRef: Context? = null

    /**
     * FoldingFeature wins. If WindowManager reports nothing for the overlay window, fall back to the
     * hinge-angle sensor: opened >= 60 degrees means a centred crease (vertical in natural orientation).
     * FoldGate still requires a >=560dp screen, so the cover screen always stays clean.
     */
    private fun applyHinge() {
        val v = view ?: return
        val hinge = featureHinge ?: if (deviceOpen == true) centredHinge() else null
        v.setHinge(hinge)
        Log.i(TAG, "hinge=$hinge deviceOpen=$deviceOpen -> ${v.placement}")
        publishPlacement()
    }

    private fun centredHinge(): ScreenHinge? {
        val ctx = uiContextRef ?: return null
        if (Build.VERSION.SDK_INT < 30) return null
        val bounds = ctx.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val rot = ctx.display?.rotation ?: 0
        return if (rot == android.view.Surface.ROTATION_0 || rot == android.view.Surface.ROTATION_180)
            ScreenHinge(CreaseOrientation.VERTICAL, bounds.exactCenterX(), Posture.FLAT)
        else ScreenHinge(CreaseOrientation.HORIZONTAL, bounds.exactCenterY(), Posture.FLAT)
    }

    /** Hinge-angle sensor (API 30+, present on foldables, absent on tablets/phones). */
    private fun registerHingeSensor(ctx: Context) {
        uiContextRef = ctx
        runCatching {
            val sm = getSystemService(android.hardware.SensorManager::class.java) ?: return
            val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_HINGE_ANGLE) ?: return
            val l = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) {
                    val open = e.values[0] >= 60f
                    if (open != deviceOpen) {
                        deviceOpen = open
                        Log.i(TAG, "hinge angle ${e.values[0]} -> open=$open")
                        applyHinge()
                    }
                }
                override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
            }
            sm.registerListener(l, sensor, android.hardware.SensorManager.SENSOR_DELAY_NORMAL)
            deviceCallback = l
        }.onFailure { Log.w(TAG, "hinge sensor unavailable", it) }
    }

    private fun publishPlacement() {
        view?.let { _placement.value = it.placement }
    }

    override fun onDestroy() {
        layoutJob?.cancel()
        (deviceCallback as? android.hardware.SensorEventListener)?.let { l ->
            runCatching { getSystemService(android.hardware.SensorManager::class.java)?.unregisterListener(l) }
        }
        view?.let { v -> runCatching { wm?.removeView(v) } }
        view = null
        _running.value = false
        _placement.value = ""
        super.onDestroy()
    }
}
