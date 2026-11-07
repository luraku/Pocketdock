package dev.androdock.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.androdock.AndrodockApp
import dev.androdock.MainActivity
import dev.androdock.R
import dev.androdock.vm.EngineState
import kotlinx.coroutines.launch

/**
 * Service de premier plan qui maintient la VM en vie quand l'application
 * passe en arrière-plan (sinon Android tuerait le processus QEMU).
 */
class EngineService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val app get() = application as AndrodockApp

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                val st = app.vm.status.value.state
                if (st == EngineState.STOPPED || st == EngineState.ERROR) stopSelf() else {
                    app.vm.stop()
                    wasActive = true
                    observe()
                }
            }
            else -> {
                goForeground(app.vm.status.value.message)
                app.vm.start() // passe l'état à PREPARING de façon synchrone
                wasActive = true
                observe()
            }
        }
        return START_NOT_STICKY
    }

    private var observing = false
    private var wasActive = false

    private fun observe() {
        if (observing) return
        observing = true
        lifecycleScope.launch {
            app.vm.status.collect { st ->
                when (st.state) {
                    EngineState.STOPPED, EngineState.ERROR -> if (wasActive) {
                        releaseWakeLock()
                        ServiceCompat.stopForeground(this@EngineService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    else -> {
                        wasActive = true
                        if (app.settings.config.value.keepAwake) acquireWakeLock() else releaseWakeLock()
                        val ports = if (st.forwardedPorts.isEmpty()) "" else " · ${st.forwardedPorts.size} port(s)"
                        notify(st.message + ports)
                    }
                }
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, EngineService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, AndrodockApp.CHANNEL_ENGINE)
            .setSmallIcon(R.drawable.ic_stat_engine)
            .setContentTitle("Pocketdock")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Arrêter", stop)
            .build()
    }

    private fun goForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(text), type)
    }

    private fun notify(text: String) {
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Pocketdock::engine")
            .apply { acquire() }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "dev.androdock.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, EngineService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, EngineService::class.java).setAction(ACTION_STOP))
        }
    }
}
