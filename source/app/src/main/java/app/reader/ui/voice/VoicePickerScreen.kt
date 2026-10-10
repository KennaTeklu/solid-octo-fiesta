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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.reader.tts.EngineVoiceGroup
import app.reader.tts.VoiceCatalog
import app.reader.tts.VoiceGroup
import app.reader.tts.VoiceInfo

/**
 * Full-screen voice picker (Batch 7 — TTS-01, TTS-04, VOICE-01..VOICE-04).
 *
 * Behaves like @Voice's voice picker: searchable, grouped, with a check mark
 * on the current voice. Each row shows the voice name, the language, and the
 * engine source — the three things a user needs to disambiguate two voices
 * with the same name from different engines.
 *
 * The "engine" chip at the top opens [TtsEnginePickerScreen] when tapped —
 * see NavGraph wiring. Switching engine reinitialises the TTS controller
 * and reloads this list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicePickerScreen(
    vm: VoicePickerViewModel,
    onBack: () -> Unit,
    onOpenEnginePicker: () -> Unit
) {
    val selectedVoice by vm.selectedVoice.collectAsStateWithLifecycle()
    val selectedEngine by vm.selectedEngine.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val groupByEngine by vm.groupByEngine.collectAsStateWithLifecycle()
    val engines by vm.engines.collectAsStateWithLifecycle()
    val voices = vm.voices

    val filtered = VoiceCatalog.search(voices, query)
    val currentEngineLabel = engines.firstOrNull { it.packageName == selectedEngine }?.label
        ?: "System default"

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voices") },
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
        ) {
            // --- Search field ---
            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Search voices, languages, engines") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Outlined.Clear, contentDescription = "Clear")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Search
                )
            )

            // --- Engine chip + grouping toggle ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AssistChip(
                    onClick = onOpenEnginePicker,
                    label = { Text("Engine: $currentEngineLabel") },
                    leadingIcon = { Icon(Icons.Outlined.Tune, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = groupByEngine,
                    onClick = vm::toggleGrouping,
                    label = { Text(if (groupByEngine) "Group: Engine" else "Group: Language") }
                )
            }
            HorizontalDivider()

            // --- Voice list ---
            if (voices.isEmpty()) {
                EmptyVoiceState(onOpenEnginePicker = onOpenEnginePicker)
            } else if (groupByEngine) {
                VoiceListByEngine(
                    groups = VoiceCatalog.groupByEngine(filtered),
                    selectedSignature = selectedVoice,
                    onSelect = vm::selectVoice
                )
            } else {
                VoiceListByLanguage(
                    groups = VoiceCatalog.groupByLanguage(filtered),
                    selectedSignature = selectedVoice,
                    onSelect = vm::selectVoice
                )
            }
        }
    }
}

@Composable
private fun VoiceListByLanguage(
    groups: List<VoiceGroup>,
    selectedSignature: String?,
    onSelect: (String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        groups.forEach { group ->
            item(key = "lang-${group.languageTag}") {
                Text(
                    text = group.languageDisplayName,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider()
            }
            items(group.voices, key = { it.signature }) { v ->
                VoiceRow(v, selected = v.signature == selectedSignature) {
                    onSelect(if (selectedSignature == v.signature) null else v.signature)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun VoiceListByEngine(
    groups: List<EngineVoiceGroup>,
    selectedSignature: String?,
    onSelect: (String?) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        groups.forEach { engineGroup ->
            item(key = "engine-${engineGroup.enginePackage}") {
                Text(
                    text = engineGroup.engineLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.SemiBold
                )
                HorizontalDivider()
            }
            items(engineGroup.languageGroups, key = { "lang-in-${engineGroup.enginePackage}-${it.languageTag}" }) { langGroup ->
                Text(
                    text = "  ${langGroup.languageDisplayName}",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                langGroup.voices.forEach { v ->
                    VoiceRow(v, selected = v.signature == selectedSignature) {
                        onSelect(if (selectedSignature == v.signature) null else v.signature)
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun VoiceRow(
    voice: VoiceInfo,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = voice.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${voice.localeDisplayName} · ${voice.engineLabel}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (voice.needsNetwork) {
                Text(
                    text = "Network required",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
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
private fun EmptyVoiceState(onOpenEnginePicker: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "No voices available for this engine.",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Try switching to another TTS engine, or install one from the Play Store.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onOpenEnginePicker) { Text("Switch engine") }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=text+to+speech+engine")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                try { context.startActivity(intent) } catch (_: Throwable) {
                    // No Play Store installed — fall back to web search.
                    val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=text%20to%20speech%20engine"))
                    webIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(webIntent) } catch (_: Throwable) { /* give up quietly */ }
                }
            }) { Text("Browse TTS engines on Play Store") }
        }
    }
}
