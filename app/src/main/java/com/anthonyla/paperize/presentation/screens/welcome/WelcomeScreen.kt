package com.anthonyla.paperize.presentation.screens.welcome

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.anthonyla.paperize.R

/**
 * Magen Wallpaper welcome. Permissions: notifications, exact alarms (timed changes),
 * background access (battery). The STATIC / LIVE mode choice stays in its own existing screen.
 */
@Composable
fun WelcomeScreen(onFinished: () -> Unit, modifier: Modifier = Modifier) {
    MagenWelcomeScreen(
        brandName = R.string.mwelcome_brand,
        tagline = R.string.mwelcome_tag,
        helloTitle = R.string.mwelcome_hi_t,
        helloBody = R.string.mwelcome_hi_b,
        logo = R.drawable.magen_gillan_logo,
        accent = Color(0xFFF5C95B),
        permissions = remember { listOf(MagenPermissions.notifications(), MagenPermissions.exactAlarms(), MagenPermissions.battery()) },
        onFinished = { onFinished() },
        modifier = modifier,
    )
}
