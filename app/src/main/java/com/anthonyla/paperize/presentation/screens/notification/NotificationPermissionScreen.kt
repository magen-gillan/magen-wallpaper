package com.anthonyla.paperize.presentation.screens.notification
import com.anthonyla.paperize.presentation.theme.AppIconSizes
import com.anthonyla.paperize.presentation.components.OnboardingLayout
import com.anthonyla.paperize.core.util.PermissionUtil

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.anthonyla.paperize.R
import com.anthonyla.paperize.core.constants.Constants
import com.anthonyla.paperize.presentation.theme.AppSpacing

/**
 * Screen to request notification permission (Android 13+)
 * Shows explanation and allows user to grant or skip
 */
@Composable
fun NotificationPermissionScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var permissionGranted by remember {
        mutableStateOf(PermissionUtil.hasNotificationPermission(context))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        permissionGranted = isGranted
        if (isGranted) {
            coroutineScope.launch {
                delay(900)
                onContinue()
            }
        }
    }

    OnboardingLayout(
        title = stringResource(R.string.notification_permission_title),
        modifier = modifier,
        iconContent = {
            Image(
                painter = painterResource(
                    if (permissionGranted) R.drawable.alisha_happy else R.drawable.alisha_sad
                ),
                contentDescription = stringResource(R.string.alisha_description),
                modifier = Modifier.size(108.dp),
                contentScale = ContentScale.Fit
            )
        },
        content = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(AppSpacing.medium)
            ) {
                Image(
                    painter = painterResource(R.drawable.magen_gillan_logo),
                    contentDescription = stringResource(R.string.magen_logo_description),
                    modifier = Modifier.size(64.dp),
                    contentScale = ContentScale.Fit
                )
                Image(
                    painter = painterResource(R.drawable.alisha_control_room),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth().height(72.dp).clip(MaterialTheme.shapes.large),
                    contentScale = ContentScale.Crop
                )
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = if (permissionGranted) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(AppSpacing.medium),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(AppSpacing.small)
                    ) {
                        Text(if (permissionGranted) "👋 ✨" else "👋", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            text = stringResource(
                                if (permissionGranted) R.string.permission_mood_granted
                                else R.string.permission_mood_denied
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (permissionGranted) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
                // Cleaner explanation card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    ),
                    border = androidx.compose.foundation.BorderStroke(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(AppSpacing.large),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = stringResource(R.string.notification_permission_description),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
        },
        actions = {
            // Allow button
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onContinue()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !permissionGranted,
                shape = MaterialTheme.shapes.large
            ) {
                Text(
                    text = if (permissionGranted) {
                        stringResource(R.string.permission_granted)
                    } else {
                        stringResource(R.string.allow)
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.padding(vertical = AppSpacing.small)
                )
            }

            // Skip button
            FilledTonalButton(
                onClick = onContinue,
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Text(
                    text = stringResource(R.string.skip),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                    modifier = Modifier.padding(vertical = AppSpacing.small)
                )
            }
        }
    )
}
