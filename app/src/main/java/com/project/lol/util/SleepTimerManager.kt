package com.project.lol.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.project.lol.service.MediaNotificationService
import com.project.lol.service.OfflineMediaService

/**
 * True sleep timer backed by AlarmManager: survives process death and Doze.
 *
 * Manifest entries required (add inside <application> + permissions):
 * ```
 * <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
 * <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
 * <receiver android:name=".util.SleepTimerManager$SleepTimerReceiver" android:exported="false" />
 * <receiver android:name=".util.SleepTimerManager$BootReceiver" android:exported="true">
 *     <intent-filter>
 *         <action android:name="android.intent.action.BOOT_COMPLETED" />
 *     </intent-filter>
 * </receiver>
 * ```
 */
object SleepTimerManager {
    private const val TAG = "SleepTimer"
    const val KEY_SLEEP_TIMER_END_MS = "sleep_timer_end_ms"
    const val ACTION_SLEEP_TIMER = "com.project.lol.ACTION_SLEEP_TIMER"
    private const val REQUEST_CODE = 0x5EE9

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("spotilol_prefs", Context.MODE_PRIVATE)

    private fun timerIntent(context: Context): Intent =
        Intent(context, SleepTimerReceiver::class.java).setAction(ACTION_SLEEP_TIMER)

    private fun timerPendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context.applicationContext,
            REQUEST_CODE,
            timerIntent(context),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    /** Arm a one-shot timer [minutes] from now. Returns the absolute end time ms. */
    @JvmStatic
    fun setTimer(context: Context, minutes: Int): Long {
        val app = context.applicationContext
        val endMs = System.currentTimeMillis() + minutes * 60_000L
        prefs(app).edit().putLong(KEY_SLEEP_TIMER_END_MS, endMs).apply()
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = timerPendingIntent(app)
        runCatching {
            // ponytail: setExactAndAllowWhileIdle survives Doze; plain setExact() if exact-while-idle ever misbehaves.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endMs, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, endMs, pi)
            }
        }.onFailure { e ->
            // SCHEDULE_EXACT_ALARM denied on S+: fall back to inexact.
            Logger.w(TAG, "setExact failed, using inexact: ${e.message}")
            am.set(AlarmManager.RTC_WAKEUP, endMs, pi)
        }
        Logger.i(TAG, "sleep timer armed: ${minutes}min (ends $endMs)")
        return endMs
    }

    @JvmStatic
    fun cancel(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        runCatching { am?.cancel(timerPendingIntent(app)) }
        prefs(app).edit().remove(KEY_SLEEP_TIMER_END_MS).apply()
        Logger.i(TAG, "sleep timer cancelled")
    }

    @JvmStatic
    fun isActive(context: Context): Boolean = remainingMs(context) > 0

    @JvmStatic
    fun remainingMs(context: Context): Long {
        val endMs = prefs(context).getLong(KEY_SLEEP_TIMER_END_MS, 0L)
        if (endMs <= 0L) return 0L
        return (endMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** Re-arm the alarm after reboot / process death. Returns false when nothing to restore. */
    @JvmStatic
    fun rescheduleIfNeeded(context: Context): Boolean {
        val app = context.applicationContext
        val endMs = prefs(app).getLong(KEY_SLEEP_TIMER_END_MS, 0L)
        if (endMs <= 0L) return false
        val remaining = endMs - System.currentTimeMillis()
        if (remaining <= 0L) {
            prefs(app).edit().remove(KEY_SLEEP_TIMER_END_MS).apply()
            return false
        }
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = timerPendingIntent(app)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, endMs, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, endMs, pi)
            }
        }.onFailure { am.set(AlarmManager.RTC_WAKEUP, endMs, pi) }
        Logger.i(TAG, "sleep timer restored, ${remaining / 60_000}min left")
        return true
    }

    /** Fires when the timer elapses: stop every playback path, clear the pref. */
    class SleepTimerReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_SLEEP_TIMER) return
            Logger.i(TAG, "sleep timer elapsed, stopping playback")
            context.getSharedPreferences("spotilol_prefs", Context.MODE_PRIVATE)
                .edit().remove(KEY_SLEEP_TIMER_END_MS).apply()
            // Web playback: pause via the bound WebView on the main thread.
            runCatching {
                Handler(Looper.getMainLooper()).post {
                    runCatching {
                        MediaNotificationService.webView?.evaluateJavascript("actPlayPause(false)", null)
                    }
                }
            }
            // Offline playback path.
            runCatching {
                context.startService(
                    Intent(context, OfflineMediaService::class.java)
                        .setAction(OfflineMediaService.ACTION_STOP),
                )
            }
        }
    }

    /** Restore the timer after reboot. */
    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
            Logger.i(TAG, "boot completed, restoring sleep timer")
            runCatching { rescheduleIfNeeded(context) }
        }
    }
}
