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
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*
import kotlinx.coroutines.launch

/**
 * SnapshotsScreen — named vault snapshot list with tap-to-restore + confirmation
 * (legacy P4.Vault UI; restore is ALWAYS user-initiated — the 1.2 guarded design).
 */
@Composable
fun SnapshotsScreen(onClose: () -> Unit) {
    val c = LocalProColors.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val ver = remember { mutableIntStateOf(0) }
    var confirmRestore by remember { mutableStateOf<Snapshots.Entry?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    val entries = remember(ver.intValue) { kotlinx.coroutines.runBlocking { Snapshots.list(ctx) } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FaIcon("fa-camera", size = 17.sp, tint = c.accent)
            Spacer(Modifier.width(10.dp))
            Text("Snapshots", style = ProType.brand, color = c.text, modifier = Modifier.weight(1f))
            Text("close", style = ProType.small, color = c.accent, modifier = Modifier.clickable { onClose() })
        }
        Text("A snapshot captures everything. Restoring takes a pre-restore safety snapshot first.", style = ProType.small, color = c.text3)
        P4Button("Take snapshot now", icon = "fa-plus", style = BtnStyle.PRIMARY, modifier = Modifier.fillMaxWidth()) {
            scope.launch {
                Snapshots.create(ctx, Snapshots.defaultName())
                ver.intValue++
                toast = "Snapshot saved"
            }
        }
        if (entries.isEmpty()) {
            EmptyState("fa-camera", "No snapshots yet", "Take one before big changes or imports.")
        } else {
            entries.forEach { e ->
                val whenTxt = java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(java.util.Date(e.at))
                val sizeTxt = if (e.bytes >= 1024) "${(e.bytes + 512) / 1024} KB" else "${e.bytes} B"
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).border(1.dp, c.hairline, RoundedCornerShape(14.dp))
                    .clickable { confirmRestore = e }.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    FaIcon("fa-clock-rotate-left", size = 14.sp, tint = c.accent)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = ProType.label, color = c.text)
                        Text("$whenTxt · $sizeTxt", style = ProType.small, color = c.text3)
                    }
                    Text("restore", style = ProType.small, color = c.accent)
                    Spacer(Modifier.width(10.dp))
                    Text("delete", style = ProType.small, color = c.bad, modifier = Modifier.clickable {
                        scope.launch { Snapshots.delete(ctx, e.id); ver.intValue++; toast = "Snapshot deleted" }
                    })
                }
            }
        }
        Spacer(Modifier.height(110.dp))

        confirmRestore?.let { e ->
            com.peakform.fitness.ui.RestoreConfirmDialog(
                name = e.name,
                onConfirm = {
                    scope.launch {
                        val r = Snapshots.restore(ctx, e.id)
                        confirmRestore = null
                        ver.intValue++
                        toast = if (r != null) "Restored “${r.name}” — ${r.workouts} workouts" else "Restore failed"
                    }
                },
                onDismiss = { confirmRestore = null },
            )
        }
        toast?.let { t ->
            LaunchedEffect(t) { kotlinx.coroutines.delay(2600); toast = null }
            ToastBanner(t)
        }
    }
}
