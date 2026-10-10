package app.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reader.BuildConfig
import app.reader.data.repo.DocumentRepository
import java.util.Locale

/**
 * Settings screen (Batch 7 restructure — SET-03).
 *
 * Layout:
 *   Appearance  — unchanged
 *   Voice       — Voice picker link, TTS engine picker link, Rate / Pitch / Volume sliders, Preview button
 *     Advanced  — Rate range (min/max), step size
 *   Cloud voices — OpenAI / Azure / Polly / Custom endpoint fields, encrypted at rest, UNWIRED
 *   Reading     — unchanged
 *   Storage     — unchanged
 *   About       — unchanged
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    vm: SettingsViewModel,
    onBack: () -> Unit,
    onOpenVoicePicker: () -> Unit,
    onOpenEnginePicker: () -> Unit
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
    val speechVolume by vm.speechVolume.collectAsStateWithLifecycle()
    val rateMin by vm.rateMin.collectAsStateWithLifecycle()
    val rateMax by vm.rateMax.collectAsStateWithLifecycle()
    val rateStep by vm.rateStep.collectAsStateWithLifecycle()
    val selectedEngine by vm.selectedEngine.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    val openaiKey by vm.openaiKey.collectAsStateWithLifecycle()
    val azureKey by vm.azureKey.collectAsStateWithLifecycle()
    val pollyKey by vm.pollyKey.collectAsStateWithLifecycle()
    val customUrl by vm.customUrl.collectAsStateWithLifecycle()
    val customKey by vm.customKey.collectAsStateWithLifecycle()
    val customModel by vm.customModel.collectAsStateWithLifecycle()

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
            // ============ Appearance ============
            SectionHeader("Appearance", icon = Icons.Outlined.Tune)
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

            // ============ Voice ============
            SectionHeader("Voice", icon = Icons.Outlined.RecordVoiceOver)
            // Voice picker link
            SettingsLinkRow(
                title = "Voice picker",
                subtitle = voice?.let { sig ->
                    val parts = sig.split('|')
                    "${parts.getOrNull(1) ?: sig} · ${parts.getOrNull(0) ?: ""}"
                } ?: "System default voice",
                onClick = onOpenVoicePicker
            )
            // TTS engine picker link
            SettingsLinkRow(
                title = "TTS engine",
                subtitle = selectedEngine ?: "System default engine",
                onClick = onOpenEnginePicker
            )
            // Rate slider — bounded by rateMin..rateMax
            SliderRow(
                title = "Speech rate",
                value = speechRate.coerceIn(rateMin, rateMax),
                from = rateMin,
                to = rateMax,
                onChange = vm::setSpeechRate,
                labelProvider = { String.format(Locale.US, "%.2fx", it) }
            )
            // Pitch slider
            SliderRow(
                title = "Pitch",
                value = pitch,
                from = 0.5f,
                to = 2.0f,
                onChange = vm::setPitch,
                labelProvider = { String.format(Locale.US, "%.2f", it) }
            )
            // Volume slider — speech-only, independent of media volume
            SliderRow(
                title = "Speech volume",
                value = speechVolume,
                from = 0f,
                to = 1f,
                onChange = vm::setSpeechVolume,
                labelProvider = { "${(it * 100).toInt()}%" }
            )
            Text(
                "Volume uses a separate speech audio path (navigation guidance). " +
                    "Your device's media volume slider doesn't affect it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = vm::previewVoice, modifier = Modifier.weight(1f)) {
                    Text("Preview voice")
                }
                OutlinedButton(
                    onClick = {
                        val intent = vm.openSystemTts().addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("System TTS settings")
                }
            }

            // ============ Advanced (subsection) ============
            SectionHeader("Advanced", icon = Icons.Outlined.Speed)
            SliderRow(
                title = "Minimum rate",
                value = rateMin,
                from = 0.25f,
                to = 1.0f,
                onChange = vm::setRateMin,
                labelProvider = { String.format(Locale.US, "%.2fx", it) }
            )
            SliderRow(
                title = "Maximum rate",
                value = rateMax,
                from = 1.5f,
                to = 4.0f,
                onChange = vm::setRateMax,
                labelProvider = { String.format(Locale.US, "%.2fx", it) }
            )
            SliderRow(
                title = "Speed-up / slow-down step",
                value = rateStep,
                from = 0.05f,
                to = 0.5f,
                onChange = vm::setRateStep,
                labelProvider = { String.format(Locale.US, "%.2fx", it) }
            )
            Text(
                "When you tap + or − on the playback bar, the rate changes by this amount. " +
                    "The rate slider is clamped to the min/max above.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // ============ Cloud voices ============
            SectionHeader("Cloud voices", icon = Icons.Outlined.Cloud)
            Text(
                "Pre-enter API keys for cloud TTS providers. Keys are encrypted at rest with the Android Keystore and never leave the device. " +
                    "Cloud voice integration arrives in Batch 8 — these fields are stored now so you can pre-configure them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CloudKeyField(
                label = "OpenAI API key",
                value = openaiKey ?: "",
                onValueChange = vm::setOpenaiKey,
                placeholder = "sk-..."
            )
            CloudKeyField(
                label = "Azure Speech key",
                value = azureKey ?: "",
                onValueChange = vm::setAzureKey,
                placeholder = "Azure region key"
            )
            CloudKeyField(
                label = "Amazon Polly key",
                value = pollyKey ?: "",
                onValueChange = vm::setPollyKey,
                placeholder = "AKIA..."
            )
            HorizontalDivider()
            CloudKeyField(
                label = "Custom endpoint URL",
                value = customUrl ?: "",
                onValueChange = vm::setCustomUrl,
                placeholder = "https://tts.example.com/v1/audio/speech",
                password = false
            )
            CloudKeyField(
                label = "Custom endpoint key",
                value = customKey ?: "",
                onValueChange = vm::setCustomKey,
                placeholder = "Bearer token"
            )
            CloudKeyField(
                label = "Custom endpoint model",
                value = customModel ?: "",
                onValueChange = vm::setCustomModel,
                placeholder = "tts-1 / custom-voice",
                password = false
            )

            HorizontalDivider()

            // ============ Reading ============
            SectionHeader("Reading", icon = Icons.Outlined.GraphicEq)
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

            // ============ Storage ============
            SectionHeader("Storage")
            val sizeMb = storageBytes / (1024.0 * 1024.0)
            Text(
                "App storage: %.1f MB".format(sizeMb),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            HorizontalDivider()

            // ============ About ============
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
private fun SectionHeader(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
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
private fun SettingsLinkRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onClick) {
            Icon(Icons.Outlined.ArrowForward, contentDescription = "Open $title")
        }
    }
}

@Composable
private fun CloudKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    password: Boolean = true
) {
    var show by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder) },
            singleLine = true,
            visualTransformation = if (password && !show) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                imeAction = androidx.compose.ui.text.input.ImeAction.Done
            ),
            trailingIcon = if (password) {
                {
                    TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") }
                }
            } else null
        )
    }
}
