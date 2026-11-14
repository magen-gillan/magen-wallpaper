package com.anthonyla.paperize

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.DataResetManager
import com.anthonyla.paperize.presentation.crash.CrashActivity
import com.anthonyla.paperize.presentation.crash.GlobalExceptionHandler
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

        // Unlock-triggered changes are handled by the manifest-declared
        // ScreenEventReceiver (see AndroidManifest.xml). ACTION_USER_PRESENT is
        // delivered to manifest receivers, which lets the system cold-start the
        // process after OEM task killers removed it; a dynamically registered
        // receiver would die with the process and silently drop changes.

        // Process lifecycle distinguishes real background/foreground transitions from activity
        // recreation, so folder-backed albums are refreshed whenever the user returns to the app.
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
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
                    android.util.Log.e("MagenWallpaperApplication", "Unable to restore wallpaper schedules", error)
                }
        }
    }

    override fun onTerminate() {
        // Cancel any in-flight application-scoped coroutine work to avoid leaking the scope
        // across emulator/test harness process reuse. (onTerminate is only invoked on real
        // devices and emulators in the foreground process; it is not called when the OS kills
        // the process to reclaim memory — but cancelling here is still correct hygiene.)
        lifecycleScope.cancel()
        super.onTerminate()
    }
}
