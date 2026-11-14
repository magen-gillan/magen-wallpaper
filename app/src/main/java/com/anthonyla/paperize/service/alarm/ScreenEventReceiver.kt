package com.anthonyla.paperize.service.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.anthonyla.paperize.core.ScreenType
import com.anthonyla.paperize.service.wallpaper.WallpaperChangeService

/**
 * Handles ACTION_USER_PRESENT (and ACTION_SCREEN_ON as a backup) by starting the
 * [WallpaperChangeService] foreground service with [WallpaperChangeService.ACTION_CHANGE_WALLPAPER_FAST].
 *
 * This manifest-declared receiver is the COLD-START path: it fires after the OEM has killed the
 * app process and the user unlocks the device. The system cold-starts the process just to deliver
 * the broadcast, which lets the app apply the next wallpaper even when it wasn't running.
 *
 * The WARM path (app process already alive in the background) is handled by a DYNAMIC receiver
 * registered in [com.anthonyla.paperize.MagenWallpaperApplication] — because Honor / Huawei /
 * Xiaomi and similar OEMs frequently block manifest receivers from firing for apps in the
 * background, even when the app process is still alive. The dynamic receiver catches those cases.
 *
 * Both paths start the same foreground service, and the service's deduplication logic (see
 * [WallpaperChangeService.handleFastChange]) prevents double-applies when both receivers fire
 * for the same unlock event.
 *
 * Note: the receiver itself does NOT do any Hilt injection or database access. It only starts
 * the foreground service, which is a one-line operation that completes well within the
 * BroadcastReceiver's 10-second goAsync window. The actual wallpaper apply happens in the
 * service, which has its own lifecycle, Hilt injection, and coroutine scope.
 */
class ScreenEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> {
                Log.d(TAG, "Received ${intent.action} — starting WallpaperChangeService")
                val serviceIntent = Intent(context, WallpaperChangeService::class.java).apply {
                    action = WallpaperChangeService.ACTION_CHANGE_WALLPAPER_FAST
                    putExtra(WallpaperChangeService.EXTRA_SCREEN_TYPE, ScreenType.BOTH.name)
                }
                // No goAsync() needed — startForegroundService is a non-blocking call that returns
                // immediately. The system starts the service asynchronously, and the service handles
                // its own lifecycle (including acquiring the mutex and applying the wallpaper).
                runCatching {
                    ContextCompat.startForegroundService(context, serviceIntent)
                }.onFailure { e ->
                    Log.e(TAG, "Failed to start WallpaperChangeService from manifest receiver", e)
                }
            }
        }
    }

    private companion object {
        const val TAG = "ScreenEventReceiver"
    }
}
