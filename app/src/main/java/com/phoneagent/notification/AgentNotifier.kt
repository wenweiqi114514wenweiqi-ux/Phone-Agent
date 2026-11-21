package com.phoneagent.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.phoneagent.MainActivity
import com.phoneagent.PhoneAgentApp

/**
 * Agent 通知：ask_user 等待用户回复时，若用户在应用外，发常驻通知
 * （OPPO ColorOS 上会以流体云/横幅形态呈现），点击回到应用。
 */
object AgentNotifier {
    private const val CHANNEL_ID = "agent_waiting"
    private const val NOTIF_ID = 1001

    private val context: Context get() = PhoneAgentApp.instance

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AI 等待回复",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "AI 正在等待你选择或输入回复"
                setShowBadge(true)
            }
            mgr.createNotificationChannel(channel)
        }
    }

    /** 发出"AI 正在等待回复"通知。 */
    fun notifyWaiting(question: String) {
        try {
            ensureChannel()
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pending = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("AI 正在等待回复")
                .setContentText(question)
                .setStyle(NotificationCompat.BigTextStyle().bigText(question))
                .setContentIntent(pending)
                .setAutoCancel(false)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.notify(NOTIF_ID, notification)
        } catch (_: Exception) {
            // 无通知权限时静默失败，不影响应用内交互
        }
    }

    fun cancelWaiting() {
        try {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.cancel(NOTIF_ID)
        } catch (_: Exception) {
        }
    }
}
