package com.anthonyla.paperize.service.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.service.wallpaper.WallpaperChangeService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Exact elapsed-realtime alarm fallback/primary trigger for wallpaper changes.
 * The alarm is one-shot and schedules its successor before starting the wallpaper service,
 * so a service failure cannot permanently remove the schedule.
 */
@AndroidEntryPoint
class WallpaperAlarmReceiver : BroadcastReceiver() {

    @Inject lateinit var settingsRepository: SettingsRepository

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.ACTION_EXACT_WALLPAPER_ALARM) return

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val settings = settingsRepository.getScheduleSettings()
                val mode = settingsRepository.getWallpaperMode()
                if (!settings.enableChanger) return@launch

                val screenType = intent.getStringExtra(Constants.EXTRA_SCREEN_TYPE)
                    ?.let(ScreenType::fromString)
                    ?: ScreenType.HOME
                val interval = intent.getIntExtra(
                    Constants.EXTRA_INTERVAL_MINUTES,
                    Constants.DEFAULT_INTERVAL_MINUTES
                ).coerceAtLeast(Constants.MIN_INTERVAL_MINUTES)

                val active = when {
                    mode == WallpaperMode.LIVE ->
                        settings.liveAlbumId != null && settings.liveIntervalMinutes > 0
                    screenType == ScreenType.HOME ->
                        settings.homeEnabled && settings.homeAlbumId != null
                    screenType == ScreenType.LOCK ->
                        settings.lockEnabled && settings.lockAlbumId != null
                    screenType == ScreenType.BOTH ->
                        (settings.homeEnabled && settings.homeAlbumId != null) ||
                            (settings.lockEnabled && settings.lockAlbumId != null)
                    else -> false
                }
                if (!active) return@launch

                schedule(context, screenType, interval)
                val serviceIntent = Intent(context, WallpaperChangeService::class.java).apply {
                    action = WallpaperChangeService.ACTION_CHANGE_WALLPAPER_AUTO
                    putExtra(Constants.EXTRA_SCREEN_TYPE, screenType.name)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
                Log.d(TAG, "Started wallpaper service from exact alarm for $screenType")
            } catch (e: Exception) {
                Log.e(TAG, "Exact wallpaper alarm failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WallpaperAlarmReceiver"
        private const val REQUEST_HOME = 5101
        private const val REQUEST_LOCK = 5102
        private const val REQUEST_BOTH = 5103
        private const val REQUEST_LIVE = 5104
        private const val SCHEDULE_PREFS = "wallpaper_alarm_schedule"
        private const val KEY_DUE_PREFIX = "due_elapsed_"
        private const val KEY_BOOT_PREFIX = "boot_count_"

        /** True while the persisted one-shot alarm is still pending on this boot. */
        fun isScheduled(context: Context, screenType: ScreenType): Boolean {
            val preferences = context.getSharedPreferences(SCHEDULE_PREFS, Context.MODE_PRIVATE)
            val bootCount = currentBootCount(context)
            val dueElapsed = preferences.getLong(dueKey(screenType), 0L)
            return isAlarmDeadlinePending(
                savedBootCount = preferences.getInt(bootKey(screenType), -1),
                currentBootCount = bootCount,
                dueElapsed = dueElapsed,
                nowElapsed = SystemClock.elapsedRealtime()
            )
        }

        fun schedule(context: Context, screenType: ScreenType, intervalMinutes: Int) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val exactAllowed = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            val interval = intervalMinutes.coerceAtLeast(Constants.MIN_INTERVAL_MINUTES)
            val pendingIntent = pendingIntent(context, screenType, interval)
            val triggerAt = SystemClock.elapsedRealtime() + interval * 60_000L
            if (exactAllowed) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            } else {
                // This path does not require SCHEDULE_EXACT_ALARM and still wakes the
                // receiver while the device is idle. Honor may add some Doze delay, but
                // the app no longer depends on the UI process or WorkManager execution.
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAt,
                    pendingIntent
                )
            }
            val bootCount = currentBootCount(context)
            if (bootCount >= 0) {
                // Foreground recovery must not restart the interval while this alarm is pending.
                context.getSharedPreferences(SCHEDULE_PREFS, Context.MODE_PRIVATE).edit()
                    .putLong(dueKey(screenType), triggerAt)
                    .putInt(bootKey(screenType), bootCount)
                    .commit()
            }
        }

        fun cancel(context: Context, screenType: ScreenType) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            alarmManager.cancel(pendingIntent(context, screenType, Constants.MIN_INTERVAL_MINUTES))
            context.getSharedPreferences(SCHEDULE_PREFS, Context.MODE_PRIVATE).edit()
                .remove(dueKey(screenType))
                .remove(bootKey(screenType))
                .apply()
        }

        private fun currentBootCount(context: Context): Int = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        } catch (_: RuntimeException) {
            -1
        }

        private fun dueKey(screenType: ScreenType) = KEY_DUE_PREFIX + screenType.name
        private fun bootKey(screenType: ScreenType) = KEY_BOOT_PREFIX + screenType.name

        private fun pendingIntent(
            context: Context,
            screenType: ScreenType,
            intervalMinutes: Int
        ): PendingIntent {
            val intent = Intent(context, WallpaperAlarmReceiver::class.java).apply {
                action = Constants.ACTION_EXACT_WALLPAPER_ALARM
                setPackage(context.packageName)
                putExtra(Constants.EXTRA_SCREEN_TYPE, screenType.name)
                putExtra(Constants.EXTRA_INTERVAL_MINUTES, intervalMinutes)
            }
            return PendingIntent.getBroadcast(
                context,
                requestCode(screenType),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun requestCode(screenType: ScreenType): Int = when (screenType) {
            ScreenType.HOME -> REQUEST_HOME
            ScreenType.LOCK -> REQUEST_LOCK
            ScreenType.BOTH -> REQUEST_BOTH
            ScreenType.LIVE -> REQUEST_LIVE
        }
    }
}

internal fun isAlarmDeadlinePending(
    savedBootCount: Int,
    currentBootCount: Int,
    dueElapsed: Long,
    nowElapsed: Long
): Boolean = savedBootCount >= 0 &&
    savedBootCount == currentBootCount &&
    dueElapsed > nowElapsed
