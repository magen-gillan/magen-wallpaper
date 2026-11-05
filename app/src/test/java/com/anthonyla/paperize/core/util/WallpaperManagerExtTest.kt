package com.anthonyla.paperize.core.util

import android.app.WallpaperManager
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WallpaperManagerExtTest {

    @Test
    fun `zero wallpaper id is treated as failure`() {
        assertThrows(IOException::class.java) {
            requireWallpaperSetSucceeded(0)
        }
    }

    @Test
    fun `positive wallpaper id is accepted`() {
        requireWallpaperSetSucceeded(42)
    }

    @Test
    fun `zero stream result is treated as failure`() {
        val wallpaperManager = mockk<WallpaperManager>()
        every {
            wallpaperManager.setStream(any(), null, true, WallpaperManager.FLAG_LOCK)
        } returns 0

        assertThrows(IOException::class.java) {
            wallpaperManager.setStreamChecked(
                ByteArrayInputStream(byteArrayOf(1)),
                WallpaperManager.FLAG_LOCK
            )
        }
    }

    @Test
    fun `positive stream result is returned`() {
        val wallpaperManager = mockk<WallpaperManager>()
        every {
            wallpaperManager.setStream(any(), null, true, WallpaperManager.FLAG_LOCK)
        } returns 42

        val wallpaperId = wallpaperManager.setStreamChecked(
            ByteArrayInputStream(byteArrayOf(1)),
            WallpaperManager.FLAG_LOCK
        )

        assertEquals(42, wallpaperId)
    }
}
