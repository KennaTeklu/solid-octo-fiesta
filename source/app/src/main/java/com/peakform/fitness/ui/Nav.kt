package com.peakform.fitness.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.ui.components.GlassCard
import com.peakform.fitness.ui.components.P4Chip

/**
 * Nav — the P4 glass dock (5 tabs + Train FAB) and the "Everything else" bottom sheet.
 * Structural fix for P1/nav: the dock lives OUTSIDE scrollable content, so it can
 * never disappear on any screen (legacy p4-tabbar-lock semantics by construction).
 */

data class TabDef(val id: String, val icon: String, val label: String, val fab: Boolean = false, val sheet: Boolean = false)

val TABS = listOf(
    TabDef("dashboard", "fa-house", "Home"),
    TabDef("workout", "fa-dumbbell", "Train", fab = true),
    TabDef("library", "fa-book-open", "Library"),
    TabDef("progress", "fa-chart-line", "Progress"),
    TabDef("more", "fa-ellipsis", "More", sheet = true),
)

data class SheetCell(val id: String, val icon: String, val label: String)

val SHEET_CELLS = listOf(
    SheetCell("history", "fa-clock-rotate-left", "History"),
    SheetCell("recovery", "fa-heart-pulse", "Recovery"),
    SheetCell("health", "fa-notes-medical", "Health"),
    SheetCell("badges", "fa-medal", "Badges"),
    SheetCell("profiles", "fa-user-group", "People"),
    SheetCell("snapshots", "fa-camera", "Snaps"),
    SheetCell("mycycle", "fa-calendar-days", "My Cycle"),
    SheetCell("settings", "fa-gear", "Settings"),
    SheetCell("resume", "fa-play", "Resume"),
    SheetCell("export", "fa-file-export", "Export"),
    SheetCell("backup", "fa-cloud-arrow-down", "Backup"),
    SheetCell("themes", "fa-palette", "Themes"),
    SheetCell("glossary", "fa-spell-check", "Words"),
    SheetCell("studio", "fa-flask-vial", "Studio"),
    SheetCell("legal", "fa-scale-balanced", "Legal"),
    SheetCell("commands", "fa-terminal", "Commands"),
)

@Composable
fun GlassDock(
    activeTab: String,
    hasDraft: Boolean,
    onSelect: (String) -> Unit,
) {
    val c = LocalProColors.current
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
        val dockWidth = (maxWidth - 16.dp).coerceAtMost(430.dp)
        Row(
            modifier = Modifier
                .width(dockWidth)
                .padding(horizontal = 8.dp)
                .shadow(18.dp, RoundedCornerShape(26.dp), spotColor = Color.Black)
                .clip(RoundedCornerShape(26.dp))
                .background(c.glass)
                .border(1.dp, c.hairline2, RoundedCornerShape(26.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TABS.forEach { tab ->
                val active = activeTab == tab.id && !tab.sheet
                if (tab.fab) {
                    // Train FAB
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelect(tab.id) }
                            .padding(horizontal = 8.dp),
                    ) {
                        Box(
                            Modifier
                                .offset(y = (-10).dp)
                                .size(56.dp)
                                .shadow(10.dp, CircleShape, spotColor = c.accent)
                                .clip(CircleShape)
                                .background(if (active) c.accentGradient else Brush.verticalGradient(listOf(c.surface2, c.surface2)))
                                .border(1.dp, if (active) Color.Transparent else c.hairline2, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            com.peakform.fitness.ui.FaIcon("fa-dumbbell", size = 19.sp, tint = if (active) c.onAccent else c.text2)
                            // FIX (audit): hasDraft param was unused — restore the legacy "started" badge dot.
                            if (hasDraft) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(x = 4.dp, y = 2.dp)
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(c.ok)
                                        .border(1.5.dp, c.bg, CircleShape),
                                )
                            }
                        }
                        Text(
                            tab.label, style = ProType.tab, color = if (active) c.accent else c.text3,
                            modifier = Modifier.offset(y = (-8).dp),
                        )
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelect(tab.id) }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                            .widthIn(min = 52.dp),
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (active) c.accent.copy(alpha = 0.16f) else Color.Transparent)
                                .border(
                                    2.dp,
                                    if (active) c.accent.copy(alpha = 0.52f) else Color.Transparent,
                                    CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            com.peakform.fitness.ui.FaIcon(tab.icon, size = 16.sp, tint = if (active) c.accent else c.text3)
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(tab.label, style = ProType.tab, color = if (active) c.accent else c.text3, maxLines = 1)
                    }
                }
            }
        }
    }
}


@Composable
fun MoreSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    quickChips: @Composable RowScope.() -> Unit = {},
) {
    val c = LocalProColors.current
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                // FIX (audit): hardcoded scrim bypassed the theme (wrong tint in light mode).
                .background(c.bg.copy(alpha = 0.55f))
                .clickable(onClick = onDismiss)
        )
    }
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(tween(320)) { it } + fadeIn(tween(220)),
        exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(180)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            Modifier
                .fillMaxSize(),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .clip(RoundedCornerShape(26.dp, 26.dp, 0.dp, 0.dp))
                    .background(c.glass2)
                    .border(1.dp, c.hairline2, RoundedCornerShape(26.dp, 26.dp, 0.dp, 0.dp))
                    // FIX (audit): clickable(enabled=false) is inert — taps in gaps fell through
                    // to the scrim and dismissed the sheet mid-selection. Consume touches instead.
                    .pointerInput(Unit) { detectTapGestures { } }
                    .padding(horizontal = 16.dp)
                    .padding(top = 10.dp, bottom = 26.dp),
            ) {
                Box(
                    Modifier
                        .width(44.dp)
                        .height(5.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(c.hairline2)
                        .align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(12.dp))
                Text("Everything else", style = ProType.cardTitle, color = c.text)
                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { quickChips() }
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SHEET_CELLS.chunked(4).forEach { row ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { cell ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(c.glass)
                                        .clickable { onSelect(cell.id) }
                                        .padding(vertical = 13.dp, horizontal = 4.dp),
                                ) {
                                    com.peakform.fitness.ui.FaIcon(cell.icon, size = 19.sp, tint = c.accent)
                                    Spacer(Modifier.height(6.dp))
                                    Text(cell.label, style = ProType.tab, color = c.text2, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center)
                                }
                            }
                            repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}
