package com.peakform.fitness.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.core.*
import kotlinx.coroutines.launch
import com.peakform.fitness.engine.Library
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*

/**
 * Library Studio — native port of P4.Studio (PF L57163–57596): add/edit custom exercises
 * with legacy validation limits, export library JSON, import & merge with per-item merge
 * report, and the offline AI helper (schema-constrained prompt builder, no network).
 */

@Composable
fun StudioScreen(onOpenSection: (String) -> Unit) {
    val c = LocalProColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("menu") } // menu | add | import | helper
    var draft by remember { mutableStateOf(StudioDraft()) }
    var toast by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<List<String>>(emptyList()) }
    var paste by remember { mutableStateOf("") }
    var helperPrompt by remember { mutableStateOf<String?>(null) }

    val groups = remember { Library.allMuscleGroups().map { it.name }.sorted() }
    val muscleKeys = remember { Library.allMuscleGroups().map { it.name }.sorted() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp))
        SectionTitle("fa-flask-vial", "Library Studio")
        Text("Author your own movements, then merge them into the library.", style = ProType.small, color = c.text3)

        when (mode) {
            "menu" -> {
                StudioTile("fa-plus", "Add exercise", "Author a custom movement") { mode = "add" }
                StudioTile("fa-file-export", "Export library", "Custom + customised defaults as JSON") {
                    scope.launch {
                        val json = Studio.exportLibrary(ctx)
                        ProPrefs.put(ctx, "p4_studio_export", json)
                        toast = "Library exported (${json.length} bytes) — see Settings → Data & backup to save it"
                    }
                }
                StudioTile("fa-file-import", "Import & merge", "Paste JSON — same name updates, new name adds") { mode = "import" }
                StudioTile("fa-wand-magic-sparkles", "AI helper", "Offline: builds a fill-in prompt with schema + registry names") { mode = "helper" }
                if (report.isNotEmpty()) {
                    GlassCard(padding = PaddingValues(12.dp)) {
                        Text("Last merge report", style = ProType.label, color = c.text)
                        Spacer(Modifier.height(4.dp))
                        report.forEach { Text(it, style = ProType.small, color = c.text2) }
                    }
                }
            }
            "add" -> {
                ProTextField(value = draft.name, onValueChange = { draft = draft.copy(name = it.take(90)) }, placeholder = "Name (max 90)")
                Text("Group", style = ProType.small, color = c.text3)
                GroupChips(groups, draft.group) { draft = draft.copy(group = it) }
                Text("Muscles", style = ProType.small, color = c.text3)
                GroupChips(muscleKeys.take(24), draft.muscles.firstOrNull() ?: "") { draft = draft.copy(muscles = listOf(it)) }
                ProTextField(value = draft.equipment, onValueChange = { draft = draft.copy(equipment = it) }, placeholder = "Equipment (bodyweight, barbell…)")
                ProTextField(value = draft.steps, onValueChange = { draft = draft.copy(steps = it) }, placeholder = "Steps — one per line (max 8)")
                ProTextField(value = draft.cues, onValueChange = { draft = draft.copy(cues = it) }, placeholder = "Coaching cues (optional, max 6 lines)")
                ProTextField(value = draft.imageUrl, onValueChange = { draft = draft.copy(imageUrl = it) }, placeholder = "Image URL (https only, optional)")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    P4Button("Cancel", style = BtnStyle.GHOST, onClick = { mode = "menu" })
                    P4Button("Save exercise", style = BtnStyle.PRIMARY, modifier = Modifier.weight(1f), enabled = draft.name.isNotBlank() && draft.group.isNotBlank() && draft.muscles.isNotEmpty()) {
                        val err = Studio.validate(draft)
                        if (err != null) toast = err
                        else {
                            Studio.addCustom(ctx, draft)
                            Badges.bumpCounter(ctx, "studioAdds")
                            toast = "${draft.name} added to your library"
                            draft = StudioDraft()
                            mode = "menu"
                        }
                    }
                }
            }
            "import" -> {
                Text("Paste exercise JSON (array, or {\"exercises\":[…]} wrapper). Markdown fences, trailing commas and smart quotes are healed automatically.", style = ProType.small, color = c.text3)
                ProTextField(value = paste, onValueChange = { paste = it }, placeholder = "[{\"name\": …}]")
                val live = remember(paste) { Studio.quickValidate(paste) }
                Text(
                    when {
                        paste.isBlank() -> ""
                        live == null -> "✗ Not valid JSON yet"
                        else -> "✓ ${live ?: 0} exercises detected"
                    },
                    style = ProType.small, color = if (live == null) c.bad else c.ok,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    P4Button("Back", style = BtnStyle.GHOST, onClick = { mode = "menu" })
                    P4Button("Import & merge", style = BtnStyle.PRIMARY, modifier = Modifier.weight(1f), enabled = live != null) {
                        val res = Studio.importAndMerge(ctx, paste)
                        report = res
                        toast = res.lastOrNull() ?: "Import finished"
                        Badges.bumpCounter(ctx, "imports")
                        paste = ""
                        mode = "menu"
                    }
                }
            }
            "helper" -> {
                Text("The helper runs offline. It builds a copy-paste prompt with the JSON schema, the exact allowed keys, and 40 random existing names to avoid duplicates — paste it into any AI chat, then bring the answer back through Import & merge.", style = ProType.body2, color = c.text2)
                P4Button("Build prompt", icon = "fa-wand-magic-sparkles", style = BtnStyle.PRIMARY, modifier = Modifier.fillMaxWidth()) {
                    helperPrompt = Studio.buildHelperPrompt(ctx)
                }
                helperPrompt?.let { p ->
                    GlassCard(padding = PaddingValues(12.dp)) {
                        Text(p, style = ProType.small, color = c.text2)
                    }
                    P4Button("Copy prompt", icon = "fa-copy", style = BtnStyle.SECONDARY, modifier = Modifier.fillMaxWidth()) {
                        val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clip.setPrimaryClip(android.content.ClipData.newPlainText("pro-studio-prompt", p))
                        toast = "Prompt copied"
                    }
                }
                P4Button("Back", style = BtnStyle.GHOST, onClick = { mode = "menu" })
            }
        }

        toast?.let { t ->
            LaunchedEffect(t) { kotlinx.coroutines.delay(2400); toast = null }
            ToastBanner(t)
        }
        Spacer(Modifier.height(110.dp))
    }
}

@Composable
private fun StudioTile(icon: String, title: String, sub: String, onClick: () -> Unit) {
    val c = LocalProColors.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.hairline, RoundedCornerShape(14.dp))
        .clickable { onClick() }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        FaIcon(icon, size = 17.sp, tint = c.accent)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = ProType.label, color = c.text)
            Text(sub, style = ProType.small, color = c.text3)
        }
    }
}

@Composable
private fun GroupChips(options: List<String>, selected: String, onPick: (String) -> Unit) {
    val c = LocalProColors.current
    FlowChips(options, selected) { onPick(it) }
}

/** Lightweight wrapping chip row (no FlowRow dependency). */
@Composable
fun FlowChips(options: List<String>, selected: String, onPick: (String) -> Unit) {
    val c = LocalProColors.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.chunked(4).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { o ->
                    val active = o == selected
                    Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (active) c.accentSoft else c.surface2)
                        .border(1.dp, if (active) c.accentLine else c.hairline2, RoundedCornerShape(8.dp))
                        .clickable { onPick(o) }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                        Text(o.replace('_', ' '), fontSize = 10.sp, color = if (active) c.accent else c.text2, maxLines = 1)
                    }
                }
                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
