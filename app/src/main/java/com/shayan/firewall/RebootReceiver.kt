package com.shayan.firewall

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat

class RebootReceiver : BroadcastReceiver() {

    private val TAG = "RebootReceiver"
    private val CHANNEL_ID = "FirewallRebootReminderChannel"
    private val NOTIFICATION_ID = 1001

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d(TAG, "Boot completed event received.")
            
            val prefs = FirewallPreferences(context)
            
            if (prefs.isRebootReminderEnabled()) {
                Log.d(TAG, "Reboot reminder is enabled. Sending notification.")
                sendNotification(context)
            } else {
                Log.d(TAG, "Reboot reminder is disabled. No notification sent.")
            }
        }
    }

    private fun sendNotification(context: Context) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.reboot_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Reminds user to re-apply firewall rules after reboot."
            }
            notificationManager.createNotificationChannel(channel)
        }

        // Intent to open MainActivity when notification is tapped
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        
        val pendingIntent: PendingIntent = PendingIntent.getActivity(
            context, 0, launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        // Build the notification
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(context.getString(R.string.reboot_notification_title))
            .setContentText(context.getString(R.string.reboot_notification_text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // Dismiss notification on tap

        // Show the notification
        try {
             notificationManager.notify(NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            
            Log.e(TAG, "Failed to send notification. Permission likely revoked.", e)
        }
    }
}

