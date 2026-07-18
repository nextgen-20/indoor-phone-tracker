package com.indoortracker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Scans WiFi periodically and uploads readings to Firestore.
 *
 * IMPORTANT REAL-WORLD CONSTRAINT:
 * Android throttles WifiManager.startScan() to roughly 4 calls per 2-minute
 * window per app (since Android 9, API 28). This service requests a scan every
 * SCAN_INTERVAL_MS but the OS may silently ignore requests that exceed the
 * throttle — in that case Android just returns the last cached scan results,
 * which is still useful (they're rarely more than ~30s stale in practice) but
 * won't be a fresh scan every single time. This is a platform limit, not
 * something fixable from app code.
 */
class WifiScanService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var wifiManager: WifiManager
    private val db = FirebaseFirestore.getInstance()

    // Stable per-install device ID, so the dashboard can tell devices apart
    private val deviceId: String by lazy {
        val prefs = getSharedPreferences("tracker_prefs", MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private var mode: String = MODE_LIVE  // MODE_LIVE or MODE_CALIBRATE
    private var calibrationLabel: String? = null
    private var calibrationX: Double? = null
    private var calibrationY: Double? = null

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            uploadCurrentScan(freshScan = success)
        }
    }

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        registerReceiver(
            scanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )
        startForeground(NOTIFICATION_ID, buildNotification("Scanning WiFi signal..."))
        startScanLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            mode = it.getStringExtra(EXTRA_MODE) ?: MODE_LIVE
            calibrationLabel = it.getStringExtra(EXTRA_LABEL)
            calibrationX = if (it.hasExtra(EXTRA_X)) it.getDoubleExtra(EXTRA_X, 0.0) else null
            calibrationY = if (it.hasExtra(EXTRA_Y)) it.getDoubleExtra(EXTRA_Y, 0.0) else null
        }
        return START_STICKY
    }

    private fun startScanLoop() {
        scope.launch {
            while (true) {
                @Suppress("DEPRECATION")
                wifiManager.startScan() // Android may throttle this; see class doc
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    private fun uploadCurrentScan(freshScan: Boolean) {
        @Suppress("DEPRECATION")
        val results = wifiManager.scanResults ?: return
        if (results.isEmpty()) return

        // Build a signal vector: BSSID -> RSSI (dBm), the "fingerprint" of this spot
        val signalMap = results.associate { it.BSSID to it.level }

        val reading = hashMapOf(
            "deviceId" to deviceId,
            "signals" to signalMap,
            "timestamp" to System.currentTimeMillis(),
            "freshScan" to freshScan
        )

        if (mode == MODE_CALIBRATE && calibrationLabel != null && calibrationX != null && calibrationY != null) {
            // Save as a labeled fingerprint for the dashboard's matching algorithm
            reading["label"] = calibrationLabel!!
            reading["x"] = calibrationX!!
            reading["y"] = calibrationY!!
            db.collection("fingerprints").add(reading)
            // Calibration is a one-shot action; drop back to live mode after saving
            mode = MODE_LIVE
        } else {
            // Live reading: dashboard reads the most recent one per device to
            // estimate current position via nearest-fingerprint matching
            db.collection("liveReadings").document(deviceId).set(reading)
        }
    }

    private fun buildNotification(text: String): Notification {
        val channelId = "wifi_scan_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "WiFi Scanning", NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Indoor Phone Tracker")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private var receiverRegistered = false

override fun onCreate() {
    super.onCreate()

    wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    registerReceiver(
        scanReceiver,
        IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
    )
    receiverRegistered = true

    startForeground(NOTIFICATION_ID, buildNotification("Scanning WiFi signal..."))
    startScanLoop()
}

override fun onDestroy() {
    if (receiverRegistered) {
        unregisterReceiver(scanReceiver)
    }
    scope.cancel()
    super.onDestroy()
}

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        // Requesting scans faster than this is pointless — Android throttles to
        // ~4 scans per 2 minutes (≈ one every 30s) in the foreground anyway.
        const val SCAN_INTERVAL_MS = 20_000L
        const val NOTIFICATION_ID = 1001

        const val EXTRA_MODE = "mode"
        const val EXTRA_LABEL = "label"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val MODE_LIVE = "live"
        const val MODE_CALIBRATE = "calibrate"
    }
}
