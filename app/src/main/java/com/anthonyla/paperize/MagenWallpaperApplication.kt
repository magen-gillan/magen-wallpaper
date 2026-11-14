package com.anthonyla.paperize

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.DataResetManager
import com.anthonyla.paperize.presentation.crash.CrashActivity
import com.anthonyla.paperize.presentation.crash.GlobalExceptionHandler
import com.anthonyla.paperize.service.wallpaper.WallpaperChangeService
import com.anthonyla.paperize.service.worker.AlbumRefreshScheduler
import com.anthonyla.paperize.service.worker.WallpaperScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Application class for MagenWallpaper
 *
 * Annotated with @HiltAndroidApp to enable dependency injection
 * Implements Configuration.Provider for WorkManager with Hilt support
 */
@HiltAndroidApp
class MagenWallpaperApplication : Application(), Configuration.Provider, DefaultLifecycleObserver {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var wallpaperScheduler: WallpaperScheduler

    private val lifecycleScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Dynamic receiver for screen-unlock events. Honor / Huawei / Xiaomi and other OEMs with
     * aggressive battery management frequently block the manifest-declared [ScreenEventReceiver]
     * from cold-starting the process on ACTION_USER_PRESENT when the app is in the background.
     *
     * A dynamic receiver registered from the Application survives as long as the app process is
     * alive (including when the app is in the background but not yet killed by the OEM). It is the
     * most reliable path for "change on unlock" on these devices.
     *
     * The manifest-declared ScreenEventReceiver remains as a fallback for the cold-start case
     * (process killed by OEM, user unlocks → system tries to start the receiver). Both paths
     * route into [WallpaperChangeService] with [WallpaperChangeService.ACTION_CHANGE_WALLPAPER_FAST],
     * and the service's deduplication logic prevents double-applies when both fire.
     */
    private var unlockReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        // Install the global crash handler FIRST, before any code that might throw — so even
        // exceptions during Hilt injection or WorkManager bootstrap are surfaced to the user
        // via CrashActivity instead of a silent crash-to-home.
        Thread.setDefaultUncaughtExceptionHandler(
            GlobalExceptionHandler(applicationContext, CrashActivity::class.java)
        )
        super<Application>.onCreate()

        // Perform one-time data reset for major version upgrades (e.g., v3 -> v4)
        // Must run before any other initialization that accesses DB/preferences
        DataResetManager.performResetIfNeeded(this)

        // Create notification channel (minSdk is 31, so always supported)
        createNotificationChannel()

        // Register a dynamic receiver for screen-unlock events. This works while the app process
        // is alive (even in the background), which is the common case on Honor devices where the
        // manifest-declared receiver is frequently blocked by the OEM's battery management.
        registerUnlockReceiver()

        // Process lifecycle distinguishes real background/foreground transitions from activity
        // recreation, so folder-backed albums are refreshed whenever the user returns to the app.
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /**
     * Register [unlockReceiver] for ACTION_USER_PRESENT and ACTION_SCREEN_ON.
     *
     * ACTION_USER_PRESENT fires after the user unlocks (most precise trigger).
     * ACTION_SCREEN_ON fires when the screen turns on (before unlock) — added as a secondary
     * trigger because some OEMs deliver one but not the other.
     *
     * RECEIVER_EXPORTED is required on API 34+ for receivers that accept system broadcasts;
     * it is safe here because both actions are protected system broadcasts that no third-party
     * app can send.
     */
    private fun registerUnlockReceiver() {
        if (unlockReceiver != null) return
        unlockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                        try {
                            val serviceIntent = Intent(context, WallpaperChangeService::class.java).apply {
                                action = WallpaperChangeService.ACTION_CHANGE_WALLPAPER_FAST
                                putExtra(WallpaperChangeService.EXTRA_SCREEN_TYPE, ScreenType.BOTH.name)
                            }
                            ContextCompat.startForegroundService(context, serviceIntent)
                        } catch (e: Exception) {
                            android.util.Log.e(TAG, "Dynamic unlock receiver failed to start service", e)
                        }
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            Constants.NOTIFICATION_CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
            ?: error("NotificationManager system service not available")
        notificationManager.createNotificationChannel(channel)
    }

    override fun onStart(owner: LifecycleOwner) {
        AlbumRefreshScheduler.enqueue(this)
        lifecycleScope.launch {
            runCatching { wallpaperScheduler.reschedulePersistedSchedules() }
                .onFailure { error ->
                    android.util.Log.e(TAG, "Unable to restore wallpaper schedules", error)
                }
        }
    }

    override fun onTerminate() {
        // Unregister the dynamic unlock receiver to avoid leaking it across process reuse.
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
        // Cancel any in-flight application-scoped coroutine work to avoid leaking the scope
        // across emulator/test harness process reuse. (onTerminate is only invoked on real
        // devices and emulators in the foreground process; it is not called when the OS kills
        // the process to reclaim memory — but cancelling here is still correct hygiene.)
        lifecycleScope.cancel()
        super.onTerminate()
    }

    companion object {
        private const val TAG = "MagenWallpaperApplication"
    }
}
