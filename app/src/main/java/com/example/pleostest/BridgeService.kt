package com.example.pleostest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import com.example.pleostest.bridge.BridgeSession
import com.example.pleostest.bridge.BridgeSessionHost
import com.example.pleostest.bridge.BridgeStatus
import com.example.pleostest.bridge.DoorBridgeServer
import com.example.pleostest.bridge.ReaderBridgeGateway
import com.example.pleostest.vehicle.DoorPositionReader
import com.example.pleostest.vehicle.DoorScheduler
import com.example.pleostest.vehicle.PleosVehicleSdk
import com.example.pleostest.vehicle.VehicleStatusReader
import java.util.concurrent.Executors

/** Main-looper scheduler shared by the reader and the bridge gateway of one session. */
class MainLooperScheduler : DoorScheduler {
    private val handler = Handler(Looper.getMainLooper())
    override fun nowMillis() = SystemClock.elapsedRealtime()
    override fun execute(action: () -> Unit) { handler.post { action() } }
    override fun after(delayMillis: Long, action: () -> Unit): () -> Unit {
        val task = Runnable(action)
        handler.postDelayed(task, delayMillis)
        return { handler.removeCallbacks(task) }
    }
}

/**
 * Thin Android shell: keeps the process in the foreground and delegates the session to
 * [BridgeSessionHost]. Never sticky; it is not restarted after the process dies.
 */
class BridgeService : Service() {
    inner class LocalBinder : Binder() {
        val service: BridgeService get() = this@BridgeService
    }

    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private val scheduler = MainLooperScheduler()
    private val host = BridgeSessionHost(::createSession)

    /** Reader of the live session, or null when no session is running. */
    val reader: DoorPositionReader? get() = host.current?.reader

    var status: BridgeStatus = BridgeStatus.Stopped
        private set
    var statusListener: ((BridgeStatus) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            requestStop()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        host.start()
        return START_NOT_STICKY
    }

    /** Notification action and screen button. Idempotent. */
    fun requestStop() {
        host.stop()
        setStatus(BridgeStatus.Stopped)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        host.stop()
        super.onDestroy()
    }

    private fun createSession(): BridgeSession {
        // The screen button and every AI request share this one reader, its slot and its SDK owner.
        val statusReader = VehicleStatusReader(PleosVehicleSdk(this), scheduler)
        val gateway = ReaderBridgeGateway(statusReader, scheduler)
        val server = DoorBridgeServer(gateway, SystemClock::elapsedRealtime)
        return BridgeSession(DoorPositionReader(statusReader), gateway::close, server, bindExecutor) { session, next ->
            main.post { if (next !is BridgeStatus.Stopped && session === host.current) setStatus(next) }
        }
    }

    private fun setStatus(next: BridgeStatus) {
        status = next
        statusListener?.invoke(next)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1,
            Intent(this, BridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.notification_stop), stop).build())
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.example.pleostest.action.STOP_BRIDGE"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1

        // Shared across service instances so a new session binds only after the previous
        // session's bind has finished and released the port.
        private val bindExecutor = Executors.newSingleThreadExecutor {
            Thread(it, "door-bridge-bind").apply { isDaemon = true }
        }
    }
}
