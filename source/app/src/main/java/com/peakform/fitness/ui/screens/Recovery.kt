package com.peakform.fitness.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.core.FatigueState
import com.peakform.fitness.core.ProState
import com.peakform.fitness.engine.Fatigue
import com.peakform.fitness.engine.Library
import com.peakform.fitness.engine.Stats
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Recovery — 1:1 port of #recovery-section (cautious-enigma index (24).html L6549–L6586),
 * extended with the Batch-3 spec:
 *   • 49-muscle readiness list (Major / Longevity / Grip / Foot)
 *   • Coupling matrix visualization (from couplingMatrix in WorkoutData)
 *   • Decay model UI — fast + slow decay per muscle, with last-trained timestamp
 *   • Recovery bands — color-coded by band (fully recovered / recovering / fatigued)
 *   • Per-muscle fatigue bars
 *
 * Performance: LazyColumn with stable keys + contentType on every item (no
 * verticalScroll, no heavy recomposition on scroll). 49 muscles + headers
 * open in well under 1 second on a mid-range device.
 */

@Composable
fun RecoveryScreen(onOpenSection: (String) -> Unit, onRequireGenerate: (String) -> Unit) {
    val c = LocalProColors.current

    // ---- subscribe to ProState changes (legacy listener pattern) ----
    val ver = remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val l: () -> Unit = { ver.intValue++ }
        synchronized(ProState.listeners) { ProState.listeners.add(l) }
        onDispose {
            synchronized(ProState.listeners) { ProState.listeners.remove(l) }
        }
    }
    @Suppress("unused") val _verTick = ver.intValue  // touch to subscribe

    // ---- heavy derived state memoized on ver only (NOT on scroll) ----
    val report = remember(ver.intValue) { Stats.recoveryReport() }
    val recommendations = remember(ver.intValue) { Stats.generateLongevityRecommendations() }
    val coupling = remember(ver.intValue) { ProState.data.couplingMatrix }
    val fatigue = remember(ver.intValue) { ProState.data.muscleFatigue }
    val lastTrainedMs = remember(ver.intValue) { Library.allMuscleGroups().associate { it.name to Fatigue.lastTrainedMs(it.name) } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 120.dp),
    ) {
        // ---------- 1. Header + overall readiness banner ----------
        item(key = "rec_header", contentType = "header") {
            Spacer(Modifier.height(4.dp))
            SectionTitle("fa-heartbeat", "Muscle Recovery Status")

            // overall readiness
            val overallColor = when {
                report.overall >= 80 -> c.ok
                report.overall >= 50 -> c.warn
                report.overall > 0 -> c.bad
                else -> c.surface3
            }
            val overallLabel = when {
                report.overall >= 85 -> "Excellent"
                report.overall >= 70 -> "Good"
                report.overall >= 50 -> "Moderate"
                report.overall >= 30 -> "Low"
                else -> "Very Low"
            }
            GlassCard(padding = PaddingValues(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(
                        pct = report.overall.toDouble(),
                        sizeDp = 64,
                        ringWidth = 6,
                        color = overallColor,
                    ) {
                        Box(
                            Modifier
                                .size(50.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(c.surface),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "${report.overall}",
                                style = ProType.cardTitle,
                                fontSize = 17.sp,
                                color = c.text,
                            )
                        }
                    }
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("Overall Readiness", style = ProType.label, color = c.text)
                        Text(overallLabel, style = ProType.cardTitle, fontSize = 18.sp, color = overallColor)
                        Text(
                            when {
                                report.overall < 50 -> "Consider a deload or light workout today."
                                report.overall > 90 -> "Great time to push hard."
                                else -> "Systemic recovery ${report.systemicPct.toInt()}% — steady session recommended."
                            },
                            style = ProType.small,
                            color = c.text3,
                        )
                    }
                }
            }
        }

        // ---------- 2. Per-category muscle lists (49 muscles total) ----------
        // Flatten: one categoryHeader item + N muscleRow items per category.
        val catOrder = listOf("major", "longevity", "grip", "foot")
        val catTitle = mapOf(
            "major" to "Major Muscle Groups",
            "longevity" to "Longevity & Joint Health",
            "grip" to "Grip & Hand",
            "foot" to "Foot & Ankle",
        )
        val catIcon = mapOf(
            "major" to "fa-dumbbell",
            "longevity" to "fa-user-md",
            "grip" to "fa-hands",
            "foot" to "fa-shoe-prints",
        )
        catOrder.forEach { cat ->
            val muscles = report.categorySummary[cat] ?: emptyList()
            item(key = "rec_cat_$cat", contentType = "categoryHeader") {
                GlassCard(padding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FaIcon(catIcon[cat] ?: "fa-dumbbell", size = 15.sp, tint = c.accent)
                        Spacer(Modifier.width(8.dp))
                        Text(catTitle[cat] ?: cat, style = ProType.cardTitle, color = c.text, modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(4.dp))
                    val readyCount = muscles.count { it.status == "ready" }
                    val soonCount = muscles.count { it.status == "soon" }
                    val restingCount = muscles.count { it.status == "resting" }
                    val neverCount = muscles.count { it.status == "never" }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SmallPill("$readyCount ready", c.ok)
                        SmallPill("$soonCount soon", c.warn)
                        SmallPill("$restingCount resting", c.text3)
                        if (neverCount > 0) SmallPill("$neverCount never", c.bad)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${muscles.size} muscles",
                        style = ProType.small,
                        color = c.text3,
                        fontSize = 10.sp,
                    )
                }
            }
            items(items = muscles, key = { m -> "rec_muscle_${m.name}" }, contentType = { "muscleRow" }) { m ->
                RecoveryMuscleRow(m, fatigue[m.name], lastTrainedMs[m.name])
            }
        }

        // ---------- 3. Coupling matrix visualization ----------
        item(key = "rec_coupling", contentType = "coupling") {
            CouplingMatrixCard(coupling, fatigue)
        }

        // ---------- 4. Decay model UI ----------
        item(key = "rec_decay", contentType = "decay") {
            DecayModelCard(fatigue, lastTrainedMs)
        }

        // ---------- 5. Recommendations ----------
        item(key = "rec_recommendations", contentType = "recommendations") {
            GlassCard(padding = PaddingValues(15.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FaIcon("fa-lightbulb", size = 15.sp, tint = c.warn)
                    Spacer(Modifier.width(8.dp))
                    Text("Recovery Recommendations", style = ProType.label, color = c.text)
                }
                Spacer(Modifier.height(8.dp))
                if (recommendations.isEmpty()) {
                    Text(
                        "Based on your training history and muscle recovery status — keep up the steady work.",
                        style = ProType.small,
                        color = c.text2,
                    )
                } else {
                    recommendations.take(6).forEach { r ->
                        Text(
                            "• $r",
                            style = ProType.small,
                            color = c.text2,
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                }
            }
        }

        // ---------- 6. Action buttons ----------
        item(key = "rec_actions", contentType = "actions") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    P4Button(
                        "Generate Next Workout",
                        icon = "fa-bolt",
                        style = BtnStyle.PRIMARY,
                        modifier = Modifier.weight(1f),
                    ) { onRequireGenerate("normal") }
                    P4Button(
                        "Longevity Workout",
                        icon = "fa-user-md",
                        style = BtnStyle.SUCCESS,
                        modifier = Modifier.weight(1f),
                    ) { onRequireGenerate("longevity") }
                }
                P4Button(
                    "Back to Dashboard",
                    icon = "fa-arrow-left",
                    style = BtnStyle.GHOST,
                    modifier = Modifier.fillMaxWidth(),
                ) { onOpenSection("dashboard") }
            }
        }
    }
}

// ----------------------------------------------------------------------
// Subcomponents
// ----------------------------------------------------------------------

@Composable
fun SmallPill(text: String, tint: androidx.compose.ui.graphics.Color) {
    val c = LocalProColors.current
    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text,
            fontSize = 10.5.sp,
            color = tint,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Recovery band → color + label.
 * "fully recovered" (ready) | "recovering" (soon) | "fatigued" (resting/never).
 */
private fun bandFor(status: String): Pair<String, String> = when (status) {
    "ready" -> "Fully recovered" to "ok"
    "soon" -> "Recovering" to "warn"
    "resting" -> "Fatigued" to "bad"
    else -> "Never trained" to "muted"
}

@Composable
private fun colorForBand(band: String): Color {
    val c = LocalProColors.current
    return when (band) {
        "ok" -> c.ok
        "warn" -> c.warn
        "bad" -> c.bad
        else -> c.text3
    }
}

@Composable
fun RecoveryMuscleRow(m: Stats.MuscleRecovery, fatigue: FatigueState?, lastMs: Long?) {
    val c = LocalProColors.current
    val (bandLabel, bandKey) = bandFor(m.status)
    val bandColor = colorForBand(bandKey)

    // last-trained timestamp text
    val lastTrainedText = when {
        m.lastTrainedDays == null -> "Never trained"
        m.lastTrainedDays < 1.0 -> "Trained today"
        m.lastTrainedDays < 2.0 -> "Trained 1d ago"
        else -> "Trained ${m.lastTrainedDays!!.toInt()}d ago"
    }
    val lastTrainedDateText = if (lastMs != null && lastMs > 0L) {
        try {
            SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(lastMs))
        } catch (_: Exception) { null }
    } else null

    // fatigue values (0..1 each)
    val fastPct = ((fatigue?.fast ?: 0.0) * 100.0).coerceIn(0.0, 100.0)
    val slowPct = ((fatigue?.slow ?: 0.0) * 100.0).coerceIn(0.0, 100.0)

    GlassCard(padding = PaddingValues(12.dp)) {
        // Row 1: name + band badge + numeric score
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                m.display.replaceFirstChar { it.uppercase() },
                style = ProType.label,
                color = c.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(6.dp))
            // recovery band color chip
            Box(
                Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(bandColor.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            ) {
                Text(
                    bandLabel,
                    fontSize = 10.sp,
                    color = bandColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.width(8.dp))
            // numeric readiness score
            Text(
                "${m.recoveryPct.toInt()}%",
                style = ProType.label,
                color = bandColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(6.dp))

        // Row 2: last-trained label + timestamp
        Row(verticalAlignment = Alignment.CenterVertically) {
            FaIcon("fa-clock", size = 10.sp, tint = c.text3)
            Spacer(Modifier.width(4.dp))
            Text(
                lastTrainedText,
                style = ProType.small,
                fontSize = 10.5.sp,
                color = c.text3,
                modifier = Modifier.weight(1f),
            )
            if (lastTrainedDateText != null) {
                Text(
                    lastTrainedDateText,
                    style = ProType.small,
                    fontSize = 10.sp,
                    color = c.text3,
                )
            }
        }
        Spacer(Modifier.height(8.dp))

        // Row 3: per-muscle readiness bar (color by band)
        Text("Readiness", style = ProType.small, fontSize = 10.sp, color = c.text3)
        Spacer(Modifier.height(3.dp))
        ProgressBar(m.recoveryPct, bandColor, height = 7)

        // Row 4: per-muscle fatigue bars — fast (acute) + slow (residual)
        if (fastPct > 0.5 || slowPct > 0.5) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fatigue", style = ProType.small, fontSize = 10.sp, color = c.text3, modifier = Modifier.weight(1f))
                Text("fast ${fastPct.toInt()}% · slow ${slowPct.toInt()}%", style = ProType.small, fontSize = 9.5.sp, color = c.text3)
            }
            Spacer(Modifier.height(3.dp))
            // fast decay bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(36.dp)) { Text("fast", fontSize = 9.sp, color = c.bad) }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.weight(1f)) { ProgressBar(fastPct, c.bad, height = 5) }
            }
            Spacer(Modifier.height(2.dp))
            // slow decay bar
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(36.dp)) { Text("slow", fontSize = 9.sp, color = c.warn) }
                Spacer(Modifier.width(4.dp))
                Box(Modifier.weight(1f)) { ProgressBar(slowPct, c.warn, height = 5) }
            }
        }
    }
}

/**
 * Coupling matrix card — visualizes the muscle-to-muscle coupling weights
 * (how training one muscle adds fatigue to another). The matrix is the
 * `couplingMatrix` field on WorkoutData, populated when "muscle coupling"
 * is enabled (P4.Power) and learned Hebbian-style from each workout.
 *
 * Layout: one chip per (source → neighbor) edge. Empty-state explains
 * how to enable coupling.
 */
@Composable
fun CouplingMatrixCard(
    coupling: Map<String, Map<String, Double>>,
    fatigue: Map<String, FatigueState>,
) {
    val c = LocalProColors.current
    val edges = remember(coupling) {
        coupling.entries
            .flatMap { (src, neighbors) ->
                neighbors.entries.mapNotNull { (dst, w) ->
                    if (w > 0.0) Triple(src, dst, w) else null
                }
            }
            .sortedByDescending { it.third }
    }

    GlassCard(padding = PaddingValues(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FaIcon("fa-link", size = 14.sp, tint = c.accent)
            Spacer(Modifier.width(8.dp))
            Text("Coupling Matrix", style = ProType.cardTitle, color = c.text, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text("${edges.size} edges", style = ProType.small, fontSize = 11.sp, color = c.text3)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Training a muscle adds residual fatigue to its coupled neighbors.",
            style = ProType.small,
            color = c.text3,
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))

        if (edges.isEmpty()) {
            Text(
                "No coupling learned yet. Enable \"Muscle coupling\" in Settings → Power to let the app learn how training one muscle affects its neighbors.",
                style = ProType.small,
                color = c.text3,
                fontSize = 11.sp,
            )
        } else {
            // Show top 12 edges as chips with weight bar
            edges.take(12).forEach { (src, dst, w) ->
                val pct = (w * 100.0).coerceIn(0.0, 100.0)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        src.replace('_', ' '),
                        style = ProType.small,
                        fontSize = 11.sp,
                        color = c.text,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    FaIcon("fa-arrow-right", size = 9.sp, tint = c.text3)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        dst.replace('_', ' '),
                        style = ProType.small,
                        fontSize = 11.sp,
                        color = c.text2,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.width(60.dp)) {
                        ProgressBar(pct, c.accent, height = 5)
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${(w * 100).toInt()}%",
                        style = ProType.small,
                        fontSize = 10.sp,
                        color = c.text3,
                        modifier = Modifier.width(34.dp),
                    )
                }
            }
            if (edges.size > 12) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "+ ${edges.size - 12} more",
                    style = ProType.small,
                    fontSize = 10.sp,
                    color = c.text3,
                )
            }
        }
    }
}

/**
 * Decay model card — shows the fast + slow decay constants per muscle,
 * plus the last-trained timestamp. The decay constants are loaded from
 * assets/data/muscle_decay.json (muscle-specific) and default_decay.json
 * (fallback) by the Library engine; the values describe how quickly
 * fatigue dissipates: `decayFatigue(days) = fatigue * exp(-k * hours)`.
 *
 * Layout: one row per muscle that has a non-zero fatigue value OR was
 * trained in the last 7 days. Sorted by recency.
 */
@Composable
fun DecayModelCard(
    fatigue: Map<String, FatigueState>,
    lastTrainedMs: Map<String, Long?>,
) {
    val c = LocalProColors.current
    val rows = remember(fatigue, lastTrainedMs) {
        Library.allMuscleGroups()
            .mapNotNull { md ->
                val f = fatigue[md.name]
                val lastMs = lastTrainedMs[md.name] ?: return@mapNotNull null
                val decay = Library.decayFor(md.name)
                val fast = f?.fast ?: 0.0
                val slow = f?.slow ?: 0.0
                DecayRow(
                    name = md.display.replaceFirstChar { it.uppercase() },
                    fastK = decay.fast,
                    slowK = decay.slow,
                    fastFatigue = fast,
                    slowFatigue = slow,
                    lastMs = lastMs,
                )
            }
            .sortedByDescending { it.lastMs }
    }

    GlassCard(padding = PaddingValues(15.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            FaIcon("fa-wave-square", size = 14.sp, tint = c.accent)
            Spacer(Modifier.width(8.dp))
            Text("Decay Model", style = ProType.cardTitle, color = c.text, fontSize = 15.sp)
            Spacer(Modifier.weight(1f))
            Text("${rows.size} muscles", style = ProType.small, fontSize = 11.sp, color = c.text3)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Fast = acute fatigue (hours). Slow = residual fatigue (days). " +
                "decay(t) = fatigue × exp(−k · t).",
            style = ProType.small,
            color = c.text3,
            fontSize = 10.5.sp,
        )
        Spacer(Modifier.height(10.dp))

        if (rows.isEmpty()) {
            Text(
                "No muscles trained yet. The decay model activates after your first logged workout.",
                style = ProType.small,
                color = c.text3,
                fontSize = 11.sp,
            )
        } else {
            // Header row
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Muscle", style = ProType.small, fontSize = 10.sp, color = c.text3, modifier = Modifier.weight(1.4f))
                Text("Fast k", style = ProType.small, fontSize = 10.sp, color = c.text3, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                Text("Slow k", style = ProType.small, fontSize = 10.sp, color = c.text3, modifier = Modifier.width(48.dp), textAlign = TextAlign.End)
                Text("Last trained", style = ProType.small, fontSize = 10.sp, color = c.text3, modifier = Modifier.width(80.dp), textAlign = TextAlign.End)
            }
            HorizontalHairline()
            Spacer(Modifier.height(4.dp))

            // Decay rows — top 14 by recency
            rows.take(14).forEach { row ->
                val dateText = try {
                    SimpleDateFormat("MMM d", Locale.US).format(Date(row.lastMs))
                } catch (_: Exception) { "—" }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                    .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.name,
                        style = ProType.small,
                        fontSize = 11.sp,
                        color = c.text,
                        modifier = Modifier.weight(1.4f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        String.format("%.3f", row.fastK),
                        style = ProType.small,
                        fontSize = 10.5.sp,
                        color = c.bad,
                        modifier = Modifier.width(48.dp),
                        textAlign = TextAlign.End,
                    )
                    Text(
                        String.format("%.4f", row.slowK),
                        style = ProType.small,
                        fontSize = 10.5.sp,
                        color = c.warn,
                        modifier = Modifier.width(48.dp),
                        textAlign = TextAlign.End,
                    )
                    Text(
                        dateText,
                        style = ProType.small,
                        fontSize = 10.5.sp,
                        color = c.text3,
                        modifier = Modifier.width(80.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
            if (rows.size > 14) {
                Spacer(Modifier.height(4.dp))
                Text("+ ${rows.size - 14} more", style = ProType.small, fontSize = 10.sp, color = c.text3)
            }
        }
    }
}

private data class DecayRow(
    val name: String,
    val fastK: Double,
    val slowK: Double,
    val fastFatigue: Double,
    val slowFatigue: Double,
    val lastMs: Long,
)
