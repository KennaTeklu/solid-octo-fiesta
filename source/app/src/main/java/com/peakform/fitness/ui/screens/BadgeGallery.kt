package com.peakform.fitness.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.peakform.fitness.core.Badges
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*

/**
 * BadgeGallery — grid of all legacy badge definitions with locked/unlocked states
 * and unlock timestamps (legacy Motivation dashboard render, PF L65594+).
 */
@Composable
fun BadgeGalleryScreen() {
    val c = LocalProColors.current
    val ctx = LocalContext.current
    val ver = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { ver.intValue++ }
        synchronized(com.peakform.fitness.core.ProState.listeners) {
            com.peakform.fitness.core.ProState.listeners.add(l)
        }
        onDispose {
            synchronized(com.peakform.fitness.core.ProState.listeners) {
                com.peakform.fitness.core.ProState.listeners.remove(l)
            }
        }
    }
    val result = remember(ver.intValue) { Badges.evaluate(ctx) }
    val seen = remember(ver.intValue) { Badges.seenTimestamps(ctx) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(4.dp))
        SectionTitle("fa-medal", "Badges")
        Text("${result.unlockedIds.size} of ${result.all.size} unlocked", style = ProType.small, color = c.text3)
        result.all.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { b ->
                    val unlocked = b.id in result.unlockedIds
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (unlocked) c.accentSoft else c.surface)
                            .border(1.dp, if (unlocked) c.accentLine else c.hairline, RoundedCornerShape(14.dp))
                            .padding(12.dp),
                    ) {
                        FaIcon(b.icon, size = 20.sp, tint = if (unlocked) c.accent else c.text3)
                        Spacer(Modifier.height(6.dp))
                        Text(b.name, style = ProType.label, color = if (unlocked) c.text else c.text3)
                        Text(b.desc, style = ProType.small, color = c.text3, maxLines = 3)
                        if (unlocked) {
                            val ts = seen[b.id]
                            Text(
                                if (ts != null && ts > 0) "Unlocked" else "Unlocked",
                                fontSize = 10.sp, color = c.ok,
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(110.dp))
    }
}
