package com.peakform.fitness.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.ui.LocalProColors
import com.peakform.fitness.ui.ProType
import kotlin.math.min
import kotlin.math.max

/**
 * Kit — the shared P4 component set (design-system.md §4):
 * GlassCard, P4Button variants, Chip, Badge, StatCard, Rings, ReadinessGauge,
 * TabPills, ProgressBar, EmptyState, SectionTitle, inputs, switches, steppers.
 */

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = LocalProColors.current
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .shadow(6.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(c.surface)
            .border(1.dp, c.hairline, shape)
            .let { m -> if (onClick != null) m.clickable { onClick() } else m }
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionTitle(icon: String, title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val c = LocalProColors.current
    Row(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        com.peakform.fitness.ui.FaIcon(icon, size = 17.sp, tint = c.accent)
        Spacer(Modifier.width(10.dp))
        Text(title, style = ProType.sectionTitle, color = c.text, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
    HorizontalHairline()
}

@Composable
fun HorizontalHairline() {
    val c = LocalProColors.current
    Box(Modifier.fillMaxWidth().height(2.dp).background(c.hairline.copy(alpha = 0.6f)))
}

enum class BtnStyle { PRIMARY, SECONDARY, DANGER, SUCCESS, INFO, GHOST }

@Composable
fun P4Button(
    text: String,
    modifier: Modifier = Modifier,
    style: BtnStyle = BtnStyle.PRIMARY,
    icon: String? = null,
    enabled: Boolean = true,
    minHeight: Int = 44,
    onClick: () -> Unit,
) {
    val c = LocalProColors.current
    val shape = RoundedCornerShape(10.dp)
    val bg: Brush = when (style) {
        BtnStyle.PRIMARY -> c.accentGradient
        BtnStyle.SECONDARY -> Brush.verticalGradient(listOf(c.surface2, c.surface2))
        BtnStyle.DANGER -> Brush.linearGradient(listOf(c.bad, Color(0xFFEF4444)))
        BtnStyle.SUCCESS -> Brush.linearGradient(listOf(c.ok, Color(0xFF10B981)))
        BtnStyle.INFO -> Brush.linearGradient(listOf(c.accentSoft, c.accentSoft))
        BtnStyle.GHOST -> Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val contentColor = when (style) {
        BtnStyle.PRIMARY -> c.onAccent
        BtnStyle.SECONDARY -> c.text
        BtnStyle.DANGER -> Color(0xFF1B0505)
        BtnStyle.SUCCESS -> Color(0xFF052015)
        BtnStyle.INFO -> c.accent
        BtnStyle.GHOST -> c.text2
    }
    val borderColor = when (style) {
        BtnStyle.PRIMARY -> Color.Transparent
        BtnStyle.INFO -> c.accentLine
        BtnStyle.GHOST -> c.hairline2
        else -> if (c.dark) c.hairline2 else c.hairline
    }
    Row(
        modifier = modifier
            .heightIn(min = minHeight.dp)
            .clip(shape)
            .background(bg)
            .border(1.dp, borderColor, shape)
            .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null) { onClick() }
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            com.peakform.fitness.ui.FaIcon(icon, size = 14.sp, tint = contentColor)
            Spacer(Modifier.width(9.dp))
        }
        Text(text, color = if (enabled) contentColor else contentColor.copy(alpha = 0.45f), style = ProType.label, fontSize = 15.sp)
    }
}

/** The luxury "Start Workout" pill (999px radius, glow pulse). */
@Composable
fun LuxuryStartButton(text: String, icon: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = LocalProColors.current
    Row(
        modifier = modifier
            .heightIn(min = 52.dp)
            .shadow(14.dp, RoundedCornerShape(999.dp), spotColor = c.accent)
            .clip(RoundedCornerShape(999.dp))
            .background(c.accentGradient)
            .clickable { onClick() }
            .padding(horizontal = 30.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        com.peakform.fitness.ui.FaIcon(icon, size = 16.sp, tint = c.onAccent)
        Spacer(Modifier.width(10.dp))
        Text(text, color = c.onAccent, style = ProType.cardTitle, fontFamily = com.peakform.fitness.ui.SpaceGrotesk, fontSize = 17.sp)
    }
}

@Composable
fun P4Chip(text: String, icon: String? = null, tint: Color? = null, accentStyle: Boolean = false) {
    val c = LocalProColors.current
    val color = tint ?: if (accentStyle) c.accent else c.text2
    val bg = if (accentStyle) c.accentSoft else c.surface2
    val border = if (accentStyle) c.accentLine else c.hairline2
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            com.peakform.fitness.ui.FaIcon(icon, size = 11.sp, tint = color)
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = ProType.chip, fontSize = 12.5.sp, color = color)
    }
}

@Composable
fun Badge(text: String, tint: Color) {
    val c = LocalProColors.current
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.15f))
            .padding(horizontal = 14.dp, vertical = 5.dp)
    ) {
        Text(text, color = tint, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

@Composable
fun StatCard(value: String, label: String, onClick: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val c = LocalProColors.current
    GlassCard(modifier = modifier, padding = PaddingValues(8.dp), onClick = onClick) {
        Text(value, style = ProType.statValue, color = c.text, maxLines = 1)
        Spacer(Modifier.height(2.dp))
        Text(label, style = ProType.statLabel, color = c.text3, maxLines = 1)
    }
}

/** Conic-progress donut ring (dashboard readiness gauge / longevity score / P4 rings). */
@Composable
fun ProgressRing(
    pct: Double,
    sizeDp: Int,
    ringWidth: Int,
    color: Color,
    trackColor: Color? = null,
    content: @Composable () -> Unit,
) {
    val c = LocalProColors.current
    val track = trackColor ?: if (c.dark) c.surface3 else c.surface3
    Box(
        modifier = Modifier.size(sizeDp.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(sizeDp.dp)) {
            val stroke = Stroke(width = ringWidth.dp.toPx(), cap = StrokeCap.Round)
            val inset = ringWidth.dp.toPx() / 2
            drawArc(
                color = track,
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                style = stroke, topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(this.size.width - inset * 2, this.size.height - inset * 2),
            )
            drawArc(
                color = color,
                startAngle = -90f, sweepAngle = (360.0 * min(1.0, max(0.0, pct / 100.0))).toFloat(), useCenter = false,
                style = stroke, topLeft = Offset(inset, inset),
                size = androidx.compose.ui.geometry.Size(this.size.width - inset * 2, this.size.height - inset * 2),
            )
        }
        content()
    }
}

@Composable
fun EmptyState(icon: String, title: String, message: String? = null, modifier: Modifier = Modifier) {
    val c = LocalProColors.current
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(c.surface)
            .border(1.5.dp, c.hairline2, RoundedCornerShape(18.dp))
            .padding(34.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        com.peakform.fitness.ui.FaIcon(icon, size = 44.sp, tint = c.text3.copy(alpha = 0.4f))
        Spacer(Modifier.height(14.dp))
        Text(title, style = ProType.cardTitle, fontSize = 20.sp, color = c.text2, textAlign = TextAlign.Center)
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(message, style = ProType.body2, color = c.text3, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun ProgressBar(value: Double, color: Color, max: Double = 100.0, height: Int = 10, modifier: Modifier = Modifier) {
    val c = LocalProColors.current
    val fraction = if (max == 0.0) 0f else (value / max).coerceIn(0.0, 1.0).toFloat()
    Box(
        modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(height.dp / 2))
            .background(if (c.dark) c.surface3 else c.surface3)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .clip(RoundedCornerShape(height.dp / 2))
                .background(Brush.horizontalGradient(listOf(color, color)))
        )
    }
}

@Composable
fun TabPills(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalProColors.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            val active = i == selected
            val bg = if (active) c.accentSoft else c.surface2
            val border = if (active) c.accentLine else c.hairline2
            val color = if (active) c.accent else c.text2
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(bg)
                    .border(1.dp, border, RoundedCornerShape(999.dp))
                    .clickable { onSelect(i) }
                    .padding(horizontal = 14.dp, vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = color, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

@Composable
fun ToggleRow(label: String, sub: String? = null, checked: Boolean, onToggle: (Boolean) -> Unit) {
    val c = LocalProColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = ProType.label, color = c.text)
            if (sub != null) Text(sub, style = ProType.small, color = c.text3)
        }
        Switch(c = c, checked = checked, onToggle = { onToggle(!checked) })
    }
}

@Composable
fun Switch(c: com.peakform.fitness.ui.ProColors, checked: Boolean, onToggle: () -> Unit) {
    val track by animateColorAsState(if (checked) c.ok else c.surface3, label = "sw")
    val knob = if (checked) c.text else c.text3
    Box(
        Modifier
            .size(54.dp, 32.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(track)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggle() },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(knob)
                .then(if (checked) Modifier.align(Alignment.CenterEnd) else Modifier)
        )
    }
}

/** P4 stepper button (±2.5 weight, ± reps). */
@Composable
fun StepButton(symbol: String, onClick: () -> Unit) {
    val c = LocalProColors.current
    Box(
        Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface2)
            .border(1.dp, c.hairline2, RoundedCornerShape(12.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(symbol, color = c.text, fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
    }
}

@Composable
fun KeyValueRow(key: String, value: String) {
    val c = LocalProColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(key, style = ProType.small, color = c.text3, modifier = Modifier.weight(1f))
        Text(value, style = ProType.label, color = c.text, textAlign = TextAlign.End)
    }
}
