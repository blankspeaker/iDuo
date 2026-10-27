package com.blankspeaker.iduo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var grantOverlay: MaterialButton
    private lateinit var crackSwitch: MaterialSwitch
    private lateinit var severity: Slider
    private lateinit var flicker: MaterialSwitch
    private var syncing = false

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        grantOverlay = findViewById(R.id.grantOverlay)
        crackSwitch = findViewById(R.id.crackSwitch)
        severity = findViewById(R.id.severity)
        flicker = findViewById(R.id.flicker)

        val s = Prefs.load(this)
        severity.value = s.severity.coerceIn(0, 100).toFloat()
        flicker.isChecked = s.flicker

        grantOverlay.setOnClickListener { openOverlaySettings() }
        crackSwitch.setOnCheckedChangeListener { _, on ->
            if (syncing) return@setOnCheckedChangeListener
            if (on) {
                if (!Settings.canDrawOverlays(this)) {
                    setSwitch(false)
                    openOverlaySettings()
                    return@setOnCheckedChangeListener
                }
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                CrackService.send(this, CrackService.ACTION_START)
            } else {
                CrackService.send(this, CrackService.ACTION_STOP)
            }
        }
        severity.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) = saveAndPush()
        })
        flicker.setOnCheckedChangeListener { _, _ -> if (!syncing) saveAndPush() }
        findViewById<View>(R.id.regen).setOnClickListener {
            Prefs.save(this, Prefs.load(this).copy(seed = Random.nextLong()))
            CrackService.send(this, CrackService.ACTION_UPDATE)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                CrackService.running.collect { setSwitch(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        grantOverlay.visibility = if (Settings.canDrawOverlays(this)) View.GONE else View.VISIBLE
        setSwitch(CrackService.running.value)
    }

    private fun setSwitch(on: Boolean) {
        syncing = true
        crackSwitch.isChecked = on
        syncing = false
    }

    private fun saveAndPush() {
        Prefs.save(this, Prefs.load(this).copy(severity = severity.value.toInt(), flicker = flicker.isChecked))
        CrackService.send(this, CrackService.ACTION_UPDATE)
    }

    private fun openOverlaySettings() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }
}
