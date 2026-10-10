package app.reader.ui.voice

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reader.tts.TtsEngineInfo

/**
 * TTS engine picker screen (Batch 7 — TTS-05).
 *
 * Lists every installed Android TTS engine — Google TTS, Samsung TTS, any
 * third-party engine the user has installed. Selecting an engine persists
 * the choice and re-initialises [app.reader.tts.TtsController], which
 * repopulates the voice list shown in [VoicePickerScreen].
 *
 * Empty state: if no engines are found (unusual — every Android device
 * ships with at least one), we surface a Play Store link.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsEnginePickerScreen(
    vm: TtsEnginePickerViewModel,
    onBack: () -> Unit
) {
    val engines by vm.engines.collectAsStateWithLifecycle()
    val selectedEngine by vm.selectedEngine.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TTS engines") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (engines.isEmpty()) {
            EmptyEngineState(modifier = Modifier.padding(padding))
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                // System default option — clears the saved selection.
                item(key = "system-default") {
                    EngineRow(
                        engine = TtsEngineInfo(
                            packageName = "",
                            label = "System default",
                            isDefault = true
                        ),
                        selected = selectedEngine == null,
                        onClick = { vm.select(null) }
                    )
                    HorizontalDivider()
                }
                items(engines, key = { it.packageName }) { engine ->
                    EngineRow(
                        engine = engine,
                        selected = engine.packageName == selectedEngine,
                        onClick = { vm.select(engine.packageName) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun EngineRow(
    engine: TtsEngineInfo,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                engine.label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                engine.packageName.ifBlank { "Uses the device's default engine" },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (selected) {
            Icon(
                Icons.Outlined.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun EmptyEngineState(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No TTS engines installed.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Android needs at least one TTS engine to read text aloud. " +
                    "Install one from the Play Store to enable speech.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=text+to+speech+engine")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { context.startActivity(intent) } catch (_: Throwable) {
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=text%20to%20speech%20engine"))
                    webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(webIntent) } catch (_: Throwable) {}
                }
            }) { Text("Browse TTS engines on Play Store") }
        }
    }
}
