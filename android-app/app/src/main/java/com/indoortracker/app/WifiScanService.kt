package com.indoortracker.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.LocationManager
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
 * Foreground service that periodically scans WiFi and uploads readings to Firestore.
 *
 * Android throttles WifiManager.startScan() to ~4 calls per 2-minute window per app
 * (since Android 9). SCAN_INTERVAL_MS stays within that budget. The OS may silently
 * return cached results when throttled — still useful, just not always fresh.
 */
class WifiScanService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private lateinit var wifiManager: WifiManager
    // Firestore is only initialized when FIRESTORE_ENABLED is true at build time.
    // When false, uploads are skipped and scan results are reported via broadcasts only.
    private val db: FirebaseFirestore? by lazy {
        if (BuildConfig.FIRESTORE_ENABLED) FirebaseFirestore.getInstance() else null
    }

    private val deviceId: String by lazy {
        val prefs = getSharedPreferences("tracker_prefs", MODE_PRIVATE)
        prefs.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device_id", it).apply()
        }
    }

    private var mode: String = MODE_LIVE
    private var calibrationLabel: String? = null
    private var calibrationX: Double? = null
    private var calibrationY: Double? = null

    private var receiverRegistered = false
    private var lastResultCount = -1
    private var lastResultAt = 0L

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            uploadCurrentScan(freshScan = success)
            updateNotificationForLatestScan()
        }
    }

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        registerReceiver(
            scanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )
        receiverRegistered = true
        startForegroundCompat("Scanning WiFi signal…")
        startScanLoop()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.let {
            mode = it.getStringExtra(EXTRA_MODE) ?: MODE_LIVE
            calibrationLabel = it.getStringExtra(EXTRA_LABEL)
            calibrationX = if (it.hasExtra(EXTRA_X)) it.getDoubleExtra(EXTRA_X, 0.0) else null
            calibrationY = if (it.hasExtra(EXTRA_Y)) it.getDoubleExtra(EXTRA_Y, 0.0) else null
            startForegroundCompat(
                if (mode == MODE_CALIBRATE) "Recording fingerprint…" else "Scanning WiFi signal…"
            )
        }
        return START_STICKY
    }

    private fun startScanLoop() {
        scope.launch {
            while (true) {
                val started = try {
                    @Suppress("DEPRECATION")
                    wifiManager.startScan()
                } catch (e: SecurityException) {
                    sendStatusBroadcast(STATUS_NO_PERMISSION, "Location/WiFi permission missing.")
                    false
                } catch (e: Throwable) {
                    false
                }
                if (!started) {
                    // startScan() returns false when WiFi is off OR when Android throttles
                    // scans (~4 per 2 min since API 28). Either way we just wait for the
                    // next SCAN_RESULTS_AVAILABLE_ACTION broadcast. Only treat it as a
                    // hard failure if WiFi itself is off.
                    if (!wifiManager.isWifiEnabled) {
                        sendStatusBroadcast(STATUS_WIFI_DISABLED, "WiFi is off — enable WiFi in Settings.")
                    }
                }
                delay(SCAN_INTERVAL_MS)
            }
        }
    }

    private fun uploadCurrentScan(freshScan: Boolean) {
        @Suppress("DEPRECATION")
        val results = try {
            wifiManager.scanResults
        } catch (e: SecurityException) {
            sendStatusBroadcast(STATUS_NO_PERMISSION, "Location/WiFi permission missing.")
            return
        }
        if (results == null || results.isEmpty()) {
            sendStatusBroadcast(STATUS_NO_RESULTS, "No networks visible yet.")
            return
        }

        lastResultCount = results.size
        lastResultAt = System.currentTimeMillis()

        val signalMap = results.associate { it.BSSID to it.level }

        val reading = hashMapOf(
            "deviceId" to deviceId,
            "signals" to signalMap,
            "timestamp" to System.currentTimeMillis(),
            "freshScan" to freshScan
        )

        if (mode == MODE_CALIBRATE && calibrationLabel != null && calibrationX != null && calibrationY != null) {
            reading["label"] = calibrationLabel!!
            reading["x"] = calibrationX!!
            reading["y"] = calibrationY!!
            if (BuildConfig.FIRESTORE_ENABLED && db != null) {
                db!!.collection("fingerprints")
                    .add(reading)
                    .addOnSuccessListener {
                        sendStatusBroadcast(
                            STATUS_FINGERPRINT_SAVED,
                            "Saved fingerprint '${calibrationLabel}' at (${calibrationX}, ${calibrationY})."
                        )
                    }
                    .addOnFailureListener { e ->
                        sendStatusBroadcast(STATUS_FIRESTORE_ERROR, "Firestore write failed: ${e.message}")
                    }
            } else {
                sendStatusBroadcast(
                    STATUS_FINGERPRINT_SAVED,
                    "Fingerprint captured locally (${results.size} networks) — cloud upload disabled in this build."
                )
            }
            mode = MODE_LIVE
            calibrationLabel = null
            calibrationX = null
            calibrationY = null
            startForegroundCompat("Fingerprint saved — back to live scanning.")
        } else {
            if (BuildConfig.FIRESTORE_ENABLED && db != null) {
                db!!.collection("liveReadings").document(deviceId)
                    .set(reading)
                    .addOnSuccessListener {
                        sendStatusBroadcast(
                            STATUS_LIVE_UPLOADED,
                            "Live reading uploaded: ${results.size} networks${if (freshScan) " (fresh)" else " (cached)"}."
                        )
                    }
                    .addOnFailureListener { e ->
                        sendStatusBroadcast(STATUS_FIRESTORE_ERROR, "Firestore write failed: ${e.message}")
                    }
            } else {
                sendStatusBroadcast(
                    STATUS_LIVE_UPLOADED,
                    "Live scan captured: ${results.size} networks${if (freshScan) " (fresh)" else " (cached)"} — cloud upload disabled in this build."
                )
            }
        }
    }

    private val notificationManager: NotificationManager by lazy {
        getSystemService(NotificationManager::class.java)
    }

    private fun updateNotificationForLatestScan() {
        val text = if (lastResultCount >= 0) {
            "Last scan: $lastResultCount networks · ${ageSeconds(lastResultAt)}s ago"
        } else {
            "Scanning WiFi signal…"
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun ageSeconds(ts: Long): Long = if (ts == 0L) 0 else (System.currentTimeMillis() - ts) / 1000

    private fun startForegroundCompat(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
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

    private fun sendStatusBroadcast(status: String, message: String) {
        val intent = Intent(ACTION_STATUS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_MESSAGE, message)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(scanReceiver)
            receiverRegistered = false
        }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val SCAN_INTERVAL_MS = 20_000L
        const val NOTIFICATION_ID = 1001

        const val EXTRA_MODE = "mode"
        const val EXTRA_LABEL = "label"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val MODE_LIVE = "live"
        const val MODE_CALIBRATE = "calibrate"

        const val ACTION_STATUS = "com.indoortracker.app.SCAN_STATUS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_MESSAGE = "message"

        const val STATUS_LIVE_UPLOADED = "live_uploaded"
        const val STATUS_FINGERPRINT_SAVED = "fp_saved"
        const val STATUS_NO_RESULTS = "no_results"
        const val STATUS_WIFI_DISABLED = "wifi_disabled"
        const val STATUS_NO_PERMISSION = "no_permission"
        const val STATUS_FIRESTORE_ERROR = "firestore_error"
    }
}
