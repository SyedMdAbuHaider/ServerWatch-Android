package com.serverwatch.app

import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

class MonitoringService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lastHealth = ConcurrentHashMap<String, String>()

    override fun onCreate() {
        super.onCreate()
        createChannels()
        val notification = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("ServerWatch monitoring")
            .setContentText("Monitoring " + Prefs.loadServers(this).size + " server(s)")
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        startForeground(1001, notification)
        scope.launch { loop() }
    }

    private suspend fun loop() {
        while (isActive) {
            val servers = Prefs.loadServers(this@MonitoringService)
            servers.forEach { config ->
                val result = runCatching { Api.fetch(config) }
                val health = result.fold({ it.health }, { "DOWN" })
                val old = lastHealth.put(config.name, health)
                if (health != old && (health == "CRITICAL" || health == "WARNING" || old == "CRITICAL" || old == "WARNING" || old == "DOWN")) {
                    val text = result.fold(
                        { st -> st.hostname + ": CPU " + String.format(Locale.US, "%.0f", st.cpuPercent) + "%, RAM " + String.format(Locale.US, "%.0f", st.ramPercent) + "%, Disk " + String.format(Locale.US, "%.0f", st.disks.maxOfOrNull { it.percent } ?: 0.0) + "%" },
                        { "Agent unreachable: " + (it.message ?: "connection failed") }
                    )
                    notifyAlert(config.name, health, text)
                }
            }
            delay(30_000)
        }
    }

    private fun notifyAlert(server: String, health: String, detail: String) {
        val title = when (health) {
            "CRITICAL" -> "Critical: " + server
            "WARNING" -> "Warning: " + server
            "DOWN" -> "Server down: " + server
            else -> "Recovered: " + server
        }
        val n = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(if (health == "CRITICAL" || health == "DOWN") NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify((server.hashCode() and 0x7fffffff) % 100000 + 2000, n)
    }

    private fun createChannels() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL_STATUS, "Monitoring service", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Server alerts", NotificationManager.IMPORTANCE_HIGH))
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object { const val CHANNEL_STATUS = "serverwatch_status"; const val CHANNEL_ALERTS = "serverwatch_alerts" }
}
