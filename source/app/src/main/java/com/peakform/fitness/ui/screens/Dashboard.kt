package com.peakform.fitness.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.peakform.fitness.core.ProState
import com.peakform.fitness.core.Health
import com.peakform.fitness.engine.Generator
import com.peakform.fitness.engine.Stats
import com.peakform.fitness.ui.*
import com.peakform.fitness.ui.components.*
import kotlinx.coroutines.launch

/**
 * Dashboard — 1:1 port of #dashboard-section (scr-dashboard.md):
 * readiness gauge, welcome badge, stat row (6 cards incl. elite date + forecast),
 * P4 weekly rings, recommendation card, composite progress chart with range pills,
 * next-workout card, Start Workout luxury button + Resume.
 */

@Composable
fun DashboardScreen(
    onStartWorkout: () -> Unit,
    onResumeWorkout: () -> Unit,
    onOpenSection: (String) -> Unit,
) {
    val c = LocalProColors.current
    val scope = rememberCoroutineScope()
    val stateVer = remember { mutableIntStateOf(0) }
    // recompute on each entry + data change
    DisposableEffect(Unit) {
        val l: () -> Unit = { stateVer.intValue++ }
        synchronized(ProState.listeners) { ProState.listeners.add(l) }
        onDispose {
            synchronized(ProState.listeners) { ProState.listeners.remove(l) }
        }
    }
    var ver = stateVer.intValue

    val user = ProState.data.user
    val hasDraft = ProState.currentWorkout != null && ProState.currentWorkout!!.exercises.isNotEmpty()
    val draftIncomplete = hasDraft && ProState.currentWorkout!!.exercises.any { !it.isLogged }

    // readiness (from recovery report)
    val recovery = remember(ver) { Stats.recoveryReport() }
    val streak = remember(ver) { Stats.calculateStreak() }
    val totalWorkouts = ProState.data.workouts.size
    val totalVolume = remember(ver) { ProState.data.workouts.sumOf { it.summary?.totalVolume ?: 0.0 } }
    val strengthProgress = remember(ver) { Stats.calculateOverallStrengthProgress() }
    val longevity = remember(ver) { Stats.calculateLongevityScore() }
    val eliteDate = remember(ver) { Stats.projectedEliteDate() }
    val rings = remember(ver) { Stats.ringsData() }
    val composite = remember(ver) { Stats.dashboardComposite(null) }
    val nextWorkoutDate = remember(ver) { Stats.nextWorkoutDate() }
    val firstName = user.name.trim().split(Regex("\\s+")).firstOrNull().takeUnless { it.isNullOrEmpty() } ?: "Athlete"
    val expLabel = user.experience.replaceFirstChar { it.uppercase() }.ifBlank { "Beginner" }

    val gaugeColor = when {
        recovery.overall >= 80 -> c.ok
        recovery.overall >= 60 -> c.warn
        else -> c.bad
    }
    val gaugeLabel = when {
        recovery.overall >= 80 -> "Fully Recovered"
        recovery.overall >= 60 -> "Recovering"
        recovery.overall >= 40 -> "Fatigued"
        else -> "Very Fatigued"
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(4.dp))

        // ---- data-safety nudges (FIX (audit) #35: the legacy app nudged via
        // p4_last_backup; the native port shipped without any backup nudge). ----
        val lastBackupIso = remember(ver) {
            com.peakform.fitness.ui.Fa.appContext?.let { com.peakform.fitness.core.ProPrefs.get(it, "p4_last_backup") }
        }
        val showBackupNudge = remember(ver, lastBackupIso) {
            com.peakform.fitness.core.Nudges.shouldNudgeBackup(lastBackupIso, totalWorkouts, System.currentTimeMillis())
        }
        val retestBanner = remember(ver) {
            notifFlag("retest_reminder") && com.peakform.fitness.core.Nudges.retestDue(ProState.data.exercises, System.currentTimeMillis())
        }
        if (showBackupNudge || retestBanner) {
            GlassCard(padding = PaddingValues(14.dp), onClick = { onOpenSection("settings") }) {
                if (showBackupNudge) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.peakform.fitness.ui.FaIcon("fa-cloud-arrow-down", size = 15.sp, tint = c.warn)
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text("Time for a backup", style = ProType.label, color = c.text)
                            Text(
                                if (lastBackupIso.isNullOrBlank()) "You have $totalWorkouts logged workouts and no backup yet. Tap to open Settings → Data & backup."
                                else "Your last backup is over two weeks old. Tap to open Settings → Data & backup.",
                                style = ProType.small, color = c.text3,
                            )
                        }
                    }
                }
                if (retestBanner) {
                    if (showBackupNudge) Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        com.peakform.fitness.ui.FaIcon("fa-gauge-high", size = 15.sp, tint = c.accent)
                        Spacer(Modifier.width(9.dp))
                        Column {
                            Text("1RM estimates are getting stale", style = ProType.label, color = c.text)
                            Text("Some calibrated lifts haven't been re-tested in 8+ weeks. Run a 1RM test on your next session.", style = ProType.small, color = c.text3)
                        }
                    }
                }
            }
        }

        // ---- HL8: tier C generation-lock banner ----
        val healthLocked = remember(ver) {
            com.peakform.fitness.ui.Fa.appContext?.let { Health.isGenerationLocked(it) } ?: false
        }
        if (healthLocked) {
            GlassCard(padding = PaddingValues(14.dp), onClick = { onOpenSection("health") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.peakform.fitness.ui.FaIcon("fa-notes-medical", size = 15.sp, tint = c.bad)
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text("Doctor sign-off needed", style = ProType.label, color = c.text)
                        Text("Your health screen result requires a doctor's clearance before workout generation. Tap to review.", style = ProType.small, color = c.text3)
                    }
                }
            }
        }

        // ---- X8: life-stage guidance card ----
        val lifeTip = remember(ver, user.gender, user.birthDate) {
            val age = user.birthDate?.let { ageOf(it) } ?: 0
            when {
                age <= 0 -> null
                age < 25 -> "Building the habit now pays compound interest — technique quality in your 20s sets your ceiling."
                age < 40 -> "Prime building years. Push progressive overload and protect sleep."
                age < 55 -> "Muscle retention becomes the priority — protein and resistance work matter more than ever."
                age < 65 -> "Power training (fast, light lifts) protects fast-twitch fibers that age first."
                else -> "Balance, grip and leg strength predict long-term independence. Train them weekly."
            }
        }
        if (lifeTip != null) {
            GlassCard(padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.peakform.fitness.ui.FaIcon("fa-user-doctor", size = 15.sp, tint = c.accent)
                    Spacer(Modifier.width(9.dp))
                    Column {
                        Text("Life-stage guidance", style = ProType.label, color = c.text)
                        Text(lifeTip, style = ProType.small, color = c.text3)
                    }
                }
            }
        }

        // ---- readiness gauge + welcome badge ----
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                ProgressRing(pct = recovery.overall.toDouble(), sizeDp = 80, ringWidth = 7, color = gaugeColor) {
                    Box(Modifier.size(64.dp).clip(RoundedCornerShape(999.dp)).background(c.surface), contentAlignment = Alignment.Center) {
                        Text("${recovery.overall}", style = ProType.cardTitle, fontSize = 22.sp, color = c.text)
                    }
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Badge(expLabel, c.accent)
                Spacer(Modifier.height(6.dp))
                Text(gaugeLabel, style = ProType.label, color = c.text2)
                Text("Body readiness", style = ProType.small, color = c.text3)
            }
        }

        // ---- rings row (P4.Rings) ----
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            rings.take(4).forEach { ring ->
                val ringColor = when (ring.color) {
                    "var(--p4-ok)" -> c.ok
                    "var(--p4-warn)" -> c.warn
                    "var(--p4-bad)" -> c.bad
                    else -> c.accent
                }
                GlassCard(
                    modifier = Modifier.weight(1f).clickable { onOpenSection(ring.section) },
                    padding = PaddingValues(vertical = 14.dp, horizontal = 8.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        ProgressRing(pct = if (ring.max > 0) ring.value / ring.max * 100.0 else 0.0, sizeDp = 74, ringWidth = 5, color = ringColor) {
                            Box(Modifier.size(58.dp).clip(RoundedCornerShape(999.dp)).background(c.surface), contentAlignment = Alignment.Center) {
                                Text(
                                    when {
                                        ring.id == "fire" -> "${ring.value.toInt()}"
                                        ring.target == "n/max" -> "${ring.value.toInt()}/${ring.max.toInt()}"
                                        else -> "${ring.value.toInt()}%"
                                    },
                                    style = ProType.cardTitle, fontSize = 15.sp, color = c.text,
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(ring.label.uppercase(), style = ProType.statLabel, color = c.text3, textAlign = TextAlign.Center, maxLines = 1)
                    }
                }
            }
        }

        // ---- workout preview card ----
        GlassCard(padding = PaddingValues(20.dp)) {
            val cw = ProState.currentWorkout
            if (cw == null || cw.exercises.isEmpty()) {
                Text("No Workout Scheduled", style = ProType.cardTitle, color = c.text)
                Spacer(Modifier.height(6.dp))
                Text("Generate a workout to get started.", style = ProType.body2, color = c.text2)
            } else {
                Text(cw.name, style = ProType.cardTitle, color = c.text)
                Spacer(Modifier.height(10.dp))
                KeyValueRow("Workout", cw.name)
                KeyValueRow("Exercises", "${cw.exercises.count { !it.isWarmup && !it.isCooldown }}")
                KeyValueRow("Status", if (draftIncomplete) "In Progress" else "Ready to Start")
                if (draftIncomplete) {
                    Spacer(Modifier.height(8.dp))
                    Text("Next Steps: Tap \"Resume Workout\" to continue where you left off.", style = ProType.small, color = c.text3)
                }
            }
        }

        // ---- stat row (6 cards) ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("$totalWorkouts", "Workouts", modifier = Modifier.weight(1f))
            StatCard("$streak", "Day Streak", modifier = Modifier.weight(1f))
            StatCard(Stats.formatNumber(totalVolume), "Volume", modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("${strengthProgress.round1()}%", "Progress", modifier = Modifier.weight(1f))
            StatCard("${longevity.total}", "Longevity", modifier = Modifier.weight(1f))
            StatCard(eliteDate.ifEmpty { "--" }, "Peak Est.", modifier = Modifier.weight(1f))
        }

        // ---- SC8: SBD forecast total + strength score (elite card detail) ----
        val sbd = remember(ver) { Stats.strengthForecast() }
        val sbdTotal = remember(ver) {
            val byName = sbd.associate { it.lift to it.est1RM }
            listOf("squat", "bench_press", "deadlift").mapNotNull { byName[it] }.sum()
        }
        if (sbdTotal > 0) {
            GlassCard(padding = PaddingValues(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Projected S/B/D Total", style = ProType.small, color = c.text3)
                        Text("${sbdTotal.toInt()} lbs", style = ProType.statValue, fontSize = 24.sp, color = c.text)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("Strength score", style = ProType.small, color = c.text3)
                        Text("${Stats.wilksScore(sbdTotal)}", style = ProType.statValue, fontSize = 24.sp, color = c.accent)
                    }
                }
            }
        }

        // ---- recommendation card ----
        GlassCard(padding = PaddingValues(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.peakform.fitness.ui.FaIcon("fa-user-md", size = 16.sp, tint = c.accent)
                Spacer(Modifier.width(8.dp))
                Text("Today's Recommendation", style = ProType.label, color = c.text)
                Spacer(Modifier.weight(1f))
                Text("See all", style = ProType.small, color = c.accent, modifier = Modifier.clickable { onOpenSection("recovery") })
            }
            Spacer(Modifier.height(8.dp))
            val rec = remember(ver) {
                val sys = recovery.systemicPct
                val ready = recovery.muscles.filter { it.status == "ready" && it.category == "major" }
                when {
                    sys < 60.0 -> "Consider a deload or light workout — your system is still recovering."
                    sys > 90.0 -> "Great time to push hard — full systemic recovery."
                    ready.isNotEmpty() -> "Priority muscles to train today: " + ready.take(3).joinToString(", ") { it.display }
                    else -> "Moderate readiness — a steady session today is ideal."
                }
            }
            Text(rec, style = ProType.body2, color = c.text2)
        }

        // ---- progress overview chart ----
        GlassCard(padding = PaddingValues(16.dp)) {
            SectionTitle("fa-chart-line", "Progress Overview")
            CompositeChart(points = composite)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Based on strength, volume, consistency and effort", style = ProType.small, color = c.text3, modifier = Modifier.weight(1f))
            }
        }

        // ---- next workout date ----
        GlassCard(padding = PaddingValues(16.dp), onClick = { onOpenSection("workout") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Next Workout", style = ProType.small, color = c.text3)
                    Spacer(Modifier.height(4.dp))
                    Text(nextWorkoutDate, style = ProType.cardTitle, color = c.text)
                    Text("Based on your recovery status and workout rotation", style = ProType.small, color = c.text3)
                }
                com.peakform.fitness.ui.FaIcon("fa-arrow-right", size = 14.sp, tint = c.accent)
            }
        }

        // ---- start / resume ----
        if (draftIncomplete) {
            P4Button("Resume Workout", onClick = onResumeWorkout, style = BtnStyle.INFO, icon = "fa-play", modifier = Modifier.fillMaxWidth())
        }
        LuxuryStartButton(
            text = "Start Workout",
            icon = "fa-bolt",
            onClick = onStartWorkout,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(16.dp)) // dock clearance
    }
}

private fun Double.round1(): String = String.format("%.1f", this)

/** Composite dashboard chart — native Canvas line with gradient fill. */
@Composable
fun CompositeChart(points: List<Stats.CompositePoint>) {
    val c = LocalProColors.current
    val minVal = points.minOfOrNull { it.value } ?: 0.0
    val maxVal = points.maxOfOrNull { it.value } ?: 1.0
    val range = (maxVal - minVal).takeIf { it > 1e-9 } ?: 1.0
    Box(Modifier.fillMaxWidth()) {
        androidx.compose.foundation.Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(c.bg2)
        ) {
            if (points.size < 2) return@Canvas
            val w = size.width
            val h = size.height
            val stepX = w / (points.size - 1)
            val path = androidx.compose.ui.graphics.Path()
            points.forEachIndexed { i, p ->
                val x = i * stepX
                val y = h - (((p.value - minVal) / range) * (h * 0.86) + h * 0.07).toFloat()
                if (i == 0) path.moveTo(x.toFloat(), y) else path.lineTo(x.toFloat(), y)
            }
            // fill
            val fill = androidx.compose.ui.graphics.Path().apply {
                addPath(path)
                lineTo(w, h); lineTo(0f, h); close()
            }
            drawPath(fill, Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.28f), Color.Transparent)))
            drawPath(path, c.accent, style = Stroke(width = 5f, cap = StrokeCap.Round))
        }
        if (points.size < 2) {
            Text(
                "Log a few workouts to see your trend.",
                style = ProType.small,
                color = c.text3,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

private fun ageOf(birth: String): Int {
    if (!birth.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return 0
    return try {
        val cal = java.util.Calendar.getInstance()
        var age = cal.get(java.util.Calendar.YEAR) - birth.substring(0, 4).toInt()
        if (cal.get(java.util.Calendar.MONTH) + 1 < birth.substring(5, 7).toInt()) age--
        age
    } catch (_: Exception) { 0 }
}
