package app.reader.ui.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reader.BuildConfig
import app.reader.data.repo.DocumentRepository
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val theme by vm.theme.collectAsStateWithLifecycle()
    val dynamicColor by vm.dynamicColor.collectAsStateWithLifecycle()
    val fontScale by vm.fontScale.collectAsStateWithLifecycle()
    val keepScreenOn by vm.keepScreenOn.collectAsStateWithLifecycle()
    val autoScroll by vm.autoScroll.collectAsStateWithLifecycle()
    val highlightStyle by vm.highlightStyle.collectAsStateWithLifecycle()
    val pauseAtChapter by vm.pauseAtChapter.collectAsStateWithLifecycle()
    val speechRate by vm.speechRate.collectAsStateWithLifecycle()
    val pitch by vm.pitch.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()

    val storageBytes = DocumentRepository.get(context).storageUsedBytes()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            SectionHeader("Appearance")
            RowSelector(
                title = "Theme",
                options = listOf("light" to "Light", "dark" to "Dark", "system" to "System"),
                selected = theme,
                onSelect = vm::setTheme
            )
            SwitchRow(
                title = "Dynamic color",
                subtitle = "Match colors to your wallpaper (Android 12+)",
                checked = dynamicColor,
                onCheckedChange = vm::setDynamicColor
            )
            SliderRow(
                title = "Font size",
                value = fontScale,
                from = 0.75f,
                to = 1.75f,
                onChange = vm::setFontScale,
                labelProvider = { "${(it * 100).toInt()}%" }
            )
            SwitchRow(
                title = "Keep screen on while reading",
                subtitle = "Don't dim the screen when text is open",
                checked = keepScreenOn,
                onCheckedChange = vm::setKeepScreenOn
            )

            HorizontalDivider()

            SectionHeader("Voice")
            VoicePicker(
                voices = vm.voices,
                selectedSignature = voice,
                onSelect = vm::setVoice
            )
            SliderRow(
                title = "Speech rate",
                value = speechRate,
                from = 0.5f,
                to = 2.0f,
                onChange = vm::setSpeechRate,
                labelProvider = { String.format(Locale.US, "%.1fx", it) }
            )
            SliderRow(
                title = "Pitch",
                value = pitch,
                from = 0.5f,
                to = 2.0f,
                onChange = vm::setPitch,
                labelProvider = { String.format(Locale.US, "%.1f", it) }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = vm::previewVoice, modifier = Modifier.weight(1f)) {
                    Text("Preview voice")
                }
                OutlinedButton(
                    onClick = { context.startActivity(vm.openSystemTts().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("System TTS settings")
                }
            }

            HorizontalDivider()

            SectionHeader("Reading")
            SwitchRow(
                title = "Auto-scroll",
                subtitle = "Follow the highlighted sentence",
                checked = autoScroll,
                onCheckedChange = vm::setAutoScroll
            )
            RowSelector(
                title = "Highlight style",
                options = listOf("sentence" to "Sentence", "word" to "Word", "off" to "Off"),
                selected = highlightStyle,
                onSelect = vm::setHighlightStyle
            )
            SwitchRow(
                title = "Pause at chapter breaks",
                subtitle = "Stop at the end of each EPUB/PDF/DOCX chapter",
                checked = pauseAtChapter,
                onCheckedChange = vm::setPauseAtChapter
            )

            HorizontalDivider()

            SectionHeader("Storage")
            val sizeMb = storageBytes / (1024.0 * 1024.0)
            Text(
                "App storage: %.1f MB".format(sizeMb),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            SectionHeader("About")
            Text("Reader", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(
                "A clean, offline text-to-speech reader. No ads, no trackers, no account, no network access.",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    from: Float,
    to: Float,
    onChange: (Float) -> Unit,
    labelProvider: (Float) -> String
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(labelProvider(value), style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.coerceIn(from, to),
            onValueChange = { onChange(it) },
            valueRange = from..to
        )
    }
}

@Composable
private fun RowSelector(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        options.forEach { (key, label) ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected == key, onClick = { onSelect(key) })
                Text(label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun VoicePicker(
    voices: List<SettingsViewModel.VoiceOption>,
    selectedSignature: String?,
    onSelect: (String?) -> Unit
) {
    Column {
        Text("Voice", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Picks a system TTS voice. Leave at default to follow your system language.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(selected = selectedSignature == null, onClick = { onSelect(null) })
            Text("Default")
        }
        voices.take(20).forEach { v ->  // cap to 20 rows for screen space
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selectedSignature == v.signature, onClick = { onSelect(v.signature) })
                Text(v.label, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (voices.size > 20) {
            Text(
                "+${voices.size - 20} more voices available — see system TTS settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}


