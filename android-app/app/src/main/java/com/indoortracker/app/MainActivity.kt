package com.indoortracker.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.indoortracker.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var scanning = false

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

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != WifiScanService.ACTION_STATUS) return
            val status = intent.getStringExtra(WifiScanService.EXTRA_STATUS) ?: return
            val message = intent.getStringExtra(WifiScanService.EXTRA_MESSAGE) ?: ""
            binding.scanStatusText.text = message
            if (status == WifiScanService.STATUS_FINGERPRINT_SAVED) {
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.deviceIdText.text = "Device ID: ${getDeviceId()}\n(enter this on the web dashboard to find this phone)"

        binding.startLiveButton.setOnClickListener {
            ensurePreconditionsThenRun { startScanService(WifiScanService.MODE_LIVE) }
        }

        binding.stopLiveButton.setOnClickListener {
            stopScanService()
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

            ensurePreconditionsThenRun {
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

        updateScanningUi()
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(
            statusReceiver,
            IntentFilter(WifiScanService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(statusReceiver)
        } catch (e: IllegalArgumentException) {
            // already unregistered
        }
    }

    /**
     * Pre-flight: permission granted AND WiFi + Location services switched on.
     * The OS will return empty/null scan results if WiFi or Location are off, so we
     * guide the user to enable them instead of silently failing.
     */
    private fun ensurePreconditionsThenRun(action: () -> Unit) {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        if (!isWifiEnabled()) {
            Toast.makeText(
                this,
                "Turn on WiFi in Settings — WiFi scanning needs it enabled.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT < 33 && !isLocationEnabled()) {
            Toast.makeText(
                this,
                "Turn on Location in Settings — Android 8-12 requires Location ON to release WiFi scan results.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            return
        }
        action()
    }

    private fun startScanService(mode: String) {
        val intent = Intent(this, WifiScanService::class.java).apply {
            putExtra(WifiScanService.EXTRA_MODE, mode)
        }
        ContextCompat.startForegroundService(this, intent)
        scanning = true
        updateScanningUi()
        binding.scanStatusText.text = "Scanning started — waiting for first results…"
        Toast.makeText(this, "Live scanning started — updates roughly every 20-30s (Android limits scan frequency)", Toast.LENGTH_LONG).show()
    }

    private fun stopScanService() {
        stopService(Intent(this, WifiScanService::class.java))
        scanning = false
        updateScanningUi()
        binding.scanStatusText.text = "Scanning stopped."
    }

    private fun updateScanningUi() {
        binding.startLiveButton.isEnabled = !scanning
        binding.stopLiveButton.isEnabled = scanning
    }

    private fun isWifiEnabled(): Boolean {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        return wm.isWifiEnabled
    }

    private fun isLocationEnabled(): Boolean {
        val lm = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return try {
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: SecurityException) {
            true // can't check — don't block the user
        }
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
