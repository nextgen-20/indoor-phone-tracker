package com.indoortracker.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.indoortracker.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val requiredPermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(
                Manifest.permission.NEARBY_WIFI_DEVICES,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private val permissionLauncher = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            Toast.makeText(this, "Permissions granted — starting scanner", Toast.LENGTH_SHORT).show()
            startScanService(WifiScanService.MODE_LIVE)
        } else {
            Toast.makeText(
                this,
                "WiFi scanning needs these permissions to work (this is an Android OS requirement, not something this app is choosing to collect for other purposes).",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.deviceIdText.text = "Device ID: ${getDeviceId()}\n(enter this on the web dashboard to find this phone)"

        binding.startLiveButton.setOnClickListener {
            ensurePermissionsThenRun { startScanService(WifiScanService.MODE_LIVE) }
        }

        binding.recordFingerprintButton.setOnClickListener {
            val label = binding.labelInput.text.toString().trim()
            val xText = binding.xInput.text.toString().trim()
            val yText = binding.yInput.text.toString().trim()
            val x = xText.toDoubleOrNull()
            val y = yText.toDoubleOrNull()

            if (label.isEmpty() || x == null || y == null) {
                Toast.makeText(
                    this,
                    "Enter a label and the x/y position shown on the web dashboard after you tap your spot there.",
                    Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }

            ensurePermissionsThenRun {
                val intent = Intent(this, WifiScanService::class.java).apply {
                    putExtra(WifiScanService.EXTRA_MODE, WifiScanService.MODE_CALIBRATE)
                    putExtra(WifiScanService.EXTRA_LABEL, label)
                    putExtra(WifiScanService.EXTRA_X, x)
                    putExtra(WifiScanService.EXTRA_Y, y)
                }
                ContextCompat.startForegroundService(this, intent)
                Toast.makeText(this, "Fingerprint '$label' will be recorded on the next scan", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun ensurePermissionsThenRun(action: () -> Unit) {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            action()
        } else {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startScanService(mode: String) {
        val intent = Intent(this, WifiScanService::class.java).apply {
            putExtra(WifiScanService.EXTRA_MODE, mode)
        }
        ContextCompat.startForegroundService(this, intent)
        Toast.makeText(this, "Live scanning started — updates roughly every 20-30s (Android limits scan frequency)", Toast.LENGTH_LONG).show()
    }

    private fun getDeviceId(): String {
        val prefs = getSharedPreferences("tracker_prefs", MODE_PRIVATE)
        return prefs.getString("device_id", null) ?: run {
            val id = java.util.UUID.randomUUID().toString()
            prefs.edit().putString("device_id", id).apply()
            id
        }
    }
}
