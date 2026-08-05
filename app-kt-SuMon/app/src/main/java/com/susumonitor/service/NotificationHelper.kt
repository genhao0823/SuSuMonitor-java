package com.susumonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.susumonitor.MainActivity
import com.susumonitor.R
import com.susumonitor.data.model.AlertLevelValues
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 通知管理：两个渠道（常驻监控 / 告警），告警渠道 IMPORTANCE_HIGH。
 * 按 skill 规范 §6：NotificationChannel + 点击通知跳转告警页。
 */
@Singleton
class NotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        const val CHANNEL_MONITOR = "monitor"
        const val CHANNEL_ALERT = "alert"

        private const val NOTIFICATION_ID_ALERT_BASE = 2000

        /** 前台服务常驻通知 ID（Service 与 Helper 共享）。 */
        const val NOTIFICATION_ID_FOREGROUND = 1001

        /** 告警通知 Action：跳转告警列表。 */
        const val ACTION_OPEN_ALERTS = "com.susumonitor.action.OPEN_ALERTS"
    }

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** 初始化通知渠道（幂等）。 */
    fun createChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val monitorChannel = NotificationChannel(
                CHANNEL_MONITOR,
                context.getString(R.string.channel_monitor_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.channel_monitor_desc)
                setShowBadge(false)
            }
            val alertChannel = NotificationChannel(
                CHANNEL_ALERT,
                context.getString(R.string.channel_alert_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.channel_alert_desc)
            }
            notificationManager.createNotificationChannel(monitorChannel)
            notificationManager.createNotificationChannel(alertChannel)
        }
    }

    /**
     * 前台服务常驻通知（LOW 优先级）。
     * @param serverCount 当前订阅的服务器数量
     */
    fun buildForegroundNotification(serverCount: Int): Notification {
        val pendingIntent = alertsPendingIntent()
        return NotificationCompat.Builder(context, CHANNEL_MONITOR)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_monitor_title))
            .setContentText(
                context.getString(R.string.notification_monitor_text, serverCount),
            )
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /** 发布前台服务通知（更新服务器计数）。 */
    fun showForegroundNotification(serverCount: Int) {
        notificationManager.notify(
            NOTIFICATION_ID_FOREGROUND,
            buildForegroundNotification(serverCount),
        )
    }

    /** 取消前台服务通知。 */
    fun cancelForegroundNotification() {
        notificationManager.cancel(NOTIFICATION_ID_FOREGROUND)
    }

    /**
     * 发布告警通知（IMPORTANCE_HIGH）。
     * 标题带级别，正文为 指标 当前值/阈值。
     */
    fun showAlertNotification(
        serverName: String,
        metricLabel: String,
        currentValue: String,
        thresholdValue: String,
        level: String,
    ) {
        val levelLabel = if (level == AlertLevelValues.CRITICAL) "严重" else "警告"
        val title = "$levelLabel · $serverName"
        val text = "$metricLabel 当前 $currentValue，阈值 $thresholdValue"

        val notification = NotificationCompat.Builder(context, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(alertsPendingIntent())
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID_ALERT_BASE + System.currentTimeMillis().toInt().mod(1000), notification)
    }

    private fun alertsPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_ALERTS
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
