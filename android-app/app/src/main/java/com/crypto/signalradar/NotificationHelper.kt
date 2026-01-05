package com.crypto.signalradar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class NotificationHelper(private val context: Context) {
  private val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  private var alertId = 2000

  fun ensureChannels() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val foreground = NotificationChannel(
        CHANNEL_FOREGROUND,
        context.getString(R.string.foreground_channel),
        NotificationManager.IMPORTANCE_LOW,
      )
      val alerts = NotificationChannel(
        CHANNEL_ALERTS,
        context.getString(R.string.alert_channel),
        NotificationManager.IMPORTANCE_HIGH,
      )
      manager.createNotificationChannel(foreground)
      manager.createNotificationChannel(alerts)
    }
  }

  fun buildForegroundNotification(text: String): Notification {
    val intent = Intent(context, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
      context,
      0,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    return NotificationCompat.Builder(context, CHANNEL_FOREGROUND)
      .setSmallIcon(android.R.drawable.ic_popup_sync)
      .setContentTitle(context.getString(R.string.app_name))
      .setContentText(text)
      .setContentIntent(pendingIntent)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .build()
  }

  fun notifyAlert(alert: AlertEntry) {
    val intent = Intent(context, MainActivity::class.java)
    val pendingIntent = PendingIntent.getActivity(
      context,
      alertId,
      intent,
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
    val changeText = PercentFormatter.format(alert.changePercent)
    val title = "${alert.symbol} moved $changeText"
    val body = "${formatWindowLabel(alert.windowMinutes)} window | Price ${PriceFormatter.format(alert.price)}"

    val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
      .setSmallIcon(android.R.drawable.stat_notify_more)
      .setContentTitle(title)
      .setContentText(body)
      .setContentIntent(pendingIntent)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setAutoCancel(true)
      .build()

    manager.notify(alertId++, notification)
  }

  private fun formatWindowLabel(minutes: Int): String {
    return when (minutes) {
      60 -> "1h"
      1440 -> "1d"
      10080 -> "1w"
      43200 -> "1mo"
      else -> "${minutes}m"
    }
  }

  companion object {
    const val CHANNEL_FOREGROUND = "signal_foreground"
    const val CHANNEL_ALERTS = "signal_alerts"
  }
}
