package com.anthonyla.paperize.service.wallpaper

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.WallpaperManager
import android.net.Uri
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.EmptyAlbumException
import com.anthonyla.paperize.core.Result as MagenWallpaperResult
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.core.WallpaperMode
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.core.util.setBitmapChecked
import com.anthonyla.paperize.core.util.setStreamChecked
import com.anthonyla.paperize.domain.model.PreparedWallpaper
import com.anthonyla.paperize.domain.model.ScheduleSettings
import com.anthonyla.paperize.domain.repository.SettingsRepository
import com.anthonyla.paperize.domain.repository.WallpaperRepository
import com.anthonyla.paperize.domain.usecase.ChangeWallpaperUseCase
import com.anthonyla.paperize.domain.usecase.ReapplyEffectsUseCase
import com.anthonyla.paperize.presentation.MainActivity
import com.anthonyla.paperize.service.WallpaperChangeLock
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service for immediate wallpaper changes and effect reapplication.
 */
@AndroidEntryPoint
class WallpaperChangeService : Service() {

    @Inject lateinit var changeWallpaperUseCase: ChangeWallpaperUseCase
    @Inject lateinit var reapplyEffectsUseCase: ReapplyEffectsUseCase
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var wallpaperRepository: WallpaperRepository
    @Inject lateinit var wallpaperChangeLock: WallpaperChangeLock

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var wallpaperManager: WallpaperManager
    private lateinit var notificationManager: NotificationManager

    /**
     * Elapsed-realtime timestamp of the most recent successful fast wallpaper apply.
     *
     * Used to deduplicate unlock-triggered changes: both the manifest-declared
     * [com.anthonyla.paperize.service.alarm.ScreenEventReceiver] and the dynamic receiver
     * registered in [com.anthonyla.paperize.MagenWallpaperApplication] can fire for the same
     * unlock event (especially on Honor/Huawei where the OEM delays delivery), and without
     * deduplication the wallpaper would advance two entries in the queue instead of one.
     *
     * The window is intentionally short (8 seconds) — long enough to absorb the OEM delivery
     * window for a single unlock, short enough to not swallow two genuinely separate unlocks
     * (which the user would expect to produce two distinct wallpaper changes).
     */
    @Volatile
    private var lastFastChangeElapsedMs: Long = 0L
    private val fastChangeDedupWindowMs = 8_000L

    override fun onCreate() {
        super.onCreate()
        wallpaperManager = WallpaperManager.getInstance(this)
        notificationManager = getSystemService(NotificationManager::class.java)
            ?: error("NotificationManager not available")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                Constants.NOTIFICATION_ID,
                createNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(Constants.NOTIFICATION_ID, createNotification())
        }

        val screenType = intent?.getStringExtra(EXTRA_SCREEN_TYPE)
            ?.let(ScreenType::fromString)
            ?: ScreenType.BOTH
        when (intent?.action) {
            ACTION_CHANGE_WALLPAPER -> handleChangeWallpaper(screenType, startId)
            ACTION_CHANGE_WALLPAPER_AUTO ->
                handleChangeWallpaper(screenType, startId, respectWallpaperMode = true)
            ACTION_CHANGE_WALLPAPER_FAST -> handleFastChange(screenType, startId)
            ACTION_REAPPLY_EFFECTS -> handleReapplyEffects(screenType, startId)
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    private fun handleFastChange(screenType: ScreenType, startId: Int) {
        serviceScope.launch {
            wallpaperChangeLock.mutex.withLock {
                // Deduplicate unlock-triggered changes: if a fast change was applied within the
                // last [fastChangeDedupWindowMs], skip this one. This happens when both the
                // dynamic receiver (registered in Application) and the manifest-declared
                // ScreenEventReceiver fire for the same unlock event, or when the OEM delivers
                // ACTION_USER_PRESENT and ACTION_SCREEN_ON in quick succession.
                val now = SystemClock.elapsedRealtime()
                val sinceLast = now - lastFastChangeElapsedMs
                if (lastFastChangeElapsedMs > 0 && sinceLast < fastChangeDedupWindowMs) {
                    Log.d(TAG, "Skipping fast change — only ${sinceLast}ms since last apply (dedup window=${fastChangeDedupWindowMs}ms)")
                    stopSelf(startId)
                    return@withLock
                }
                try {
                    if (settingsRepository.getWallpaperMode() == WallpaperMode.STATIC) {
                        fastChange(screenType, settingsRepository.getScheduleSettings())
                        lastFastChangeElapsedMs = SystemClock.elapsedRealtime()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Fast wallpaper change failed", e)
                } finally {
                    stopSelf(startId)
                }
            }
        }
    }

    /**
     * Unlock-only fast path. It applies the next source URI directly and deliberately skips
     * expensive decode/effect processing; the regular scheduled path still preserves effects.
     */
    private suspend fun fastChange(screenType: ScreenType, settings: ScheduleSettings) {
        val homeAlbumId = settings.homeAlbumId.takeIf {
            settings.homeEnabled && screenType != ScreenType.LOCK
        }
        val lockAlbumId = settings.lockAlbumId.takeIf {
            settings.lockEnabled && screenType != ScreenType.HOME
        }
        val synchronized = homeAlbumId != null && homeAlbumId == lockAlbumId && !settings.separateSchedules
        if (screenType == ScreenType.BOTH && synchronized) {
            fastChangeSingle(homeAlbumId, ScreenType.BOTH, WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK)
        } else {
            if (homeAlbumId != null) fastChangeSingle(homeAlbumId, ScreenType.HOME, WallpaperManager.FLAG_SYSTEM)
            if (lockAlbumId != null) fastChangeSingle(lockAlbumId, ScreenType.LOCK, WallpaperManager.FLAG_LOCK)
        }
    }

    private suspend fun fastChangeSingle(albumId: String, screenType: ScreenType, which: Int) {
        val queueScreen = if (screenType == ScreenType.BOTH) ScreenType.HOME else screenType
        if (wallpaperRepository.getNextWallpaperInQueue(albumId, queueScreen) == null) {
            wallpaperRepository.buildWallpaperQueue(albumId, queueScreen, false)
        }
        val candidate = wallpaperRepository.getAndDequeueWallpaper(albumId, queueScreen) ?: return
        try {
            val input = contentResolver.openInputStream(Uri.parse(candidate.uri))
                ?: throw IllegalStateException("Unable to open wallpaper URI")
            input.use { wallpaperManager.setStreamChecked(it, which) }
            val completedScreens = if (screenType == ScreenType.BOTH) {
                listOf(ScreenType.HOME, ScreenType.LOCK)
            } else {
                listOf(screenType)
            }
            completedScreens.forEach { target ->
                wallpaperRepository.setCurrentWallpaper(albumId, target, candidate.id)
                wallpaperRepository.removeWallpaperFromQueue(albumId, target, candidate.id)
            }
            Log.d(TAG, "Fast wallpaper applied from URI for $screenType")
        } catch (e: Exception) {
            wallpaperRepository.restoreWallpaperToQueueFront(albumId, queueScreen, candidate.id)
            throw e
        }
    }

    private fun handleChangeWallpaper(
        screenType: ScreenType,
        startId: Int,
        respectWallpaperMode: Boolean = false
    ) {
        serviceScope.launch {
            wallpaperChangeLock.mutex.withLock {
                try {
                    val effectiveScreenType =
                        if (
                            respectWallpaperMode &&
                            settingsRepository.getWallpaperMode() == WallpaperMode.LIVE
                        ) {
                            ScreenType.LIVE
                        } else {
                            screenType
                        }
                    changeWallpaper(
                        effectiveScreenType,
                        settingsRepository.getScheduleSettings()
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error changing wallpaper", e)
                    showErrorNotification(
                        getString(R.string.app_name),
                        e.localizedMessage
                            ?: getString(R.string.error_no_valid_wallpaper_after_retries)
                    )
                } finally {
                    stopSelf(startId)
                }
            }
        }
    }

    private suspend fun changeWallpaper(
        screenType: ScreenType,
        settings: ScheduleSettings
    ) {
        when (screenType) {
            ScreenType.LIVE -> {
                sendBroadcast(
                    Intent(Constants.ACTION_RELOAD_WALLPAPER).setPackage(packageName)
                )
                Log.d(TAG, "Requested immediate live wallpaper reload")
            }

            ScreenType.HOME -> settings.homeAlbumId?.let {
                changeSingle(it, ScreenType.HOME)
            } ?: Log.w(TAG, "No home album selected")

            ScreenType.LOCK -> settings.lockAlbumId?.let {
                changeSingle(it, ScreenType.LOCK)
            } ?: Log.w(TAG, "No lock album selected")

            ScreenType.BOTH -> {
                val homeAlbumId = settings.homeAlbumId.takeIf { settings.homeEnabled }
                val lockAlbumId = settings.lockAlbumId.takeIf { settings.lockEnabled }
                if (
                    homeAlbumId != null &&
                    homeAlbumId == lockAlbumId &&
                    !settings.separateSchedules
                ) {
                    changeSynchronized(homeAlbumId, settings)
                } else {
                    homeAlbumId?.let { changeSingle(it, ScreenType.HOME) }
                        ?: Log.w(TAG, "No home album selected")
                    lockAlbumId?.let { changeSingle(it, ScreenType.LOCK) }
                        ?: Log.w(TAG, "No lock album selected")
                }
            }
        }
    }

    private suspend fun changeSynchronized(
        albumId: String,
        settings: ScheduleSettings
    ) {
        when (val result = changeWallpaperUseCase(albumId, ScreenType.HOME)) {
            is MagenWallpaperResult.Success -> {
                val prepared = result.data
                val samePresentation =
                    settings.homeEffects == settings.lockEffects &&
                        settings.homeScalingType == settings.lockScalingType &&
                        !settings.homeScrollingEnabled

                if (samePresentation) {
                    applyPrepared(
                        prepared,
                        WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                        listOf(ScreenType.HOME, ScreenType.LOCK)
                    )
                    Log.d(TAG, "Home and lock wallpaper set atomically")
                } else {
                    applyPrepared(
                        prepared,
                        WallpaperManager.FLAG_SYSTEM,
                        listOf(ScreenType.HOME)
                    )
                    Log.d(TAG, "Home wallpaper set in BOTH mode")

                    when (
                        val lockResult = reapplyEffectsUseCase(
                            albumId,
                            ScreenType.LOCK,
                            prepared.wallpaperId
                        )
                    ) {
                        is MagenWallpaperResult.Success -> {
                            val lockBitmap = lockResult.data
                            try {
                                wallpaperManager.setBitmapChecked(
                                    lockBitmap,
                                    WallpaperManager.FLAG_LOCK
                                )
                                changeWallpaperUseCase.complete(prepared, ScreenType.LOCK)
                                Log.d(TAG, "Lock wallpaper set separately in BOTH mode")
                            } finally {
                                lockBitmap.recycle()
                            }
                        }

                        is MagenWallpaperResult.Error -> throw asException(lockResult.exception)
                        MagenWallpaperResult.Loading -> error("Unexpected loading result")
                    }
                }
            }

            is MagenWallpaperResult.Error -> {
                if (result.exception is EmptyAlbumException) {
                    handleEmptyAlbumError(ScreenType.BOTH)
                } else {
                    throw asException(result.exception)
                }
            }

            MagenWallpaperResult.Loading -> error("Unexpected loading result")
        }
    }

    private suspend fun changeSingle(
        albumId: String,
        screenType: ScreenType
    ) {
        when (val result = changeWallpaperUseCase(albumId, screenType)) {
            is MagenWallpaperResult.Success -> {
                val which = when (screenType) {
                    ScreenType.HOME -> WallpaperManager.FLAG_SYSTEM
                    ScreenType.LOCK -> WallpaperManager.FLAG_LOCK
                    else -> error("Unsupported static screen: $screenType")
                }
                applyPrepared(result.data, which, listOf(screenType))
                Log.d(TAG, "$screenType wallpaper changed successfully")
            }

            is MagenWallpaperResult.Error -> {
                if (result.exception is EmptyAlbumException) {
                    handleEmptyAlbumError(screenType)
                } else {
                    throw asException(result.exception)
                }
            }

            MagenWallpaperResult.Loading -> error("Unexpected loading result")
        }
    }

    private suspend fun applyPrepared(
        prepared: PreparedWallpaper,
        which: Int,
        completedScreens: List<ScreenType>
    ) {
        var platformAccepted = false
        try {
            wallpaperManager.setBitmapChecked(prepared.bitmap, which)
            platformAccepted = true
            completedScreens.forEach { screen ->
                changeWallpaperUseCase.complete(prepared, screen)
            }
        } catch (e: Exception) {
            if (!platformAccepted) {
                changeWallpaperUseCase.restore(prepared)
            }
            throw e
        } finally {
            prepared.bitmap.recycle()
        }
    }

    private fun handleReapplyEffects(screenType: ScreenType, startId: Int) {
        serviceScope.launch {
            wallpaperChangeLock.mutex.withLock {
                try {
                    val settings = settingsRepository.getScheduleSettings()
                    when (screenType) {
                        ScreenType.HOME -> settings.homeAlbumId?.let {
                            reapplySingle(it, ScreenType.HOME)
                        }

                        ScreenType.LOCK -> settings.lockAlbumId?.let {
                            reapplySingle(it, ScreenType.LOCK)
                        }

                        ScreenType.BOTH -> {
                            settings.homeAlbumId?.let {
                                reapplySingle(it, ScreenType.HOME)
                            }
                            settings.lockAlbumId?.let {
                                reapplySingle(it, ScreenType.LOCK)
                            }
                        }

                        ScreenType.LIVE -> Unit
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error reapplying effects", e)
                    showErrorNotification(
                        getString(R.string.app_name),
                        e.localizedMessage
                            ?: getString(R.string.error_no_valid_wallpaper_after_retries)
                    )
                } finally {
                    stopSelf(startId)
                }
            }
        }
    }

    private suspend fun reapplySingle(
        albumId: String,
        screenType: ScreenType
    ) {
        when (val result = reapplyEffectsUseCase(albumId, screenType)) {
            is MagenWallpaperResult.Success -> {
                val bitmap = result.data
                try {
                    val which = if (screenType == ScreenType.HOME) {
                        WallpaperManager.FLAG_SYSTEM
                    } else {
                        WallpaperManager.FLAG_LOCK
                    }
                    wallpaperManager.setBitmapChecked(bitmap, which)
                    Log.d(TAG, "$screenType effects reapplied successfully")
                } finally {
                    bitmap.recycle()
                }
            }

            is MagenWallpaperResult.Error -> {
                Log.w(TAG, "Reapply failed for $screenType; advancing queue", result.exception)
                changeSingle(albumId, screenType)
            }

            MagenWallpaperResult.Loading -> error("Unexpected loading result")
        }
    }

    private suspend fun handleEmptyAlbumError(screenType: ScreenType) {
        val settings = settingsRepository.getScheduleSettings()
        val updated = when (screenType) {
            ScreenType.HOME -> {
                val lockStillActive = settings.lockEnabled && settings.lockAlbumId != null
                settings.copy(
                    homeAlbumId = null,
                    enableChanger = lockStillActive && settings.enableChanger
                )
            }

            ScreenType.LOCK -> {
                val homeStillActive = settings.homeEnabled && settings.homeAlbumId != null
                settings.copy(
                    lockAlbumId = null,
                    enableChanger = homeStillActive && settings.enableChanger
                )
            }

            ScreenType.BOTH -> settings.copy(
                homeAlbumId = null,
                lockAlbumId = null,
                enableChanger = false
            )

            ScreenType.LIVE -> settings.copy(enableChanger = false)
        }
        settingsRepository.updateScheduleSettings(updated)
        showErrorNotification(
            getString(R.string.no_wallpapers_in_album),
            getString(R.string.wallpaper_changer_disabled_empty_album)
        )
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent().setClassName(packageName, MainActivity::class.java.name),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.changing_wallpaper))
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun showErrorNotification(title: String, message: String) {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent().setClassName(packageName, MainActivity::class.java.name),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, Constants.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notificationManager.notify(ERROR_NOTIFICATION_ID, notification)
    }

    private fun asException(throwable: Throwable): Exception =
        throwable as? Exception ?: RuntimeException(throwable)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "WallpaperChangeService"
        const val ACTION_CHANGE_WALLPAPER = Constants.ACTION_CHANGE_WALLPAPER
        const val ACTION_CHANGE_WALLPAPER_AUTO = Constants.ACTION_CHANGE_WALLPAPER_AUTO
        const val ACTION_CHANGE_WALLPAPER_FAST = Constants.ACTION_CHANGE_WALLPAPER_FAST
        const val ACTION_REAPPLY_EFFECTS = Constants.ACTION_REAPPLY_EFFECTS
        const val EXTRA_SCREEN_TYPE = Constants.EXTRA_SCREEN_TYPE
        private const val ERROR_NOTIFICATION_ID = Constants.NOTIFICATION_ID + 1
    }
}
