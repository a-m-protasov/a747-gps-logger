package io.github.a_m_protasov.a747.device

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.a_m_protasov.a747.A747App
import io.github.a_m_protasov.a747.DeviceState
import io.github.a_m_protasov.a747.R
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Keeps a ~3.5 min download alive with the screen off (foreground service + progress). */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val repo = A747App.repo
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.downloading_from_the_logger), NotificationManager.IMPORTANCE_LOW))
        fun note(progress: Float?) = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(getString(R.string.downloading_from_the_logger))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        ServiceCompat.startForeground(this, ID, note(0f), type)
        val erase = intent?.getBooleanExtra(EXTRA_ERASE, false) ?: false
        val watcher = repo.scope.launch {
            repo.device.collectLatest { d -> if (d is DeviceState.Busy) nm.notify(ID, note(d.progress)) }
        }
        repo.scope.launch {
            repo.download(erase)
            watcher.cancel()
            ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "download"
        private const val ID = 1
        private const val EXTRA_ERASE = "erase"

        fun start(ctx: Context, erase: Boolean) {
            ctx.startForegroundService(Intent(ctx, DownloadService::class.java).putExtra(EXTRA_ERASE, erase))
        }
    }
}
