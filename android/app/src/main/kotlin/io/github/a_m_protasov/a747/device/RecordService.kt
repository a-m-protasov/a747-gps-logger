package io.github.a_m_protasov.a747.device

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.a_m_protasov.a747.A747App
import io.github.a_m_protasov.a747.MainActivity
import io.github.a_m_protasov.a747.R
import io.github.a_m_protasov.a747.core.LogPoint
import io.github.a_m_protasov.a747.core.PointsFile
import io.github.a_m_protasov.a747.core.Tracks
import io.github.a_m_protasov.a747.ui.UI
import java.io.File
import java.io.FileWriter

/**
 * Records a track with this phone's GPS into the library (tracks/phone-<start>.csv), one point
 * every [io.github.a_m_protasov.a747.Repo.recordIntervalS] seconds. Each point is written at once,
 * so a recording survives the app being killed. Started and stopped from the app, the
 * notification, or other apps such as Tasker (see [RecordControlActivity]).
 */
class RecordService : Service(), LocationListener {
    private val repo get() = A747App.repo
    private var writer: FileWriter? = null
    private var file: File? = null
    private var last: LogPoint? = null
    private var count = 0
    private var distance = 0.0
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.recording_a_track), NotificationManager.IMPORTANCE_LOW))
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0
        try {
            ServiceCompat.startForeground(this, ID, note(), type)
        } catch (e: Exception) {
            // no location permission, or started from the background (see RecordControlActivity)
            repo.message.value = getString(R.string.location_access_is_needed_to_record_a_track)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasPermission(this)) { stop(); return START_NOT_STICKY }
        started = true
        repo.recording.value = io.github.a_m_protasov.a747.Repo.Recording(null)
        requestUpdates()
        return START_NOT_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun requestUpdates() {
        val lm = getSystemService(LocationManager::class.java)
        lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, repo.recordIntervalS * 1000L, 0f, this, Looper.getMainLooper())
    }

    private fun note() = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_menu_mylocation)
        .setContentTitle(getString(R.string.recording_a_track))
        .setContentText(if (count == 0) getString(R.string.waiting_for_gps) else
            "${UI.distance(distance)} · ${getString(R.string.points)}: $count")
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
        .addAction(0, getString(R.string.stop_recording),
            PendingIntent.getService(this, 1, Intent(this, RecordService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE))
        .build()

    override fun onLocationChanged(l: Location) {
        val utc = l.time / 1000
        val prev = last
        if (prev != null && utc <= prev.utc) return
        // a fix this poor is noise rather than a position
        if (l.hasAccuracy() && l.accuracy > 100) return
        val p = LogPoint(utc, l.latitude, l.longitude,
            height = if (l.hasAltitude()) l.altitude else null,
            speed = if (l.hasSpeed()) l.speed * 3.6 else null)
        val w = writer ?: run {
            val f = repo.library.newRecording(utc)
            file = f
            FileWriter(f, true).also { writer = it }
        }
        w.write(PointsFile.row('T', p))
        w.flush()
        if (prev != null) distance += Tracks.haversine(prev, p)
        last = p
        count++
        repo.recording.value = io.github.a_m_protasov.a747.Repo.Recording(file, count, distance, file?.let { startUtc ?: utc }, p)
        getSystemService(NotificationManager::class.java).notify(ID, note())
    }

    private val startUtc get() = repo.recording.value?.startUtc

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun stop() {
        getSystemService(LocationManager::class.java).removeUpdates(this)
        runCatching { writer?.close() }
        writer = null
        started = false
        repo.recording.value = null
        repo.reload()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (started) stop()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "record"
        private const val ID = 2
        const val ACTION_STOP = "stop"

        fun hasPermission(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

        /** Must be called while one of the app's screens is visible (Android allows location only then). */
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, RecordService::class.java))

        fun stop(ctx: Context) {
            if (A747App.repo.recording.value != null) ctx.startService(Intent(ctx, RecordService::class.java).setAction(ACTION_STOP))
        }

        val permissions = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
}

/**
 * Start, stop or toggle recording from other apps, e.g. Tasker's "Send Intent" with the action
 * below and target "Activity". It is an invisible screen: Android lets an app start location
 * recording only while it shows something, and it asks for the location permission if needed.
 */
class RecordControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Tasker often fires with the screen locked: show (invisibly) over the lock screen so
        // Android counts the app as visible and allows location recording
        if (Build.VERSION.SDK_INT >= 27) setShowWhenLocked(true)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val recording = A747App.repo.recording.value != null
        when (intent?.action) {
            ACTION_STOP -> { RecordService.stop(this); finish() }
            ACTION_START -> if (recording) finish() else startRecording()
            ACTION_TOGGLE -> if (recording) { RecordService.stop(this); finish() } else startRecording()
            else -> finish()
        }
    }

    private fun startRecording() {
        if (RecordService.hasPermission(this)) {
            RecordService.start(this)
            finish()
        } else if (getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) {
            // the permission dialog cannot appear over the lock screen and would stay stuck there
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.recording_a_track), NotificationManager.IMPORTANCE_LOW))
            nm.notify(ID, android.app.Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(getString(R.string.location_access_is_needed_to_record_a_track))
                .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, RecordControlActivity::class.java).setAction(ACTION_START),
                    PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build())
            finish()
        } else requestPermissions(RecordService.permissions, 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (RecordService.hasPermission(this)) RecordService.start(this)
        else A747App.repo.message.value = getString(R.string.location_access_is_needed_to_record_a_track)
        finish()
    }

    companion object {
        const val ACTION_START = "io.github.a_m_protasov.a747.action.START_RECORDING"
        const val ACTION_STOP = "io.github.a_m_protasov.a747.action.STOP_RECORDING"
        const val ACTION_TOGGLE = "io.github.a_m_protasov.a747.action.TOGGLE_RECORDING"
        private const val CHANNEL = "record"
        private const val ID = 3
    }
}
