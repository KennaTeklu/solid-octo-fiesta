package com.peakform.fitness.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*

/**
 * Glossary — word list at the current reading level (legacy glossary overlay, PF L57859–57886)
 * + phrasebook export/import round-trip (L57598–57678).
 */
@Composable
fun GlossaryScreen(onClose: () -> Unit) {
    val c = LocalProColors.current
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var level by remember { mutableIntStateOf(Vocab.currentLevel(ctx)) }
    var toast by remember { mutableStateOf<String?>(null) }
    var importPaste by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }

    val entries = remember(level, query) {
        Vocab.glossaryEntries(ctx).filter { (k, _) -> query.length < 2 || k.lowercase().contains(query.lowercase()) }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FaIcon("fa-spell-check", size = 17.sp, tint = c.accent)
            Spacer(Modifier.width(10.dp))
            Text("Words", style = ProType.brand, color = c.text, modifier = Modifier.weight(1f))
            Text("L$level", style = ProType.cardTitle, color = c.accent)
        }
        ProTextField(value = query, onValueChange = { query = it }, placeholder = "Search words…")

        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Vocab.LEVELS.forEach { l ->
                val active = level == l.id
                Box(Modifier.clip(RoundedCornerShape(999.dp)).background(if (active) c.accentSoft else c.surface2)
                    .border(1.dp, if (active) c.accentLine else c.hairline2, RoundedCornerShape(999.dp))
                    .clickable { level = l.id; Vocab.setLevel(ctx, l.id) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text("L${l.id}", fontSize = 11.sp, color = if (active) c.accent else c.text2)
                }
            }
        }
        Text(Vocab.LEVELS.first { it.id == level }.name, style = ProType.small, color = c.text3)

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${entries.size} of ${Vocab.LEVELS.size * 188} phrases tuned", style = ProType.small, color = c.text3)
            entries.take(120).forEach { (word, replacement) ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surface).padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Text(word, style = ProType.body2, color = c.text)
                    Spacer(Modifier.width(8.dp))
                    Text("→", style = ProType.body2, color = c.text3)
                    Spacer(Modifier.width(8.dp))
                    Text(replacement, style = ProType.body2, color = c.accent)
                }
            }
            if (entries.size > 120) Text("Showing 120 of ${entries.size} — refine search", style = ProType.small, color = c.text3)
        }

        if (showImport) {
            ProTextField(value = importPaste, onValueChange = { importPaste = it }, placeholder = "Paste a phrasebook JSON…")
            P4Button("Merge into L$level", style = BtnStyle.PRIMARY, modifier = Modifier.fillMaxWidth(), enabled = importPaste.isNotBlank()) {
                val n = Vocab.importPhrasebook(ctx, importPaste)
                toast = if (n > 0) "Merged $n phrases into L$level" else "Import failed"
                showImport = false
                importPaste = ""
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                P4Button("Export phrases", icon = "fa-download", style = BtnStyle.SECONDARY, modifier = Modifier.weight(1f)) {
                    val json = Vocab.exportPhrasebook(ctx)
                    val clip = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clip.setPrimaryClip(ClipData.newPlainText("pro-phrasebook", json))
                    toast = "L$level phrasebook copied (${json.length} chars)"
                }
                P4Button("Import phrases", icon = "fa-upload", style = BtnStyle.SECONDARY, modifier = Modifier.weight(1f)) { showImport = true }
            }
        }
        P4Button("Close", style = BtnStyle.GHOST, modifier = Modifier.fillMaxWidth().padding(bottom = 96.dp), onClick = onClose)

        toast?.let { t ->
            LaunchedEffect(t) { kotlinx.coroutines.delay(2400); toast = null }
            ToastBanner(t)
        }
    }
}
