package com.clipmax.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.concurrent.Executors

/** Cleans expired temporary cards when due and re-arms the next expiry, including after reboot. */
class CaptureExpiryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        val pending = goAsync()
        worker.execute {
            try {
                val preferences = CapturePreferences.preferences(app)
                CapturePreferences.cleanupExpired(ClipStore(app.filesDir), preferences)
                schedule(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val ACTION_EXPIRE = "com.clipmax.app.action.EXPIRE_CAPTURE_CARDS"
        private const val REQUEST_CODE = 7304
        private val worker = Executors.newSingleThreadExecutor()

        fun schedule(context: Context) {
            val app = context.applicationContext
            val alarm = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(app, CaptureExpiryReceiver::class.java).setAction(ACTION_EXPIRE)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val operation = PendingIntent.getBroadcast(app, REQUEST_CODE, intent, flags)
            val next = CapturePreferences.nextExpiry(CapturePreferences.preferences(app))
            if (next == null) {
                alarm.cancel(operation)
                operation.cancel()
            } else {
                val trigger = next.coerceAtLeast(System.currentTimeMillis() + 1_000L)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
                else alarm.set(AlarmManager.RTC_WAKEUP, trigger, operation)
            }
        }
    }
}
