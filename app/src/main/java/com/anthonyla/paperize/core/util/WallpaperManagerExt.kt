package com.anthonyla.paperize.core.util

import android.app.WallpaperManager
import android.graphics.Bitmap
import java.io.IOException
import java.io.InputStream

/**
 * Apply a bitmap and treat WallpaperManager's documented zero return value as a failure.
 */
fun WallpaperManager.setBitmapChecked(bitmap: Bitmap, which: Int): Int {
    check(bitmap.width > 0 && bitmap.height > 0) {
        "Invalid bitmap dimensions: ${bitmap.width}x${bitmap.height}"
    }
    check(!bitmap.isRecycled) { "Bitmap has been recycled" }

    val wallpaperId = setBitmap(bitmap, null, true, which)
    requireWallpaperSetSucceeded(wallpaperId)
    return wallpaperId
}

/** Apply an image stream and reject WallpaperManager's documented zero failure result. */
fun WallpaperManager.setStreamChecked(bitmapData: InputStream, which: Int): Int {
    val wallpaperId = setStream(bitmapData, null, true, which)
    requireWallpaperSetSucceeded(wallpaperId)
    return wallpaperId
}

/** Shared guard for WallpaperManager methods that return zero when setting a wallpaper fails. */
internal fun requireWallpaperSetSucceeded(wallpaperId: Int) {
    if (wallpaperId == 0) {
        throw IOException("WallpaperManager rejected the wallpaper")
    }
}
