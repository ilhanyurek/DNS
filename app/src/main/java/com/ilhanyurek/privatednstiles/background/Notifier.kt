package com.ilhanyurek.privatednstiles.background

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.ilhanyurek.privatednstiles.MainActivity
import com.ilhanyurek.privatednstiles.R

/**
 * Tells the user about Private DNS changes the app made on its own (automatic
 * fallback). Uses a notification when allowed, otherwise a toast.
 * Call on the main thread.
 */
object Notifier {

    private const val CHANNEL_ID = "automatic_changes"
    private const val NOTIFICATION_ID = 1

    fun show(context: Context, text: String) {
        val app = context.applicationContext
        if (!canNotify(app)) {
            Toast.makeText(app, text, Toast.LENGTH_LONG).show()
            return
        }
        app.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Automatic DNS changes", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "When the app switches Private DNS by itself" }
        )
        val open = PendingIntent.getActivity(
            app, 0,
            Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Private DNS")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        post(app, notification)
    }

    private fun canNotify(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    // Only called after canNotify() has confirmed the permission.
    @SuppressLint("MissingPermission")
    private fun post(context: Context, notification: Notification) {
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
