package com.novatv.app.boot

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.PowerManager
import com.novatv.app.MainActivity
import com.novatv.app.app

/**
 * Settings › General › "Auto start app on wake up from sleep mode" and
 * Settings › Other › Reminders › "Wake up from sleep mode". Both "may not work on all devices"
 * (like TiviMate): some TVs don't let apps turn the screen on or start from the background.
 */
object WakeAlarms {
    private const val REQ = 4711

    /** Registered by the app at start: the screen coming back on opens the app if the setting is on. */
    fun registerScreenOn(context: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (c.app.lastSettings?.bool("general.wake_start") == true) openApp(c)
            }
        }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_ON), Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(r, IntentFilter(Intent.ACTION_SCREEN_ON))
        }
    }

    /** Wakes the device for the next reminder (if "Wake up from sleep mode" is on). */
    fun scheduleNextReminder(context: Context) {
        val app = context.app
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = PendingIntent.getBroadcast(context, REQ, Intent(context, ReminderWakeReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val s = app.lastSettings
        val next = app.reminders.items.value.minByOrNull { it.start }
        if (s == null || !s.premium || !s.bool("reminders.wake") || next == null) { am.cancel(pi); return }
        val at = next.start - s.int("epg.reminder_before") * 60_000L
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    fun openApp(c: Context) {
        runCatching {
            c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }
    }
}

class ReminderWakeReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "KingVegasTV:reminder").acquire(15_000)
        }
        WakeAlarms.openApp(context)
    }
}
