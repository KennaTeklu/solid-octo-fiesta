package com.peakform.fitness.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.core.*
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*

/**
 * ProfilesScreen — picker grid with emoji, add (random animal emoji), delete with
 * legacy confirm copy, and LIVE switching without restart (Profiles.switchTo).
 *
 * BATCH-4B item 3: tapping a profile now switches live AND lands on the new
 * profile's Dashboard (no restart). The shell observes ProState.notifyChanged
 * and recomposes; the picker calls `onClose` to drop itself off the back stack.
 *
 * BATCH-4B item 4: each row reads the live `Name (Default) (Currently selected)`
 * label — pulled live from the profile's Personal Info user.name + the
 * default/active pointers, nothing hardcoded. A "Set as default" action on
 * any non-default row promotes it to the cold-start profile.
 *
 * BATCH-4B item 5: creating a profile seeds that profile's user.name —
 * visible in the picker immediately as the live label.
 */
@Composable
fun ProfilesScreen(onClose: () -> Unit) {
    val c = LocalProColors.current
    val ctx = LocalContext.current
    var ver by remember { mutableIntStateOf(0) }
    var showAdd by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var toast by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf<Profiles.ProfileMeta?>(null) }

    // BATCH-4B item 2: observe ProState so a switch from elsewhere (or this
    // picker itself) re-renders the list with the new active/default state.
    DisposableEffect(Unit) {
        val l: () -> Unit = { ver++ }
        ProState.listeners.add(l)
        onDispose { ProState.listeners.remove(l) }
    }

    val profiles = remember(ver) { Profiles.list(ctx) }
    val activeId = remember(ver) { Profiles.activeId(ctx) }
    val defaultId = remember(ver) { Profiles.defaultId(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FaIcon("fa-user-group", size = 17.sp, tint = c.accent)
            Spacer(Modifier.width(10.dp))
            Text("Profiles", style = ProType.brand, color = c.text, modifier = Modifier.weight(1f))
            Text("close", style = ProType.small, color = c.accent, modifier = Modifier.clickable { onClose() })
        }
        Text("Each profile keeps its own data namespace — workouts, draft, fatigue, snapshots, settings. Switching is instant.", style = ProType.small, color = c.text3)

        profiles.forEach { p ->
            val active = p.id == activeId
            val isDefault = p.id == defaultId
            val liveLabel = remember(ver) { Profiles.displayLabel(ctx, p.id) }
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (active) c.accentSoft else c.surface)
                    .border(1.dp, if (active) c.accentLine else c.hairline, RoundedCornerShape(14.dp))
                    .clickable {
                        if (!active) {
                            // BATCH-4B item 3: live switch + land on Dashboard.
                            val name = Profiles.switchTo(ctx, p.id)
                            ver++
                            toast = "Switched to $name"
                            onClose()
                        }
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(p.emoji ?: "🏋️", fontSize = 24.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(liveLabel, style = ProType.label, color = if (active) c.accent else c.text)
                        Text(
                            if (active) "CURRENT" else "Tap to switch",
                            style = ProType.small, color = c.text3,
                        )
                    }
                    if (p.id != Profiles.DEFAULT && !isDefault) {
                        Text("delete", style = ProType.small, color = c.bad, modifier = Modifier.clickable { confirmDelete = p })
                    }
                }
                // BATCH-4B item 4: "Set as default" action on any non-default row.
                if (!isDefault) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Set as default",
                        style = ProType.small, color = c.accent,
                        modifier = Modifier.clickable {
                            Profiles.setAsDefault(ctx, p.id)
                            ver++
                            toast = "${p.name.ifBlank { "Profile" }} is now the default — opens on cold start."
                        },
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Default — opens on cold start.",
                        style = ProType.small, color = c.text3,
                    )
                }
            }
        }

        if (showAdd) {
            ProTextField(value = newName, onValueChange = { newName = it.take(30) }, placeholder = "New profile name")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                P4Button("Cancel", style = BtnStyle.GHOST, onClick = { showAdd = false })
                P4Button("Create", style = BtnStyle.PRIMARY, modifier = Modifier.weight(1f), enabled = newName.isNotBlank()) {
                    val p = Profiles.create(ctx, newName.trim())
                    newName = ""
                    showAdd = false
                    ver++
                    toast = "Profile ${p.name} created (${p.emoji})"
                }
            }
            Text("BATCH-4B item 5: the new profile's Personal Info name is seeded from this entry.", style = ProType.small, color = c.text3)
        } else {
            P4Button("Add profile", icon = "fa-plus", style = BtnStyle.PRIMARY, modifier = Modifier.fillMaxWidth(), onClick = { showAdd = true })
        }
        Spacer(Modifier.height(110.dp))

        confirmDelete?.let { p ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { confirmDelete = null },
                containerColor = c.glass2,
                shape = RoundedCornerShape(22.dp),
                title = { Text("Delete ${p.name}?", style = ProType.cardTitle, color = c.text) },
                text = { Text("Delete this profile and all its data? This cannot be undone.", style = ProType.body2, color = c.text2) },
                confirmButton = {
                    P4Button("Delete", style = BtnStyle.DANGER, onClick = {
                        Profiles.delete(ctx, p.id)
                        confirmDelete = null
                        ver++
                        toast = "Profile deleted"
                    })
                },
                dismissButton = { P4Button("Cancel", style = BtnStyle.SECONDARY, onClick = { confirmDelete = null }) },
            )
        }
        toast?.let { t ->
            LaunchedEffect(t) { kotlinx.coroutines.delay(2400); toast = null }
            ToastBanner(t)
        }
    }
}
