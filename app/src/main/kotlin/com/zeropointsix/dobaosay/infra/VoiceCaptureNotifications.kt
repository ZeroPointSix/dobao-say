package com.zeropointsix.dobaosay.infra

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zeropointsix.dobaosay.MainActivity
import com.zeropointsix.dobaosay.R
import com.zeropointsix.dobaosay.session.VoiceCaptureService

object VoiceCaptureNotifications {
    const val CHANNEL_ID = "voice_capture"
    const val NOTIFICATION_ID = 10086

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val existing = manager.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "语音录音",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "显示 dobao-say 正在使用麦克风"
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        title: String,
        text: String,
        showStopAction: Boolean,
    ): Notification {
        ensureChannel(context)
        val contentIntent =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }

        builder
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_mic_notification)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)

        if (showStopAction) {
            val stopIntent =
                Intent(context, VoiceCaptureService::class.java).setAction(VoiceCaptureService.ACTION_STOP)
            val stopPending =
                PendingIntent.getService(
                    context,
                    1,
                    stopIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            builder.addAction(Notification.Action.Builder(null, "停止", stopPending).build())
        }
        return builder.build()
    }
}
