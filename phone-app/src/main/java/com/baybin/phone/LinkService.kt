package com.baybin.phone

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the glasses link (and with it the recognition pipeline) alive while the phone is in a
 * pocket: a foreground service of type connectedDevice that turns on auto-reconnect.
 * Started by the main screen once the Bluetooth permissions are granted.
 */
class LinkService : Service() {

    /** Bluetooth switched back on: reconnect now instead of waiting for the next retry. */
    private val bluetoothOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) == BluetoothAdapter.STATE_ON) {
                (application as App).link.autoReconnect = true
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "BayBin", NotificationManager.IMPORTANCE_LOW))
        try {
            val n = notification()
            if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            else startForeground(ID, n)
        } catch (e: Exception) { // e.g. Bluetooth permission revoked
            EventLog.add("foreground service refused: ${e.message}")
            stopSelf()
            return
        }
        (application as App).link.autoReconnect = true
        registerReceiver(bluetoothOn, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        EventLog.add("link service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java).notify(ID, notification())
        return START_STICKY
    }

    private fun notification(): Notification {
        val app = application as App
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(Lang.s(app.lang, "title"))
            .setContentText(Lang.s(app.lang, "notify"))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        try { unregisterReceiver(bluetoothOn) } catch (_: IllegalArgumentException) {}
        (application as App).link.autoReconnect = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "link"
        private const val ID = 1
    }
}
