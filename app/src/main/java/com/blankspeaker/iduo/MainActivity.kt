package com.blankspeaker.iduo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.random.Random

@Suppress("UseSwitchCompatOrMaterialCode")
class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var grantOverlay: Button
    private lateinit var grantNotif: Button
    private lateinit var crackSwitch: Switch
    private lateinit var severity: SeekBar
    private lateinit var severityLabel: TextView
    private lateinit var deadLine: Switch
    private lateinit var flicker: Switch
    private var hingeText = "checking…"
    private var syncing = false

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        grantOverlay = findViewById(R.id.grantOverlay)
        grantNotif = findViewById(R.id.grantNotif)
        crackSwitch = findViewById(R.id.crackSwitch)
        severity = findViewById(R.id.severity)
        severityLabel = findViewById(R.id.severityLabel)
        deadLine = findViewById(R.id.deadLine)
        flicker = findViewById(R.id.flicker)

        val s = Prefs.load(this)
        severity.progress = s.severity
        deadLine.isChecked = s.deadLine
        flicker.isChecked = s.flicker
        updateSeverityLabel(s.severity)

        grantOverlay.setOnClickListener { openOverlaySettings() }
        grantNotif.setOnClickListener { askNotifications() }
        crackSwitch.setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            if (on) {
                if (!Settings.canDrawOverlays(this)) {
                    syncing = true; crackSwitch.isChecked = false; syncing = false
                    openOverlaySettings()
                    return@setOnCheckedChangeListener
                }
                askNotifications()
                CrackService.send(this, CrackService.ACTION_START)
            } else {
                CrackService.send(this, CrackService.ACTION_STOP)
            }
        }
        severity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = updateSeverityLabel(p)
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) = saveAndPush()
        })
        deadLine.setOnCheckedChangeListener { _, _ -> saveAndPush() }
        flicker.setOnCheckedChangeListener { _, _ -> saveAndPush() }
        findViewById<Button>(R.id.regen).setOnClickListener {
            Prefs.save(this, Prefs.load(this).copy(seed = Random.nextLong()))
            CrackService.send(this, CrackService.ACTION_UPDATE)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(CrackService.running, CrackService.placement) { r, p -> r to p }.collect { refresh() }
                }
                launch {
                    WindowInfoTracker.getOrCreate(this@MainActivity).windowLayoutInfo(this@MainActivity).collect { info ->
                        val f = info.displayFeatures.filterIsInstance<FoldingFeature>().firstOrNull()
                        hingeText = if (f == null) "none reported (folded, outer screen, or not a foldable)"
                        else "${f.orientation.toString().lowercase()} at ${f.bounds}, ${f.state.toString().lowercase()}"
                        refresh()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun updateSeverityLabel(p: Int) {
        val word = when {
            p < 20 -> "a few dead pixels"
            p < 45 -> "\"it's just the crease\""
            p < 70 -> "AppleCare territory"
            p < 90 -> "\$999 inner screen"
            else -> "dead band"
        }
        severityLabel.text = "${getString(R.string.severity)}: $p  ($word)"
    }

    private fun saveAndPush() {
        Prefs.save(
            this,
            Prefs.load(this).copy(severity = severity.progress, deadLine = deadLine.isChecked, flicker = flicker.isChecked)
        )
        CrackService.send(this, CrackService.ACTION_UPDATE)
    }

    private fun needsNotifPermission() = Build.VERSION.SDK_INT >= 33 &&
        checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun askNotifications() {
        if (needsNotifPermission()) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun openOverlaySettings() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun refresh() {
        val overlayOk = Settings.canDrawOverlays(this)
        val running = CrackService.running.value
        grantOverlay.visibility = if (overlayOk) View.GONE else View.VISIBLE
        grantNotif.visibility = if (needsNotifPermission()) View.VISIBLE else View.GONE
        syncing = true
        crackSwitch.isChecked = running
        syncing = false
        val placement = CrackService.placement.value
        status.text = buildString {
            append("Overlay permission: ").append(if (overlayOk) "granted" else "needed").append('\n')
            append("Hinge: ").append(hingeText).append('\n')
            append("Damage: ").append(if (running) "ON" else "off")
            if (running && placement.isNotEmpty()) append(" — ").append(placement)
        }
    }
}
