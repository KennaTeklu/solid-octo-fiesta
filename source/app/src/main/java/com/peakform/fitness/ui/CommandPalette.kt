package com.peakform.fitness.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.ui.screens.ProTextField

/**
 * CommandPalette — Ctrl/Cmd+K palette (PF L68100–68157) with the 7 legacy actions,
 * type-to-filter. Native triggers: long-press the header brand, hardware keyboard
 * Ctrl+K / Cmd+K, or the "Commands" cell.
 */
data class Command(val id: String, val label: String, val hint: String)

val COMMANDS = listOf(
    Command("dashboard", "Go to Dashboard", "Home"),
    Command("workout", "Start / Continue Workout", "Train"),
    Command("history", "View History", "Log"),
    Command("progress", "View Progress", "Charts"),
    Command("settings", "Open Settings", "Configure"),
    Command("profiles", "New Profile / Switch", "People"),
    Command("palette_close", "Close", "Esc"),
)

@Composable
fun CommandPalette(
    visible: Boolean,
    onDismiss: () -> Unit,
    onRun: (String) -> Unit,
) {
    if (!visible) return
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableIntStateOf(0) }
    val c = LocalProColors.current
    val filtered = remember(query) {
        if (query.isBlank()) COMMANDS
        else COMMANDS.filter { it.label.contains(query, ignoreCase = true) || it.hint.contains(query, ignoreCase = true) }
    }

    Box(Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.6f)).clickable { onDismiss() }) {
        Column(
            Modifier.align(Alignment.TopCenter).padding(top = 90.dp).fillMaxWidth().padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp)).background(c.glass2).border(1.dp, c.hairline2, RoundedCornerShape(20.dp))
                .padding(12.dp),
        ) {
            ProTextField(value = query, onValueChange = { query = it; selected = 0 }, placeholder = "Type a command…")
            Spacer(Modifier.height(8.dp))
            filtered.take(7).forEachIndexed { i, cmd ->
                val activeRow = i == selected
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (activeRow) c.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onRun(cmd.id) }.padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(cmd.label, style = ProType.body2, color = if (activeRow) c.accent else c.text)
                    Spacer(Modifier.weight(1f))
                    Text(cmd.hint, style = ProType.small, color = c.text3)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Tap a command · Esc closes · Ctrl K", style = ProType.small, color = c.text3, fontSize = 10.sp)
        }
    }
}
