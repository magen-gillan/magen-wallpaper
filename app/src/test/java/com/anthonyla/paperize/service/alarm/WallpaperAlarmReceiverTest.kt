package com.anthonyla.paperize.service.alarm

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperAlarmReceiverTest {
    @Test
    fun `pending deadline on same boot is preserved`() {
        assertTrue(
            isAlarmDeadlinePending(
                savedBootCount = 12,
                currentBootCount = 12,
                dueElapsed = 900_000L,
                nowElapsed = 840_000L
            )
        )
    }

    @Test
    fun `expired deadline is not treated as scheduled`() {
        assertFalse(
            isAlarmDeadlinePending(
                savedBootCount = 12,
                currentBootCount = 12,
                dueElapsed = 840_000L,
                nowElapsed = 840_000L
            )
        )
    }

    @Test
    fun `deadline from a previous boot is not treated as scheduled`() {
        assertFalse(
            isAlarmDeadlinePending(
                savedBootCount = 12,
                currentBootCount = 13,
                dueElapsed = 900_000L,
                nowElapsed = 840_000L
            )
        )
    }
}
