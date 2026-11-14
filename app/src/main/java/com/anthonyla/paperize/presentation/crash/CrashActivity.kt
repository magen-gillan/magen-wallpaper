package com.anthonyla.paperize.presentation.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.anthonyla.paperize.BuildConfig
import com.anthonyla.paperize.presentation.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shows the stack trace of an uncaught exception (and the recent logcat) so the user can copy
 * or share it. Launched by [GlobalExceptionHandler] when any thread throws.
 *
 * Minimal, dependency-free version of the magen-player CrashActivity: no Koin/Hilt injection,
 * no FileProvider — just plain text on a scrollable surface with Copy + Share + Restart actions.
 */
class CrashActivity : ComponentActivity() {

    private var exceptionText: String = ""
    private var logcatText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exceptionText = intent?.getStringExtra(GlobalExceptionHandler.EXCEPTION_EXTRA) ?: ""

        // Collect logcat on a background thread so the Activity can render immediately.
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var logcat by remember { mutableStateOf(logcatText) }
                    val scope = rememberCoroutineScope()
                    val context = LocalContext.current

                    if (logcat.isEmpty()) {
                        scope.launch(Dispatchers.IO) {
                            val collected = collectLogcat()
                            logcatText = collected
                            withContext(Dispatchers.Main) { logcat = collected }
                        }
                    }

                    CrashScreen(
                        exceptionText = exceptionText,
                        logcatText = logcat,
                        onCopy = {
                            clipboard.setPrimaryClip(
                                ClipData.newPlainText(
                                    "magen-crash",
                                    concatLogs(collectDeviceInfo(), exceptionText, logcat)
                                )
                            )
                        },
                        onShare = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Magen Wallpaper crash report")
                                putExtra(Intent.EXTRA_TEXT, concatLogs(collectDeviceInfo(), exceptionText, logcat))
                            }
                            runCatching { startActivity(Intent.createChooser(send, "Share crash report")) }
                        },
                        onRestart = {
                            finish()
                            runCatching {
                                startActivity(Intent(context, MainActivity::class.java))
                            }
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun CrashScreen(
        exceptionText: String,
        logcatText: String,
        onCopy: () -> Unit,
        onShare: () -> Unit,
        onRestart: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Magen Wallpaper crashed", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Tap Share and send the report to the developer. You can also restart the app.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            Text("Exception", style = MaterialTheme.typography.titleMedium)
            LogsBox(exceptionText)

            Text("Logcat (recent)", style = MaterialTheme.typography.titleMedium)
            LogsBox(logcatText)

            Spacer(Modifier.height(8.dp))
            Button(onClick = onShare, modifier = Modifier.fillMaxWidth()) { Text("Share report") }
            OutlinedButton(onClick = onCopy, modifier = Modifier.fillMaxWidth()) { Text("Copy to clipboard") }
            OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("Restart app") }
        }
    }

    @Composable
    private fun LogsBox(text: String, modifier: Modifier = Modifier) {
        SelectionContainer {
            Text(
                text = text,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall,
                modifier = modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
            )
        }
    }

    private fun collectDeviceInfo(): String = buildString {
        appendLine("App version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        appendLine("Android version: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Device: ${Build.BRAND} ${Build.MODEL} (${Build.DEVICE})")
        appendLine("Manufacturer: ${Build.MANUFACTURER}")
    }

    companion object {
        fun concatLogs(deviceInfo: String, exception: String, logcat: String): String = buildString {
            appendLine(deviceInfo)
            appendLine()
            if (exception.isNotBlank()) {
                appendLine("Exception:")
                appendLine(exception)
                appendLine()
            }
            appendLine("Logcat:")
            appendLine(logcat)
        }

        fun collectLogcat(): String {
            val process = Runtime.getRuntime()
            val reader = BufferedReader(InputStreamReader(process.exec("logcat -d -v brief *:W").inputStream))
            val sb = StringBuilder()
            reader.lines().forEach(sb::appendLine)
            return sb.toString()
        }
    }
}
