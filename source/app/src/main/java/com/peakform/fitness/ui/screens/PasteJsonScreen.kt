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
import com.peakform.fitness.core.Backup
import com.peakform.fitness.core.JsonHeal
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.ProJson
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*
import kotlinx.coroutines.launch

/**
 * PasteJsonScreen — BATCH-2A feature 9: paste-JSON import (P4.Studio.submitImport).
 * Text field → heal → guarded import → merge report. Accepts any format the file
 * importer accepts (Format A, Complete-v1, Format B, bare workoutData).
 */
@Composable
fun PasteJsonScreen(onClose: () -> Unit) {
    val c = LocalProColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var paste by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }
    var guardFor by remember { mutableStateOf<String?>(null) }
    var report by remember { mutableStateOf<Backup.ImportResult?>(null) }
    var reportFailed by remember { mutableStateOf<String?>(null) }

    val liveCheck = remember(paste) {
        if (paste.isBlank()) null else try {
            val healed = JsonHeal.heal(paste)
            ProJson.json.parseToJsonElement(healed)
            "✓ Valid JSON after healing"
        } catch (e: Exception) {
            "✗ Not valid JSON yet — ${e.message?.take(60)}"
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FaIcon("fa-clipboard", size = 17.sp, tint = c.accent)
            Spacer(Modifier.width(10.dp))
            Text("Paste JSON to import", style = ProType.brand, color = c.text, modifier = Modifier.weight(1f))
            Text("close", style = ProType.small, color = c.accent, modifier = Modifier.clickable { onClose() })
        }
        Text("Paste any backup — Format A, Complete-v1, Format B or bare workoutData. Markdown fences, trailing commas and smart quotes are healed automatically; string-form NaN/Infinity values are normalized to null.", style = ProType.small, color = c.text3)
        ProTextField(value = paste, onValueChange = { paste = it }, placeholder = "{ \"workouts\": … }", singleLine = false)
        if (liveCheck != null) {
            Text(liveCheck, style = ProType.small, color = if (liveCheck.startsWith("✓")) c.ok else c.bad)
        }
        P4Button("Heal & import", icon = "fa-file-import", style = BtnStyle.PRIMARY, modifier = Modifier.fillMaxWidth(), enabled = paste.isNotBlank() && liveCheck?.startsWith("✓") == true) {
            guardFor = paste
        }
        Spacer(Modifier.height(110.dp))
    }

    // feature 8: pre-import guard — red confirm before anything is touched
    guardFor?.let { raw ->
        com.peakform.fitness.ui.ImportGuardDialog(
            detail = "This merges the pasted JSON into your current data (a pre-import snapshot is taken first).",
            onConfirm = {
                scope.launch {
                    val res = Backup.import(ctx, raw)
                    guardFor = null
                    if (res.ok) report = res else reportFailed = res.message
                }
            },
            onDismiss = { guardFor = null },
        )
    }

    report?.let { r ->
        MergeReportDialog(
            result = r,
            onClose = {
                report = null
                toast = "Import finished"
                paste = ""
            },
        )
    }
    reportFailed?.let { msg ->
        SwalDialog(
            title = "Import failed",
            text = msg,
            confirmText = "OK",
            onConfirm = { reportFailed = null },
        )
    }
    toast?.let { t ->
        LaunchedEffect(t) { kotlinx.coroutines.delay(2600); toast = null }
        ToastBanner(t)
    }
}

/** Feature 11 — merge report surfaced as a dialog on EVERY import. */
@Composable
fun MergeReportDialog(result: Backup.ImportResult, onClose: () -> Unit) {
    val c = LocalProColors.current
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onClose,
        containerColor = c.glass2,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Import report", style = ProType.cardTitle, color = c.text) },
        text = {
            Column {
                KeyValueRow("Format detected", result.format)
                KeyValueRow("Workouts added", result.workoutsAdded.toString())
                KeyValueRow("Exercises added", result.exercisesAdded.toString())
                KeyValueRow("Duplicates skipped", result.duplicates.toString())
                HorizontalHairline()
                Spacer(Modifier.height(8.dp))
                if (result.rejected.isEmpty()) {
                    Text("Nothing rejected.", style = ProType.small, color = c.ok)
                } else {
                    Text("Rejected items:", style = ProType.small, color = c.bad)
                    result.rejected.take(8).forEach { r ->
                        Text("✗ $r", style = ProType.small, color = c.text2)
                    }
                    if (result.rejected.size > 8) Text("…and ${result.rejected.size - 8} more", style = ProType.small, color = c.text3)
                }
            }
        },
        confirmButton = { P4Button("Done", onClick = onClose, style = BtnStyle.PRIMARY, minHeight = 40) },
    )
}
